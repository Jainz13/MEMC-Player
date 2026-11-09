package com.jainz.memc.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Streams a video from an SMB share to the player.
 *
 * SMB is request/response: one small read per call would stall on every network
 * round trip and stutter on 4K remuxes. Instead the file is fetched in 1 MB
 * chunks with several chunks requested ahead in parallel, so the network stays
 * busy while the player consumes the current chunk.
 */
@UnstableApi
class SmbDataSource(private val pool: SmbPool) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: File? = null
    private var fileSize = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private val chunks = LinkedHashMap<Long, Future<ByteArray>>()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val f = try {
            pool.openFile(pool.resolve(dataSpec.uri))
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f
        try {
            fileSize = f.fileInformation.standardInformation.endOfFile
        } catch (e: Exception) {
            throw DataSourceException(IOException(e), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (dataSpec.position > fileSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            min(dataSpec.length, fileSize - position)
        } else {
            fileSize - position
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        val idx = position / CHUNK
        val data = chunk(idx)
        val inChunk = (position - idx * CHUNK).toInt()
        if (inChunk >= data.size) return C.RESULT_END_OF_INPUT

        val n = min(min(length, data.size - inChunk).toLong(), bytesRemaining).toInt()
        System.arraycopy(data, inChunk, buffer, offset, n)
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    private fun chunk(idx: Long): ByteArray {
        val f = file ?: throw IOException("SMB file closed")
        val lastIdx = (fileSize - 1) / CHUNK
        // Drop chunks behind us, then make sure the read-ahead window is requested.
        val it = chunks.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key < idx) {
                e.value.cancel(false)
                it.remove()
            }
        }
        for (i in idx..min(idx + AHEAD, lastIdx)) {
            if (!chunks.containsKey(i)) {
                chunks[i] = executor.submit(Callable { readChunk(f, i) })
            }
        }
        val future = chunks[idx] ?: throw IOException("SMB read scheduling failed")
        return try {
            future.get()
        } catch (e: ExecutionException) {
            chunks.remove(idx)
            val cause = e.cause
            throw DataSourceException(
                cause as? IOException ?: IOException(cause),
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
    }

    private fun readChunk(f: File, index: Long): ByteArray {
        val start = index * CHUNK
        val len = min(CHUNK.toLong(), fileSize - start).toInt()
        val buf = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = f.read(buf, start + got, got, len - got)
            if (n <= 0) break
            got += n
        }
        return if (got == len) buf else buf.copyOf(got)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        chunks.values.forEach { it.cancel(false) }
        chunks.clear()
        try {
            file?.close()
        } catch (_: Exception) {
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val CHUNK = SmbPool.READ_CHUNK
        /** Chunks requested ahead of the one being read (about 6 MB in flight). */
        private const val AHEAD = 6L

        private val threadCount = AtomicInteger()
        private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "smb-read-${threadCount.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
