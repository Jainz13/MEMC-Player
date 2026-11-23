package com.jainz.memc.ui.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jainz.memc.ui.components.tvFocusRing
import com.jainz.memc.ui.theme.Palette

/** Quick filters shared by the Movies and TV Shows grids. */
enum class LibraryFilter(val label: String) {
    UNWATCHED("Unwatched"), UHD("4K"), HDR("HDR"), DV("Dolby Vision");

    /** [tech] is the '|'-joined tag list from the file names (see NameParser.detectTech). */
    fun matches(tech: String, unwatched: Boolean): Boolean {
        val tags = tech.split('|')
        return when (this) {
            UNWATCHED -> unwatched
            UHD -> "4K" in tags
            HDR -> tags.any { it.startsWith("HDR") || it == "HLG" || it == "DV" }
            DV -> "DV" in tags
        }
    }
}

/** Sort button (showing the current order) followed by the filter chips, in one scrolling row. */
@Composable
fun FilterBar(
    sort: SortBy,
    onSort: (SortBy) -> Unit,
    active: Set<LibraryFilter>,
    onToggle: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = Palette.Surface,
        labelColor = Palette.TextDim,
        iconColor = Palette.TextDim,
        selectedContainerColor = Palette.AccentDim,
        selectedLabelColor = Palette.Accent,
        selectedLeadingIconColor = Palette.Accent,
    )
    LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = false,
                    onClick = { menu = true },
                    label = { Text(sort.label) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, "Sort", Modifier.size(18.dp)) },
                    colors = colors,
                    modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                )
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    SortBy.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { onSort(s); menu = false },
                            trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, null) },
                        )
                    }
                }
            }
        }
        LibraryFilter.entries.forEach { f ->
            item {
                FilterChip(
                    selected = f in active,
                    onClick = { onToggle(f) },
                    label = { Text(f.label) },
                    leadingIcon = if (f in active) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                    } else null,
                    colors = colors,
                    modifier = Modifier.tvFocusRing(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                )
            }
        }
    }
}
