package com.jainz.memc.ui.transfer

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jainz.memc.data.db.SourceEntity
import com.jainz.memc.data.db.VideoEntity
import com.jainz.memc.data.transfer.Transfers
import com.jainz.memc.data.transfer.UploadWorker

/**
 * "Copy / Move to network share": pick a folder on a share, confirm, and the files go in the
 * background with a progress notification. A move deletes the phone copy only after the
 * share's copy is verified.
 */
@Composable
fun SendToShareDialog(videos: List<VideoEntity>, move: Boolean, suggestedFolder: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val local = remember(videos) { videos.filter { !it.uri.startsWith("smb://") } }
    var target by remember { mutableStateOf<Pair<SourceEntity, String>?>(null) }
    val verb = if (move) "Move" else "Copy"
    val pick = target
    if (pick == null) {
        ShareFolderPicker(
            title = "$verb ${if (local.size == 1) "\"${local.first().fileName}\"" else "${local.size} videos"} to…",
            confirmLabel = "$verb here",
            suggestedName = suggestedFolder,
            onDismiss = onDismiss,
            onPick = { s, f -> target = s to f },
        )
    } else {
        val (source, folder) = pick
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("$verb ${Transfers.amount(local.size, local.sumOf { it.size })}?") },
            text = {
                Text(
                    "To ${source.name}" + (if (folder.isNotBlank()) " / ${folder.replace("/", " / ")}" else "") + "." +
                        (if (move) "\n\nEach file is removed from this phone once its copy on the share is complete and checked." else "") +
                        "\n\nIt runs in the background; progress shows in the notifications. Files already on the share (same name and size) are skipped.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    UploadWorker.enqueue(context, local.map { it.id }, source.id, folder, move)
                    Toast.makeText(context, "${if (move) "Moving" else "Copying"} to ${source.name} in the background", Toast.LENGTH_LONG).show()
                    onDismiss()
                }) { Text(verb) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("Back") } },
        )
    }
}
