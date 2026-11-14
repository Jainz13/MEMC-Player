package com.jainz.memc.ui.components

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jainz.memc.MemcApp
import com.jainz.memc.data.VideoFiles
import com.jainz.memc.data.db.VideoEntity
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Asks before permanently deleting one or more video files, then deletes them.
 * [what] names them in the question ("this episode", "all 8 episodes of season 1").
 */
@Composable
fun DeleteVideosDialog(videos: List<VideoEntity>, what: String, onDismiss: () -> Unit, onDeleted: (Int) -> Unit = {}) {
    val context = LocalContext.current
    var working by remember { mutableStateOf(false) }
    val place by produceState("", videos) { value = videos.firstOrNull()?.let { VideoFiles.place(it) } ?: "" }
    val size = videos.sumOf { it.size }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Delete $what?") },
        text = {
            Text(
                (if (videos.size == 1) videos[0].fileName + "\n\n" else "") +
                    "${if (videos.size == 1) "The file" else "${videos.size} files"} (${Format.size(size)}) will be permanently " +
                    "deleted from $place. This can't be undone."
            )
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                // App-wide scope: the deletion finishes even if this screen closes.
                MemcApp.graph.scope.launch {
                    var done = 0
                    var problem: String? = null
                    for (v in videos) {
                        val p = VideoFiles.delete(context.applicationContext, v)
                        if (p == null) done++ else problem = p
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            when {
                                problem == null -> if (done == 1) "Deleted" else "Deleted $done files"
                                done == 0 -> "Not deleted: $problem"
                                else -> "Deleted $done of ${videos.size}. The rest: $problem"
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                        working = false
                        onDismiss()
                        if (done > 0) onDeleted(done)
                    }
                }
            }) { Text(if (working) "Deleting…" else "Delete", color = Palette.Danger) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } },
    )
}
