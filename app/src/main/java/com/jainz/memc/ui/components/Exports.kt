package com.jainz.memc.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jainz.memc.export.ExportJob
import com.jainz.memc.export.ExportManager
import com.jainz.memc.export.ExportStatus
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.theme.Palette

/** Returns a function that queues a 60 fps export, asking once for notification permission. */
@Composable
fun rememberExportAction(): (uri: String, title: String) -> Unit {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { uri, title ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ExportManager.enqueue(context, uri, title)
        android.widget.Toast.makeText(context, "Exporting \"$title\" at 60 fps - see Library → Exports", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** The export queue with progress, shown in the Library tab. Renders nothing when empty. */
@Composable
fun ExportsSection() {
    val jobs by ExportManager.jobs.collectAsStateWithLifecycle()
    if (jobs.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("60 fps exports", Modifier.weight(1f).padding(top = 12.dp))
            if (jobs.any { it.status != ExportStatus.QUEUED && it.status != ExportStatus.RUNNING }) {
                TextButton(onClick = { ExportManager.clearFinished() }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) {
                    Text("Clear finished")
                }
            }
        }
        jobs.asReversed().forEach { ExportRow(it) }
    }
}

@Composable
private fun ExportRow(job: ExportJob) {
    val context = LocalContext.current
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val statusText = when (job.status) {
                ExportStatus.QUEUED -> "Waiting"
                ExportStatus.RUNNING -> if (job.progress >= 0) "Rendering ${job.progress}%" else "Starting…"
                ExportStatus.DONE -> "Done"
                ExportStatus.FAILED -> "Failed"
                ExportStatus.CANCELLED -> "Cancelled"
            }
            Text(
                listOfNotNull(statusText, job.message).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = when (job.status) {
                    ExportStatus.FAILED -> Palette.Danger
                    ExportStatus.DONE -> Palette.Accent
                    else -> Palette.TextDim
                },
            )
            if (job.status == ExportStatus.RUNNING) {
                if (job.progress >= 0) {
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Accent,
                        trackColor = Palette.SurfaceHigh,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent, trackColor = Palette.SurfaceHigh)
                }
            }
            Row {
                when (job.status) {
                    ExportStatus.QUEUED, ExportStatus.RUNNING ->
                        TextButton(onClick = { ExportManager.cancel(context, job.id) }) { Text("Cancel") }
                    ExportStatus.DONE -> job.outputUri?.let { uri ->
                        TextButton(onClick = { Play.uri(context, uri, "${job.title} (60 fps)") }) { Text("Play") }
                    }
                    ExportStatus.FAILED, ExportStatus.CANCELLED ->
                        TextButton(onClick = { ExportManager.retry(context, job.id) }) { Text("Try again") }
                }
            }
        }
    }
}
