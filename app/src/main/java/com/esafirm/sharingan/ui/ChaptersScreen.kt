package com.esafirm.sharingan.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.esafirm.sharingan.data.source.LinkSource
import com.esafirm.sharingan.data.source.SourceChapter

/** A series' chapters from a source: pick some (tap, or long-press for a range) and download them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChaptersScreen(
    vm: BrowseViewModel,
    downloads: ScraperViewModel,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onDownloadAsChapter: (String) -> Unit,
) {
    val state by vm.chapters.collectAsState()
    val title by vm.seriesTitle.collectAsState()
    val selection by vm.selection.collectAsState()
    val owned by vm.inLibrary.collectAsState()
    val newestFirst by vm.newestFirst.collectAsState()
    val rendering by vm.renderer.active.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val s = state ?: return
    val chapters = s.listing?.chapters.orEmpty()
    val visible = if (newestFirst) chapters.asReversed() else chapters
    val isLink = s.manga.sourceId == LinkSource.ID

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { s.manga.title }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onOpenDownloads) { Text("Downloads") }
                    if (chapters.isNotEmpty()) Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Select all") }, { menu = false; vm.selectAll() })
                            DropdownMenuItem({ Text("Select not on shelf") }, { menu = false; vm.selectNew() })
                            DropdownMenuItem({ Text("Clear selection") }, { menu = false; vm.clearSelection() })
                            HorizontalDivider()
                            DropdownMenuItem(
                                { Text(if (newestFirst) "Oldest first" else "Newest first") },
                                { menu = false; vm.newestFirst.value = !newestFirst },
                            )
                            if (isLink) DropdownMenuItem({ Text("Save site as a source") }, { menu = false; vm.saveLinkAsSource() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selection.size} selected", Modifier.weight(1f))
                        TextButton(vm::clearSelection) { Text("Clear") }
                        Button({ vm.downloadSelected(downloads) }) { Text("Download") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        s.manga.coverUrl?.let { cover ->
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current).data(cover).crossfade(true).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 84.dp, height = 120.dp).clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { vm.seriesTitle.value = it },
                                singleLine = true,
                                label = { Text("Save as series") },
                                supportingText = { Text("Chapters are added as “${title.ifBlank { "Title" }.trim()} - Ch. N”") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                s.manga.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                when {
                    s.loading -> item(key = "loading") {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading chapters…")
                        }
                    }
                    s.error != null -> item(key = "error") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.error, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(vm::reloadChapters) { Text("Try again") }
                        }
                    }
                    chapters.isEmpty() -> item(key = "empty") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (s.manga.sourceId == com.esafirm.sharingan.data.source.MangaDexSource.ID) {
                                    "No chapters in the chosen language. Change it next to MangaDex in the source list."
                                } else {
                                    "No chapter list found on this page. If it is a single chapter, download it as one."
                                },
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (s.manga.sourceId != com.esafirm.sharingan.data.source.MangaDexSource.ID) {
                                OutlinedButton({ onDownloadAsChapter(s.manga.url) }) { Text("Download this page as a chapter") }
                            }
                        }
                    }
                    else -> {
                        item(key = "count") {
                            Text(
                                buildString {
                                    append("${chapters.size} chapters")
                                    if (owned.isNotEmpty()) append(" · ${owned.size} on your shelf")
                                    append(" · long-press to select a range")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(visible, key = { it.id }) { c ->
                            ChapterRow(
                                c = c,
                                selected = c.id in selection,
                                owned = c.id in owned,
                                onClick = { vm.toggle(c) },
                                onLongClick = { vm.selectRange(c, visible) },
                            )
                        }
                    }
                }
            }
            rendering?.let { url -> BrowserCard(vm.renderer, url, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(c: SourceChapter, selected: Boolean, owned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onClick() })
        Column(Modifier.weight(1f)) {
            Text(
                c.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (owned && !selected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
            val sub = listOfNotNull(c.group, if (owned) "On your shelf" else null).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
        }
        if (owned) Icon(Icons.Default.CheckCircle, "On your shelf", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
