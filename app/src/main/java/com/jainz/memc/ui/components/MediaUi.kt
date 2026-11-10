package com.jainz.memc.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jainz.memc.MemcApp
import com.jainz.memc.data.db.VideoEntity
import com.jainz.memc.download.DownloadJob
import com.jainz.memc.download.DownloadStatus
import com.jainz.memc.download.Downloads
import com.jainz.memc.player.DeviceCaps
import com.jainz.memc.player.MediaInfo
import com.jainz.memc.player.MediaProbe
import com.jainz.memc.ui.Format
import com.jainz.memc.ui.Play
import com.jainz.memc.ui.theme.Palette

/** Video, audio and subtitle tracks of a file, read from the file itself. */
@Composable
fun MediaInfoSection(video: VideoEntity) {
    val context = LocalContext.current
    var info by remember(video.id) { mutableStateOf(MediaProbe.cached(context, video.id)) }
    var failed by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(video.id) {
        if (info == null) {
            info = MediaProbe.probe(context, MemcApp.graph, video)
            failed = info == null
        }
    }
    Column {
        SectionTitle("Media info", Modifier.padding(top = 18.dp))
        val i = info
        when {
            i != null -> MediaInfoBody(i)
            failed -> Text(
                "Couldn't read this file. Is the network share reachable?",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            else -> Text(
                "Reading the file…",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun MediaInfoBody(i: MediaInfo) {
    val context = LocalContext.current
    val screenHdr = remember { DeviceCaps.read(context).hdrTypes }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TechBadges(i.tags)
        InfoLine("Video", listOf(i.video))
        InfoLine("Audio", i.audio.ifEmpty { listOf("No audio tracks") })
        if (i.subtitles.isNotEmpty()) InfoLine("Subtitles", i.subtitles)
        val tags = i.tags.split('|')
        val notes = buildList {
            if ("DV" in tags && "Dolby Vision" !in screenHdr) {
                add("This phone's screen has no Dolby Vision, so the film's HDR10 layer is shown instead.")
            }
            if ("HDR10+" in tags && "HDR10+" !in screenHdr) add("This screen doesn't report HDR10+; it plays as HDR10.")
            if (tags.any { it in setOf("DTS", "DTS-HD", "DTS:X", "TrueHD") } || ("ATMOS" in tags && i.audio.any { "TrueHD" in it })) {
                add("DTS and TrueHD tracks are decoded by the app's FFmpeg decoder and play as surround/stereo PCM.")
            }
        }
        notes.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun InfoLine(label: String, values: List<String>) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.width(80.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            values.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Text) }
        }
    }
}

/** Returns a function that starts copying a network file to the phone. */
@Composable
fun rememberDownloadAction(): (video: VideoEntity, title: String) -> Unit {
    val context = LocalContext.current
    return { video, title ->
        Downloads.enqueue(context, video.uri, video.fileName, title)
        Toast.makeText(context, "Downloading \"$title\" - see Library → Downloads", Toast.LENGTH_LONG).show()
    }
}

/** The download queue with progress, shown in the Library tab. Renders nothing when empty. */
@Composable
fun DownloadsSection() {
    val jobs by Downloads.jobs.collectAsStateWithLifecycle()
    if (jobs.isEmpty()) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Downloads", Modifier.weight(1f).padding(top = 12.dp))
            if (jobs.any { it.status != DownloadStatus.QUEUED && it.status != DownloadStatus.RUNNING }) {
                TextButton(onClick = { Downloads.clearFinished() }, modifier = Modifier.padding(end = 8.dp, top = 12.dp)) {
                    Text("Clear finished")
                }
            }
        }
        jobs.asReversed().forEach { DownloadRow(it) }
    }
}

@Composable
private fun DownloadRow(job: DownloadJob) {
    val context = LocalContext.current
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (job.status) {
                DownloadStatus.QUEUED -> "Waiting"
                DownloadStatus.RUNNING -> {
                    val left = if (job.bytesPerSecond > 0) ((job.bytesTotal - job.bytesDone) / job.bytesPerSecond).let {
                        if (it >= 60) "${it / 60} min left" else "$it s left"
                    } else null
                    listOfNotNull(
                        "${Format.size(job.bytesDone)} of ${Format.size(job.bytesTotal)}",
                        if (job.bytesPerSecond > 0) "${Format.size(job.bytesPerSecond)}/s" else null,
                        left,
                    ).joinToString(" · ")
                }
                DownloadStatus.DONE -> "Done"
                DownloadStatus.FAILED -> "Failed"
                DownloadStatus.CANCELLED -> "Cancelled"
            }
            Text(
                listOfNotNull(status, job.message).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = when (job.status) {
                    DownloadStatus.FAILED -> Palette.Danger
                    DownloadStatus.DONE -> Palette.Accent
                    else -> Palette.TextDim
                },
            )
            if (job.status == DownloadStatus.RUNNING && job.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { job.bytesDone.toFloat() / job.bytesTotal },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
            Row {
                when (job.status) {
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING ->
                        TextButton(onClick = { Downloads.cancel(context, job.id) }) { Text("Cancel") }
                    DownloadStatus.DONE -> job.outputUri?.let { uri ->
                        TextButton(onClick = { Play.uri(context, uri, job.title) }) { Text("Play") }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                        TextButton(onClick = { Downloads.retry(context, job.id) }) { Text("Try again") }
                }
            }
        }
    }
}
