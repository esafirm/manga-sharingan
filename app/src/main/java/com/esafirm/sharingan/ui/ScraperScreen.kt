package com.esafirm.sharingan.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.esafirm.sharingan.data.scraper.WebScraper

/** Paste chapter links → see which pages were found → download them as PDFs into the library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScraperScreen(vm: ScraperViewModel, onBack: () -> Unit, onRead: (Long) -> Unit) {
    val input by vm.input.collectAsState()
    val filter by vm.filter.collectAsState()
    val jobs by vm.jobs.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(filter.isNotEmpty()) }
    val ready = jobs.count { it.status == ScrapeStatus.READY }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download from web") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (jobs.any { it.status == ScrapeStatus.DONE }) TextButton(vm::clearFinished) { Text("Clear done") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "input") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { vm.input.value = it },
                        label = { Text("Chapter links") },
                        placeholder = { Text("https://…\nOne chapter per line") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { advanced = !advanced }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Image filter (optional)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                    }
                    AnimatedVisibility(advanced) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { vm.filter.value = it },
                            singleLine = true,
                            label = { Text("Image URL contains") },
                            supportingText = {
                                Text("Pages are detected automatically. If the wrong images are picked, type part of the page images' address (e.g. “/chapters/”) and scan again.")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::scanInput, enabled = input.isNotBlank()) { Text("Find pages") }
                        if (ready > 1) FilledTonalButton(onClick = vm::downloadAll) { Text("Download all ($ready)") }
                    }
                }
            }

            if (jobs.isEmpty()) {
                item(key = "hint") {
                    Text(
                        "Paste the link of a chapter's reading page (or share it here from your browser). " +
                            "The page images are downloaded, packed into a PDF and added to your shelf. " +
                            "Name chapters like “Title - Ch. 12” so they group into one series.\n\n" +
                            "Only download what you have the right to. Sites that load pages purely with JavaScript can't be read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            items(jobs.asReversed(), key = { it.id }) { job ->
                JobCard(
                    job = job,
                    onTitle = { vm.setTitle(job.id, it) },
                    onDownload = { vm.download(job.id) },
                    onRetry = { if (job.scan == null || job.status == ScrapeStatus.EMPTY) vm.rescan(job.id) else vm.download(job.id) },
                    onRemove = { vm.remove(job.id) },
                    onRead = { job.mangaId?.let(onRead) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun JobCard(
    job: ScrapeJob,
    onTitle: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onRemove, Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, if (job.busy) "Cancel" else "Remove", Modifier.size(18.dp))
                }
            }

            if (job.status != ScrapeStatus.SCANNING) {
                OutlinedTextField(
                    value = job.title,
                    onValueChange = onTitle,
                    singleLine = true,
                    enabled = job.status == ScrapeStatus.READY || job.status == ScrapeStatus.FAILED || job.status == ScrapeStatus.EMPTY,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            job.scan?.takeIf { it.images.isNotEmpty() }?.let { scan ->
                Thumbnails(scan.images, scan.pageUrl)
            }

            when (job.status) {
                ScrapeStatus.SCANNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Looking for pages…")
                }
                ScrapeStatus.READY -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${job.total} pages found", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Button(onDownload) { Text("Download") }
                }
                ScrapeStatus.EMPTY -> StatusLine(
                    "No page images found. Try the image filter, or the site may load pages with JavaScript.",
                    error = true,
                    action = "Scan again",
                    onAction = onRetry,
                )
                ScrapeStatus.QUEUED -> Text("Waiting for other downloads…", color = MaterialTheme.colorScheme.outline)
                ScrapeStatus.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (job.done < job.total) "Downloading ${job.done} / ${job.total}" else "Building PDF…")
                    if (job.total > 0 && job.done < job.total) {
                        LinearProgressIndicator(progress = { job.done.toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                ScrapeStatus.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append("Added to your shelf · ${job.total} pages")
                            if (job.skipped > 0) append(" · ${job.skipped} skipped")
                        },
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    FilledTonalButton(onRead) { Text("Read") }
                }
                ScrapeStatus.FAILED -> StatusLine(job.error ?: "Failed", error = true, action = "Retry", onAction = onRetry)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, error: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onAction) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(action)
        }
    }
}

/** First few detected pages, so a wrong guess (ads, thumbnails) is obvious before downloading. */
@Composable
private fun Thumbnails(images: List<String>, referer: String) {
    val context = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(images.take(8)) { url ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .addHeader("Referer", referer)
                    .addHeader("User-Agent", WebScraper.USER_AGENT)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 56.dp, height = 80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        if (images.size > 8) {
            item {
                Box(
                    Modifier.size(width = 56.dp, height = 80.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Text("+${images.size - 8}", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
