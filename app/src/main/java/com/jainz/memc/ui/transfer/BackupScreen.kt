package com.jainz.memc.ui.transfer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.work.WorkInfo
import com.jainz.memc.MemcApp
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.transfer.BackupRule
import com.jainz.memc.data.transfer.BackupRules
import com.jainz.memc.data.transfer.SyncWorker
import com.jainz.memc.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

/**
 * Automatic backups: each one sends a phone folder to a folder on a network share, in the
 * background every hour (on Wi-Fi, and optionally only while charging), or right away with
 * "Sync now".
 */
@Composable
fun BackupScreen(nav: NavController) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val json by g.settings.backupRules.flow.collectAsStateWithLifecycle()
    val rules = remember(json) { BackupRules.parse(json) }
    val sources by remember { g.db.sources().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val running by remember { SyncWorker.runningInfo(context) }.collectAsStateWithLifecycle(emptyList())
    val busy = running.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    // Adding: 1) phone folder from Android's picker, 2) share folder, 3) options.
    var pickedTree by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var draft by remember { mutableStateOf<BackupRule?>(null) }
    var removing by remember { mutableStateOf<BackupRule?>(null) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Folder"
            pickedTree = uri to name
        }
    }

    fun save(rule: BackupRule) {
        BackupRules.upsert(rule)
        SyncWorker.schedule(context)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Automatic backup", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (rules.isNotEmpty()) {
                TextButton(enabled = !busy, onClick = {
                    SyncWorker.syncNow(context)
                    Toast.makeText(context, "Backing up now - progress shows in the notifications", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Syncing…" else "Sync now")
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Palette.Accent)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)) {
            item {
                Text(
                    "Pick a folder on this phone (for example Download/1DMP) and a folder on a network share. " +
                        "New files are uploaded by themselves every hour, and files already on the share (same name and size) are skipped. " +
                        "Files changed in the last two minutes (downloads still running) wait for the next run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    rule,
                    sources.firstOrNull { it.id == rule.sourceId },
                    onChange = { save(it) },
                    onSyncNow = { SyncWorker.syncNow(context, rule.id) },
                    onRemove = { removing = rule },
                )
            }
            item {
                Button(onClick = { treePicker.launch(null) }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add a backup")
                }
            }
        }
    }

    pickedTree?.let { (uri, name) ->
        ShareFolderPicker(
            title = "Back up \"$name\" to…",
            confirmLabel = "Use this folder",
            suggestedName = name,
            onDismiss = { pickedTree = null },
            onPick = { source, folder ->
                draft = BackupRule(treeUri = uri.toString(), label = name, sourceId = source.id, folder = folder)
                pickedTree = null
            },
        )
    }

    draft?.let { d ->
        var rule by remember(d.id) { mutableStateOf(d) }
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("Back up \"${d.label}\"") },
            text = { RuleOptions(rule) { rule = it } },
            confirmButton = {
                TextButton(onClick = {
                    save(rule)
                    SyncWorker.syncNow(context, rule.id)
                    draft = null
                }) { Text("Save and sync now") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Cancel") } },
        )
    }

    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this backup?") },
            text = { Text("\"${r.label}\" stops being backed up. Nothing is deleted on the phone or on the share.") },
            confirmButton = {
                TextButton(onClick = {
                    BackupRules.remove(r.id)
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(r.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    SyncWorker.schedule(context)
                    removing = null
                }) { Text("Remove", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RuleCard(
    rule: BackupRule,
    source: SourceEntity?,
    onChange: (BackupRule) -> Unit,
    onSyncNow: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(Palette.Surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Backup, null, tint = Palette.Accent)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(rule.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "→ " + (source?.name ?: "missing share") + (if (rule.folder.isNotBlank()) " / " + rule.folder.replace("/", " / ") else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = { onChange(rule.copy(enabled = it)) })
        }
        if (rule.lastRunAt > 0) {
            Text(
                "Last run ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(rule.lastRunAt))}: ${rule.lastResult}",
                style = MaterialTheme.typography.bodySmall,
                color = if (rule.lastResult.contains("failed") || rule.lastResult.contains("no longer")) Palette.Danger else Palette.TextFaint,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        RuleOptions(rule, onChange)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onSyncNow) { Text("Sync now") }
            TextButton(onClick = onRemove) { Text("Remove", color = Palette.Danger) }
        }
    }
}

@Composable
private fun RuleOptions(rule: BackupRule, onChange: (BackupRule) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        OptionRow("Only video files", "Otherwise every file in the folder", rule.videosOnly) { onChange(rule.copy(videosOnly = it)) }
        OptionRow("Only on Wi-Fi", "Background runs wait for an unmetered network", rule.wifiOnly) { onChange(rule.copy(wifiOnly = it)) }
        OptionRow("Only while charging", null, rule.chargingOnly) { onChange(rule.copy(chargingOnly = it)) }
        OptionRow(
            "Remove from the phone after upload",
            "Frees space: each file is deleted once its copy on the share is complete and checked",
            rule.deleteAfter,
        ) { onChange(rule.copy(deleteAfter = it)) }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
