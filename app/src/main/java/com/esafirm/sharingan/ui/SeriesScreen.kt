package com.esafirm.sharingan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esafirm.sharingan.data.ReadStatus
import com.esafirm.sharingan.data.Series
import com.esafirm.sharingan.data.SeriesItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(vm: LibraryViewModel, key: String, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val all by vm.allSeries.collectAsState()
    val list = all ?: return
    val series = list.firstOrNull { it.key == key }
    if (series == null) {
        // The last file in this series was deleted.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var menu by remember { mutableStateOf(false) }
    var coverOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Find cover…") }, { menu = false; coverOpen = true })
                            DropdownMenuItem({ Text("Mark all as read") }, { menu = false; vm.markAllRead(series) })
                            DropdownMenuItem({ Text("Mark all as unread") }, { menu = false; vm.markAllUnread(series) })
                            DropdownMenuItem({ Text("Delete series") }, { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Header(series, onContinue = { onOpen(series.continueItem.manga.id) }, onCover = { coverOpen = true })
            }
            items(series.items, key = { it.manga.id }) { item ->
                ChapterRow(item, vm, onClick = { onOpen(item.manga.id) })
            }
            item(key = "similar") {
                SimilarSection(series, vm, Modifier.padding(top = 16.dp))
            }
        }
    }

    if (coverOpen) CoverPickerSheet(series, vm) { coverOpen = false }
    if (deleting) {
        ConfirmDialog(
            title = "Delete \"${series.title}\"?",
            text = "All ${series.items.size} files in this series are removed from the app. Your original files are untouched.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteSeries(series) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun Header(series: Series, onContinue: () -> Unit, onCover: () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Row {
            CoverImage(
                series.coverFile, series.title,
                Modifier.width(120.dp).aspectRatio(0.7f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onCover),
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(series.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val n = series.items.size
                Text("$n ${if (n == 1) series.unitName else series.unitName + "s"}", color = MaterialTheme.colorScheme.outline)
                Text(
                    listOfNotNull(
                        "${series.completed} read".takeIf { series.completed > 0 },
                        "${series.reading} reading".takeIf { series.reading > 0 },
                        "${series.unread} unread".takeIf { series.unread > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { series.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    (if (series.continueItem.manga.status == ReadStatus.READING) "Continue" else "Start") +
                        " · " + series.continueItem.displayLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = onCover) { Text("Cover") }
        }
    }
}

@Composable
private fun ChapterRow(item: SeriesItem, vm: LibraryViewModel, onClick: () -> Unit) {
    val m = item.manga
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(m.status.color().copy(alpha = if (m.status == ReadStatus.UNREAD) 0.35f else 1f)),
                contentAlignment = Alignment.Center,
            ) {
                if (m.status == ReadStatus.COMPLETED) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.displayLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.parsed.label.isNotBlank()) {
                    Text(m.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when (m.status) {
                        ReadStatus.READING -> "Page ${m.lastPage + 1} of ${m.pageCount}"
                        ReadStatus.COMPLETED -> "Finished · ${m.pageCount} pages"
                        ReadStatus.UNREAD -> "${m.pageCount} pages"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = m.status.color(),
                )
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                    DropdownMenuItem({ Text("Mark as read") }, { menu = false; vm.markRead(m) })
                    DropdownMenuItem({ Text("Mark as unread") }, { menu = false; vm.markUnread(m) })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; deleting = true })
                }
            }
        }
        if (m.status == ReadStatus.READING) {
            LinearProgressIndicator(
                progress = { m.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 10.dp),
            )
        }
    }

    if (renaming) RenameDialog(m.title, { vm.rename(m, it) }) { renaming = false }
    if (deleting) {
        ConfirmDialog(
            "Delete \"${item.displayLabel}\"?",
            "The PDF is removed from the app. Your original file is untouched.",
            "Delete",
            { vm.delete(m) },
            { deleting = false },
        )
    }
}
