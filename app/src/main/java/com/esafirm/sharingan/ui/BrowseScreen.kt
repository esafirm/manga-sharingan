package com.esafirm.sharingan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.esafirm.sharingan.data.source.MangaDexSource
import com.esafirm.sharingan.data.source.MangaSource
import com.esafirm.sharingan.data.source.SourceManga
import com.esafirm.sharingan.data.source.WebsiteSource

/** Find a title across sources, open a series link, and manage sources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    vm: BrowseViewModel,
    downloads: ScraperViewModel,
    onBack: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val query by vm.query.collectAsState()
    val link by vm.link.collectAsState()
    val results by vm.results.collectAsState()
    val sources by vm.sources.collectAsState()
    val language by vm.language.collectAsState()
    val jobs by downloads.jobs.collectAsState()
    val active = jobs.count { it.busy }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MangaSource?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find manga") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(onOpenDownloads) {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Text("Downloads") }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    singleLine = true,
                    placeholder = { Text("Search a title") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(vm::clearSearch) { Icon(Icons.Default.Close, "Clear") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            items(results, key = { "r-" + it.source.id }) { r ->
                Column(Modifier.animateItem()) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.source.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (r.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    when {
                        r.loading -> {}
                        r.error != null -> Hint(r.error, error = true)
                        r.items.isEmpty() -> Hint(
                            if (r.source is WebsiteSource && r.source.config.searchUrl == null) "No match (this site has no search address set)"
                            else "No results",
                        )
                        else -> LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(r.items, key = { it.id }) { m ->
                                ResultCard(m) { vm.openSeries(m); onOpenSeries() }
                            }
                        }
                    }
                }
            }

            item(key = "link") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (results.isNotEmpty()) HorizontalDivider(Modifier.padding(bottom = 4.dp))
                    SectionLabel("Open a link")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = link,
                            onValueChange = { vm.link.value = it },
                            singleLine = true,
                            placeholder = { Text("Series page, e.g. https://site.com/manga/…") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { if (vm.openLink()) onOpenSeries() }),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { if (vm.openLink()) onOpenSeries() }, enabled = link.isNotBlank()) { Text("Chapters") }
                    }
                    Hint("Lists every chapter on the page so you can pick which to download. A single chapter's page can go straight to Downloads.", padded = false)
                }
            }

            item(key = "sources-title") {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Sources", Modifier.weight(1f))
                    TextButton({ adding = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add website")
                    }
                }
            }
            items(sources, key = { "s-" + it.id }) { s ->
                SourceRow(
                    source = s,
                    language = language,
                    onLanguage = vm::setLanguage,
                    onOpen = if (s is WebsiteSource) ({ vm.openWebsite(s); onOpenSeries() }) else null,
                    onRemove = { removing = s },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "sources-hint") {
                Hint(
                    "Add any manga site: a series page, or the home page of a site about one series, is read for its chapter list. " +
                        "Give it a search address to find titles there too. Only download what you have the right to.",
                )
            }
        }
    }

    if (adding) {
        AddWebsiteDialog(
            onAdd = { name, url, search ->
                vm.openWebsite(vm.addWebsite(name, url, search)) // show its chapters right away
                onOpenSeries()
            },
            onDismiss = { adding = false },
        )
    }
    removing?.let { s ->
        ConfirmDialog(
            title = "Remove “${s.name}”?",
            text = "Its chapters already on your shelf stay.",
            confirmLabel = "Remove",
            onConfirm = { vm.removeSource(s.id) },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun ResultCard(m: SourceManga, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.size(width = 112.dp, height = 160.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (m.coverUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(m.coverUrl).crossfade(true).build(),
                    contentDescription = m.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(m.title.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(m.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        m.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SourceRow(
    source: MangaSource,
    language: String,
    onLanguage: (String) -> Unit,
    onOpen: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var langMenu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                source.description + if (source.builtIn) " · built in" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (source is MangaDexSource) {
            Box {
                OutlinedButton({ langMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(MangaDexSource.LANGUAGES.firstOrNull { it.first == language }?.second ?: language)
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(langMenu, { langMenu = false }) {
                    MangaDexSource.LANGUAGES.forEach { (code, label) ->
                        DropdownMenuItem({ Text(label, fontWeight = if (code == language) FontWeight.Bold else FontWeight.Normal) }, {
                            langMenu = false
                            onLanguage(code)
                        })
                    }
                }
            }
        }
        if (!source.builtIn) {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    onOpen?.let { DropdownMenuItem({ Text("Chapters") }, { menu = false; it() }) }
                    DropdownMenuItem({ Text("Remove") }, { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun AddWebsiteDialog(onAdd: (name: String, url: String, search: String?) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add website") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    url, { url = it }, singleLine = true,
                    label = { Text("Address") },
                    placeholder = { Text("https://site.com/") },
                    supportingText = { Text("A series page, or a site about one series") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name (series title)") })
                OutlinedTextField(
                    search, { search = it }, singleLine = true,
                    label = { Text("Search address (optional)") },
                    placeholder = { Text("https://site.com/?s={query}") },
                    supportingText = { Text("Use {query} where the search words go") },
                    isError = search.isNotBlank() && "{query}" !in search,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, url, search.ifBlank { null }); onDismiss() },
                enabled = url.isNotBlank() && (search.isBlank() || "{query}" in search),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun Hint(text: String, error: Boolean = false, padded: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        modifier = if (padded) Modifier.padding(horizontal = 16.dp) else Modifier,
    )
}
