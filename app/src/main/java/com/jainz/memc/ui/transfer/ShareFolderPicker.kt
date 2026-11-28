package com.jainz.memc.ui.transfer

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jainz.memc.MemcApp
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.smb.SmbPool
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks a folder on one of the network shares: first the share, then folders inside it.
 * "New folder" creates one right away (prefilled with [suggestedName]).
 */
@Composable
fun ShareFolderPicker(
    title: String,
    confirmLabel: String,
    suggestedName: String? = null,
    onDismiss: () -> Unit,
    onPick: (SourceEntity, String) -> Unit,
) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by remember { g.db.sources().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var source by remember { mutableStateOf<SourceEntity?>(null) }
    var path by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf<String?>(null) }

    val folders by produceState<Result<List<String>>?>(null, source, path, reload) {
        val s = source ?: return@produceState
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                g.smb.list(g.smb.location(s, path)).filter { it.isDir && !it.name.startsWith(".") }
                    .map { it.path }.sortedBy { it.lowercase() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title)
                val where = source?.let { s -> s.name + if (path.isNotBlank()) " / " + path.replace("/", " / ") else "" }
                if (where != null) {
                    Text(where, style = MaterialTheme.typography.bodySmall, color = Palette.Accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 380.dp)) {
                val s = source
                if (s == null) {
                    if (sources.isEmpty()) {
                        Text("Add a network share in Library first.", color = Palette.TextDim)
                    }
                    LazyColumn {
                        items(sources, key = { it.id }) { src ->
                            PickRow(Icons.Filled.Dns, src.name, "\\\\${src.host}\\${src.share}") {
                                source = src
                                path = src.path.trim('/')
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (path.isBlank()) source = null else path = path.substringBeforeLast('/', "")
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                        Text(if (path.isBlank()) "Back to the shares" else "Up one folder", color = Palette.TextDim)
                        Box(Modifier.weight(1f))
                        IconButton(onClick = { naming = suggestedName ?: "" }) {
                            Icon(Icons.Filled.CreateNewFolder, "New folder", tint = Palette.Accent)
                        }
                    }
                    val r = folders
                    when {
                        r == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                        r.isFailure -> Text(
                            SmbPool.friendlyError(r.exceptionOrNull() ?: Exception("Unknown error")),
                            color = Palette.Danger,
                        )
                        else -> LazyColumn {
                            val list = r.getOrDefault(emptyList())
                            if (list.isEmpty()) item { Text("No folders here.", color = Palette.TextFaint, modifier = Modifier.padding(8.dp)) }
                            items(list, key = { it }) { p ->
                                PickRow(Icons.Filled.Folder, p.substringAfterLast('/'), null) { path = p }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = source != null, onClick = { source?.let { onPick(it, path.trim('/')) } }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    naming?.let { initial ->
        var name by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val s = source ?: return@TextButton
                    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val target = if (path.isBlank()) clean else "$path/$clean"
                    scope.launch {
                        val problem = withContext(Dispatchers.IO) {
                            runCatching { g.smb.mkdirs(g.smb.location(s, target)) }.exceptionOrNull()
                        }
                        if (problem != null) {
                            Toast.makeText(context, "Couldn't create it: ${SmbPool.friendlyError(problem)}", Toast.LENGTH_LONG).show()
                        } else {
                            path = target
                            reload++
                        }
                        naming = null
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = Palette.Accent)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1)
        }
    }
}
