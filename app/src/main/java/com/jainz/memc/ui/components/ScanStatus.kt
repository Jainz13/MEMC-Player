package com.jainz.memc.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.jainz.memc.data.scan.ScanWorker
import com.jainz.memc.ui.theme.Palette

/** Thin progress line shown while the library is scanning or downloading artwork. */
@Composable
fun ScanStatus(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info by remember { ScanWorker.observe(context) }.collectAsStateWithLifecycle(null)
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val failMessage = info?.takeIf { it.state == WorkInfo.State.SUCCEEDED }
        ?.outputData?.getString(ScanWorker.KEY_MESSAGE)

    AnimatedVisibility(running) {
        val stage = info?.progress?.getString(ScanWorker.KEY_STAGE) ?: "Updating library"
        val done = info?.progress?.getInt(ScanWorker.KEY_DONE, 0) ?: 0
        val total = info?.progress?.getInt(ScanWorker.KEY_TOTAL, 0) ?: 0
        Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(stage, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
            }
        }
    }
    if (!running && failMessage != null) {
        Text(
            failMessage,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Danger,
            modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}
