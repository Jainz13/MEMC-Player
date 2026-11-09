package com.jainz.memc.ui.library

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jainz.memc.MemcApp
import com.jainz.memc.core.Crypto
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiscoveredServer(val name: String, val host: String)

/** Add or edit an SMB share. Leaving the password empty when editing keeps the saved one. */
@Composable
fun ShareDialog(
    existing: SourceEntity?,
    prefill: DiscoveredServer?,
    onDismiss: () -> Unit,
) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: prefill?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: prefill?.host ?: "") }
    var share by remember { mutableStateOf(existing?.share ?: "") }
    var folder by remember { mutableStateOf(existing?.path ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var indexed by remember { mutableStateOf(existing?.indexed ?: true) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun build(): SourceEntity {
        val h = host.trim().removePrefix("smb://").removePrefix("\\\\").trimEnd('/')
        val hostPart = h.substringBefore('/')
        return SourceEntity(
            id = existing?.id ?: 0,
            name = name.trim().ifBlank { "${share.trim()} on $hostPart" },
            host = hostPart.substringBefore(':'),
            port = hostPart.substringAfter(':', "445").toIntOrNull() ?: 445,
            share = share.trim().trim('/', '\\'),
            path = folder.trim().trim('/', '\\').replace('\\', '/'),
            username = user.trim(),
            passwordEnc = if (password.isEmpty() && existing != null) existing.passwordEnc else Crypto.encrypt(password),
            domain = existing?.domain ?: "",
            indexed = indexed,
            lastScanAt = existing?.lastScanAt ?: 0,
        )
    }

    val valid = host.isNotBlank() && share.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add network share" else "Edit network share") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(host, { host = it }, label = { Text("Server (IP or name)") }, singleLine = true,
                    placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true,
                    placeholder = { Text("Media") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder inside share (optional)") }, singleLine = true,
                    placeholder = { Text("Movies") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username (blank = guest)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it },
                    label = { Text(if (existing != null) "Password (blank = keep)" else "Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Display name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Add to library", style = MaterialTheme.typography.bodyMedium)
                        Text("Scan for movies and shows", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    }
                    Switch(indexed, { indexed = it })
                }
                if (status != null) {
                    Text(
                        status ?: "",
                        color = if (status == "Connected") Palette.Accent else Palette.Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val s = build()
                        val id = if (existing == null) g.db.sources().insert(s) else { g.db.sources().update(s); s.id }
                        g.smb.forget(s.host)
                        if (s.indexed) ScanWorker.enqueue(context)
                        busy = false
                        if (id > 0) onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = valid && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Connecting…"
                            val s = build().copy(id = -1)
                            g.smb.forget(s.host)
                            status = withContext(Dispatchers.IO) { g.smb.test(s) } ?: "Connected"
                            busy = false
                        }
                    },
                ) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Watches the local network for SMB servers that announce themselves (most NAS boxes do). */
@Composable
fun rememberSmbDiscovery(): List<DiscoveredServer> {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<DiscoveredServer>() }
    DisposableEffect(Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val ip = info.host?.hostAddress ?: return
                            val server = DiscoveredServer(info.serviceName, ip)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if (found.none { it.host == ip }) found.add(server)
                            }
                        }
                    })
                }
            }
        }
        runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        onDispose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    return found
}
