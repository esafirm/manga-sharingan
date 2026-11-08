package com.esafirm.sharingan.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esafirm.sharingan.data.Manga
import com.esafirm.sharingan.data.MangaRepository
import com.esafirm.sharingan.data.ReadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val coverCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(repo: MangaRepository, onOpen: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val all by repo.observeAll().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf<ReadStatus?>(null) }
    var importing by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<Manga?>(null) }
    var renaming by remember { mutableStateOf<Manga?>(null) }
    var deleting by remember { mutableStateOf<Manga?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            importing++
            scope.launch {
                repo.import(uri).onFailure {
                    snackbar.showSnackbar("Couldn't import a file: ${it.message ?: "invalid PDF"}")
                }
                importing--
            }
        }
    }

    val shown = remember(all, filter) { all.filter { filter == null || it.status == filter } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Icon(Icons.Default.Add, contentDescription = "Add PDF")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (importing > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(filter == null, { filter = null }, { Text("All (${all.size})") }) }
                items(ReadStatus.entries.toList()) { s ->
                    FilterChip(filter == s, { filter = s }, { Text("${s.label} (${all.count { it.status == s }})") })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(if (all.isEmpty()) "Tap + to add manga PDFs" else "Nothing here")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { m ->
                        Box {
                            MangaCard(repo, m, onClick = { onOpen(m.id) }, onLongClick = { menuFor = m })
                            DropdownMenu(menuFor?.id == m.id, { menuFor = null }) {
                                DropdownMenuItem({ Text("Rename") }, { menuFor = null; renaming = m })
                                DropdownMenuItem({ Text("Mark as unread") }, { menuFor = null; scope.launch { repo.markUnread(m) } })
                                DropdownMenuItem({ Text("Mark as completed") }, { menuFor = null; scope.launch { repo.markCompleted(m) } })
                                DropdownMenuItem({ Text("Delete") }, { menuFor = null; deleting = m })
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ scope.launch { repo.rename(m, text) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${m.title}\"?") },
            text = { Text("The PDF is removed from the app. Your original file is untouched.") },
            confirmButton = { TextButton({ scope.launch { repo.delete(m) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MangaCard(repo: MangaRepository, m: Manga, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cover by produceState<Bitmap?>(m.coverName?.let { coverCache[it] }, m.coverName) {
        val name = m.coverName ?: return@produceState
        value = coverCache[name] ?: withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(File(repo.coverDir, name).path)?.also { coverCache.put(name, it) }
        }
    }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            cover?.let {
                Image(it.asImageBitmap(), m.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            AssistChip(
                onClick = onClick,
                label = { Text(m.status.label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text("${m.lastPage + 1} / ${m.pageCount}", style = MaterialTheme.typography.labelSmall)
    }
}
