package com.esafirm.sharingan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.esafirm.sharingan.data.CoverFinder
import com.esafirm.sharingan.data.CoverResult
import com.esafirm.sharingan.data.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private sealed interface CoverSearch {
    data object Loading : CoverSearch
    data object Failed : CoverSearch
    data class Done(val results: List<CoverResult>) : CoverSearch
}

/** "Find cover by title": search online, tap a result to use it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverPickerSheet(series: Series, vm: LibraryViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(series.title) }
    var state by remember { mutableStateOf<CoverSearch>(CoverSearch.Loading) }
    var job by remember { mutableStateOf<Job?>(null) }

    val search: () -> Unit = {
        job?.cancel()
        job = scope.launch {
            state = CoverSearch.Loading
            state = try {
                CoverSearch.Done(CoverFinder.search(query.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoverSearch.Failed
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            vm.setCoverFromUri(series, uri)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Find cover", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Title") },
                trailingIcon = { IconButton(onClick = search) { Icon(Icons.Default.Search, "Search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 460.dp)) {
                when (val s = state) {
                    CoverSearch.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    CoverSearch.Failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Couldn't reach the cover service.\nCheck your connection and try again.")
                        TextButton(onClick = search) { Text("Retry") }
                    }
                    is CoverSearch.Done ->
                        if (s.results.isEmpty()) {
                            Text("No results. Try a shorter or different title.", Modifier.align(Alignment.Center))
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(100.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(s.results, key = { it.imageUrl }) { r ->
                                    Column(Modifier.clickable {
                                        vm.setCoverFromUrl(series, r.imageUrl)
                                        onDismiss()
                                    }) {
                                        AsyncImage(
                                            model = r.imageUrl,
                                            contentDescription = r.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                        )
                                        Text(r.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        r.subtitle?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                    }
                                }
                            }
                        }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { gallery.launch("image/*") }) { Text("From gallery") }
                TextButton(onClick = { vm.resetCover(series); onDismiss() }) { Text("Use first page") }
            }
        }
    }
}
