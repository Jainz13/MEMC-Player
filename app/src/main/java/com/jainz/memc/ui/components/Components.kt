package com.jainz.memc.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.composed
import com.jainz.memc.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jainz.memc.ui.theme.Palette

val CardShape = RoundedCornerShape(10.dp)

/** Artwork with a soft gradient placeholder that shows the title until the image loads. */
@Composable
fun Artwork(
    url: String?,
    placeholderText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(Palette.SurfaceHigh, Palette.Surface))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            placeholderText,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Palette.TextFaint,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = placeholderText,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 2:3 poster card with optional progress bar and watched tick. */
/**
 * On the TV variant, draws a clear ring around whatever the remote has selected. Put it before
 * `clickable` in the modifier chain. Does nothing on the phone.
 */
fun Modifier.tvFocusRing(shape: Shape = CardShape): Modifier =
    if (!BuildConfig.TV) this else composed {
        var focused by remember { mutableStateOf(false) }
        onFocusChanged { focused = it.hasFocus }
            .then(if (focused) Modifier.border(3.dp, Palette.Accent, shape) else Modifier)
    }

@Composable
fun PosterCard(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp? = 116.dp,
    subtitle: String? = null,
    progress: Float = 0f,
    watched: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        (if (width != null) modifier.width(width) else modifier)
            .tvFocusRing()
            .clip(CardShape)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(CardShape)
                .border(0.5.dp, Palette.Stroke, CardShape)
        ) {
            Artwork(url, title, Modifier.fillMaxSize())
            if (watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress > 0f && !watched) ProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = Palette.TextDim,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** 16:9 card for continue-watching and episodes. */
@Composable
fun LandscapeCard(
    url: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
    progress: Float = 0f,
    watched: Boolean = false,
    overline: String? = null,
    onClick: () -> Unit,
) {
    Column(modifier.width(width).clip(CardShape).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(CardShape)
                .border(0.5.dp, Palette.Stroke, CardShape)
        ) {
            Artwork(url, title, Modifier.fillMaxSize())
            if (overline != null) {
                Text(
                    overline,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress > 0f && !watched) ProgressStrip(progress, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color(0x66000000))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(Palette.Accent)
        )
    }
}

@Composable
fun WatchedTick(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .background(Palette.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
    }
}

/** A titled horizontal row of cards. */
@Composable
fun Shelf(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(top = 22.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onSeeAll != null) Modifier.clickable(onClick = onSeeAll) else Modifier)
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (onSeeAll != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Small tech badges: 4K, HDR10+, DV, ATMOS… */
@Composable
fun TechBadges(tech: String, modifier: Modifier = Modifier) {
    val tags = tech.split('|').filter { it.isNotBlank() }
    if (tags.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            val hdr = tag.startsWith("HDR") || tag == "DV" || tag == "HLG"
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = if (hdr) Palette.Accent else Palette.Text,
                modifier = Modifier
                    .border(1.dp, if (hdr) Palette.Accent.copy(alpha = 0.7f) else Palette.TextDim.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Full-bleed artwork fading into the page background. */
@Composable
fun BackdropHeader(
    url: String?,
    title: String,
    height: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(height)) {
        Artwork(url, "", Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x66000000),
                        0.25f to Color.Transparent,
                        0.55f to Palette.Canvas.copy(alpha = 0.35f),
                        1f to Palette.Canvas,
                    )
                )
        )
        content()
    }
}

@Composable
fun CastShelf(cast: List<com.jainz.memc.data.db.CastMember>) {
    if (cast.isEmpty()) return
    Shelf("Cast") {
        items(cast.size) { i ->
            val c = cast[i]
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Palette.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Person, null, tint = Palette.TextFaint)
                    val url = com.jainz.memc.data.tmdb.Tmdb.profile(c.profilePath)
                    if (url != null) {
                        AsyncImage(url, c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(
                    c.name, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    c.character, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                    color = Palette.TextDim, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextDim,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, textAlign = TextAlign.Center)
        action()
    }
}
