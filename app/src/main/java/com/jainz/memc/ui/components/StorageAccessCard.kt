package com.jainz.memc.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jainz.memc.MemcApp
import com.jainz.memc.data.scan.LibraryScanner
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.data.scan.StorageAccess
import com.jainz.memc.ui.theme.Palette

/** Current storage access, refreshed every time the app comes back to the foreground. */
@Composable
fun rememberStorageAccess(): StorageAccess {
    val g = MemcApp.graph
    val level by g.settings.lastAccessLevel.flow.collectAsStateWithLifecycle()
    return StorageAccess.entries.getOrElse(level) { StorageAccess.NONE }
}

/**
 * Returns two actions: ask for full file access (Android's "All files access" page, which lets
 * the app see every video and the subtitle files next to them), or the smaller media-only popup.
 */
@Composable
fun rememberStorageRequests(): Pair<() -> Unit, () -> Unit> {
    val context = LocalContext.current
    val g = MemcApp.graph
    fun refresh() {
        val access = LibraryScanner(context, g).storageAccess()
        g.settings.lastAccessLevel.set(access.ordinal)
        if (access != StorageAccess.NONE) ScanWorker.enqueue(context)
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    val askFull: () -> Unit = remember {
        {
            if (Build.VERSION.SDK_INT >= 30) {
                val appPage = Intent(
                    SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { settingsLauncher.launch(appPage) }
                    .onFailure { settingsLauncher.launch(Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else {
                mediaLauncher.launch(LibraryScanner.devicePermissions())
            }
        }
    }
    val askMedia: () -> Unit = remember { { mediaLauncher.launch(LibraryScanner.devicePermissions()) } }
    return askFull to askMedia
}

/** Shows what the app can read on the phone, the last scan result, and buttons to fix access. */
@Composable
fun StorageAccessCard(modifier: Modifier = Modifier) {
    val g = MemcApp.graph
    val context = LocalContext.current
    val access = rememberStorageAccess()
    val info by g.settings.deviceScanInfo.flow.collectAsStateWithLifecycle()
    val (askFull, askMedia) = rememberStorageRequests()

    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone storage", style = MaterialTheme.typography.titleMedium)
            Text(
                "Access: ${access.label}",
                style = MaterialTheme.typography.bodySmall,
                color = if (access == StorageAccess.FULL) Palette.Accent else Palette.TextDim,
            )
            if (info.isNotBlank()) {
                Text(info, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            when (access) {
                StorageAccess.FULL -> OutlinedButton(onClick = { ScanWorker.enqueue(context) }) { Text("Scan phone again") }
                StorageAccess.NONE -> {
                    Text(
                        "Allow \"All files access\" so every video is found, including ones the gallery hides, " +
                            "and subtitle files next to your videos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = askFull) { Text("Allow all files") }
                        TextButton(onClick = askMedia) { Text("Videos only") }
                    }
                }
                else -> {
                    Text(
                        "Upgrade to \"All files access\" to also find hidden folders and load .srt subtitles next to videos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = askFull) { Text("Allow all files") }
                        OutlinedButton(onClick = { ScanWorker.enqueue(context) }) { Text("Scan again") }
                    }
                }
            }
        }
    }
}
