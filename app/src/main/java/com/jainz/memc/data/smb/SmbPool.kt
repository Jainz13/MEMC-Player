package com.jainz.memc.data.smb

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.jainz.memc.core.Crypto
import com.jainz.memc.data.db.AppDatabase
import com.jainz.memc.data.db.SourceEntity
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbAuth(val user: String, val password: String, val domain: String)

data class SmbLocation(
    val host: String,
    val port: Int,
    val share: String,
    /** Path inside the share using forward slashes, no leading slash. "" = share root. */
    val path: String,
    val auth: SmbAuth,
) {
    val smbPath: String get() = path.trim('/').replace('/', '\\')
}

data class SmbEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    /** Path inside the share, forward slashes, no leading slash. */
    val path: String,
)

/**
 * Keeps SMB connections open and shared between the library scanner, the folder
 * browser and the player, so opening a video doesn't redo the login each time.
 */
class SmbPool(private val db: AppDatabase) {

    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withMultiProtocolNegotiate(true)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .withReadBufferSize(READ_CHUNK)
            .withDfsEnabled(false)
            .build()
    )

    private val shares = HashMap<String, DiskShare>()

    // ---- URIs -----------------------------------------------------------------

    /** smb://srcN@host[:port]/share/path - the source id lets us look up the saved login. */
    fun uriFor(source: SourceEntity, pathInShare: String): String {
        val b = Uri.Builder().scheme("smb")
            .encodedAuthority("src${source.id}@${source.host}" + if (source.port != 445) ":${source.port}" else "")
            .appendPath(source.share)
        pathInShare.trim('/').split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
        return b.build().toString()
    }

    fun authFor(source: SourceEntity) = SmbAuth(source.username, Crypto.decrypt(source.passwordEnc), source.domain)

    fun location(source: SourceEntity, pathInShare: String) = SmbLocation(
        host = source.host, port = source.port, share = source.share,
        path = pathInShare.trim('/'), auth = authFor(source),
    )

    /** Turns a smb:// URI back into a location with credentials. */
    fun resolve(uri: Uri): SmbLocation {
        val segments = uri.pathSegments
        if (segments.isEmpty()) throw IOException("SMB address has no share: $uri")
        val info = uri.userInfo ?: ""
        val sourceId = info.removePrefix("src").toLongOrNull()?.takeIf { info.startsWith("src") }
        if (sourceId != null) {
            // Library files: always use the share's current server address and login, so
            // editing the share (e.g. the NAS got a new IP) keeps every video playable.
            val src = db.sources().getBlocking(sourceId)
                ?: throw IOException("The network share for this video was removed")
            return SmbLocation(
                host = src.host, port = src.port, share = src.share,
                path = segments.drop(1).joinToString("/"),
                auth = authFor(src),
            )
        }
        val auth = when {
            info.contains(':') -> SmbAuth(info.substringBefore(':'), info.substringAfter(':'), "")
            info.isNotEmpty() -> SmbAuth(info, "", "")
            else -> SmbAuth("", "", "")
        }
        return SmbLocation(
            host = uri.host ?: throw IOException("SMB address has no server: $uri"),
            port = if (uri.port > 0) uri.port else 445,
            share = segments[0],
            path = segments.drop(1).joinToString("/"),
            auth = auth,
        )
    }

    // ---- Connections ----------------------------------------------------------

    @Synchronized
    private fun share(loc: SmbLocation, fresh: Boolean = false): DiskShare {
        val key = "${loc.host}:${loc.port}/${loc.share.lowercase()}/${loc.auth.user}"
        if (!fresh) shares[key]?.let { if (it.isConnected) return it }
        shares.remove(key)?.let { runCatching { it.close() } }

        val connection = client.connect(loc.host, loc.port)
        val session = if (loc.auth.user.isBlank()) {
            // Guest first (most home NAS shares), then anonymous.
            runCatching { connection.authenticate(AuthenticationContext.guest()) }
                .getOrElse { connection.authenticate(AuthenticationContext.anonymous()) }
        } else {
            connection.authenticate(
                AuthenticationContext(loc.auth.user, loc.auth.password.toCharArray(), loc.auth.domain.ifBlank { null })
            )
        }
        val share = session.connectShare(loc.share) as? DiskShare
            ?: throw IOException("\"${loc.share}\" is not a file share")
        shares[key] = share
        return share
    }

    /** Runs [block] on the share; if the connection went stale, reconnects once and retries. */
    private fun <T> withShare(loc: SmbLocation, block: (DiskShare) -> T): T {
        return try {
            block(share(loc))
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            try {
                block(share(loc, fresh = true))
            } catch (e2: Exception) {
                throw if (e2 is IOException) e2 else IOException(friendlyError(e2), e2)
            }
        }
    }

    fun openFile(loc: SmbLocation): File = withShare(loc) { share ->
        share.openFile(
            loc.smbPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    fun list(loc: SmbLocation): List<SmbEntry> = withShare(loc) { share ->
        val base = loc.path.trim('/')
        share.list(loc.smbPath).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val hidden = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L
            if (hidden || name.startsWith(".")) return@mapNotNull null
            SmbEntry(
                name = name,
                isDir = isDir,
                size = info.endOfFile,
                modified = info.lastWriteTime.toEpochMillis(),
                path = if (base.isEmpty()) name else "$base/$name",
            )
        }
    }

    /** Returns null when the share can be opened and listed, otherwise a readable error. */
    fun test(source: SourceEntity): String? = try {
        list(location(source, source.path))
        null
    } catch (e: Exception) {
        friendlyError(e)
    }

    @Synchronized
    fun forget(host: String) {
        val it = shares.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.startsWith("$host:")) {
                runCatching { e.value.close() }
                it.remove()
            }
        }
    }

    companion object {
        const val READ_CHUNK = 1024 * 1024

        fun friendlyError(e: Throwable): String {
            val msg = (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
            return when {
                msg.contains("STATUS_LOGON_FAILURE") -> "Wrong username or password"
                msg.contains("STATUS_BAD_NETWORK_NAME") -> "Share name not found on the server"
                msg.contains("STATUS_ACCESS_DENIED") -> "Access denied - check the share permissions"
                msg.contains("STATUS_OBJECT_PATH_NOT_FOUND") || msg.contains("STATUS_OBJECT_NAME_NOT_FOUND") ->
                    "Folder not found on the share"
                msg.contains("UnknownHost", ignoreCase = true) || msg.contains("Unable to resolve") ->
                    "Server name not found - try its IP address"
                msg.contains("timed out", ignoreCase = true) || msg.contains("ConnectException") ->
                    "Server did not respond - is it on the same Wi-Fi?"
                else -> msg
            }
        }
    }
}
