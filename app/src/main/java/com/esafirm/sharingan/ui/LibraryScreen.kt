package com.esafirm.sharingan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esafirm.sharingan.R
import com.esafirm.sharingan.data.ReadStatus
import com.esafirm.sharingan.data.Series
import com.esafirm.sharingan.data.SeriesItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onOpenSeries: (Series) -> Unit,
    onResume: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenScraper: () -> Unit,
) {
    val all by vm.allSeries.collectAsState()
    val shown by vm.shown.collectAsState()
    val sort by vm.sort.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val importing by vm.importing.collectAsState()
    val suggestions by vm.shelfSuggestions.collectAsState()
    val showSuggestions by vm.showSuggestions.collectAsState()
    val haptic = LocalHapticFeedback.current

    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Series?>(null) }
    var coverFor by remember { mutableStateOf<Series?>(null) }
    var renaming by remember { mutableStateOf<SeriesItem?>(null) }
    var deleting by remember { mutableStateOf<Series?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val library = all
    val resume = remember(library) { findResume(library.orEmpty()) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Sharingan", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.query.value = ""
                    }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Find manga online…") },
                                onClick = { sortMenu = false; onOpenScraper() },
                            )
                            HorizontalDivider()
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label, fontWeight = if (mode == sort) FontWeight.Bold else FontWeight.Normal) },
                                    leadingIcon = { if (mode == sort) Icon(Icons.Default.Check, null) },
                                    onClick = { vm.sort.value = mode; sortMenu = false },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/pdf")) },
                expanded = gridState.firstVisibleItemIndex == 0,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add manga") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            AnimatedVisibility(searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    singleLine = true,
                    placeholder = { Text("Search your shelf") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (importing > 0) LinearProgressIndicator(Modifier.fillMaxWidth())

            when {
                library == null -> Box(Modifier.fillMaxSize())
                library.isEmpty() -> EmptyShelf()
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (resume != null && query.isBlank() && filter == null) {
                        item(key = "resume", span = { GridItemSpan(maxLineSpan) }) {
                            ContinueCard(resume.first, resume.second, onClick = { onResume(resume.second.manga.id) })
                        }
                    }
                    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(filter == null, { vm.filter.value = null }, { Text("All (${library.size})") })
                            }
                            items(ReadStatus.entries.toList()) { s ->
                                val n = library.count { it.status == s }
                                FilterChip(filter == s, { vm.filter.value = s }, { Text("${s.label} ($n)") })
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Nothing matches.",
                                Modifier.fillMaxWidth().padding(32.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    items(shown, key = { it.key }) { s ->
                        Box(Modifier.animateItem()) {
                            SeriesCard(
                                s,
                                onClick = { onOpenSeries(s) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuFor = s
                                },
                            )
                            DropdownMenu(menuFor?.key == s.key, { menuFor = null }) {
                                DropdownMenuItem({ Text("Find cover…") }, { menuFor = null; coverFor = s })
                                DropdownMenuItem({ Text("Mark all as read") }, { menuFor = null; vm.markAllRead(s) })
                                DropdownMenuItem({ Text("Mark all as unread") }, { menuFor = null; vm.markAllUnread(s) })
                                if (s.items.size == 1) {
                                    DropdownMenuItem({ Text("Rename") }, { menuFor = null; renaming = s.items.first() })
                                }
                                DropdownMenuItem({ Text("Delete") }, { menuFor = null; deleting = s })
                            }
                        }
                    }
                    if (showSuggestions && suggestions.isNotEmpty() && query.isBlank() && filter == null) {
                        item(key = "suggested", span = { GridItemSpan(maxLineSpan) }) {
                            SuggestionsSection(
                                title = "Suggested for you",
                                hint = "Similar to what you've been reading · from AniList",
                                items = suggestions,
                                loading = false,
                                failed = false,
                                onRetry = {},
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    coverFor?.let { CoverPickerSheet(it, vm) { coverFor = null } }
    renaming?.let { item -> RenameDialog(item.manga.title, { vm.rename(item.manga, it) }) { renaming = null } }
    deleting?.let { s ->
        ConfirmDialog(
            title = "Delete \"${s.title}\"?",
            text = if (s.items.size == 1) "The PDF is removed from the app. Your original file is untouched."
            else "All ${s.items.size} files in this series are removed from the app. Your original files are untouched.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteSeries(s) },
            onDismiss = { deleting = null },
        )
    }
}

private fun findResume(all: List<Series>): Pair<Series, SeriesItem>? =
    all.flatMap { s -> s.items.map { s to it } }
        .filter { it.second.manga.status == ReadStatus.READING }
        .maxByOrNull { it.second.manga.lastReadAt }

@Composable
private fun EmptyShelf() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(160.dp),
        )
        Text("Your shelf is empty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap “Add manga” to pick PDFs, share them straight from Telegram, or find manga online (⋮ menu).\nVolumes of the same series group together automatically.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ContinueCard(series: Series, item: SeriesItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(series.coverFile, series.title, Modifier.width(64.dp).aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("CONTINUE READING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(series.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.displayLabel} · page ${item.manga.lastPage + 1} of ${item.manga.pageCount}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { item.manga.progress }, modifier = Modifier.fillMaxWidth())
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.PlayArrow, "Resume", tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesCard(series: Series, onClick: () -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")

    Column(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(16.dp))) {
            CoverImage(series.coverFile, series.title, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
            when {
                series.status == ReadStatus.COMPLETED ->
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(CompletedGreen),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Check, "Completed", tint = Color.White, modifier = Modifier.size(16.dp)) }
                series.items.size > 1 ->
                    Text(
                        "${series.items.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
            }
            if (series.progress > 0f && series.status != ReadStatus.COMPLETED) {
                LinearProgressIndicator(
                    progress = { series.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    trackColor = Color.Black.copy(alpha = 0.35f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(series.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            seriesSubtitle(series),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun seriesSubtitle(s: Series): String =
    if (s.items.size == 1) {
        s.items.first().parsed.label.ifBlank { "${s.items.first().manga.pageCount} pages" }
    } else {
        val n = s.items.size
        val noun = if (n == 1) s.unitName else s.unitName + "s"
        if (s.unread in 1 until n) "$n $noun · ${s.unread} unread" else "$n $noun"
    }
