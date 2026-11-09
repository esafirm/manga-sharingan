package com.esafirm.sharingan.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.esafirm.sharingan.data.Series
import com.esafirm.sharingan.data.Suggestion
import kotlinx.coroutines.CancellationException

private val CardWidth = 112.dp

@Composable
private fun SuggestionCard(s: Suggestion, onClick: () -> Unit) {
    Column(Modifier.width(CardWidth).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = s.imageUrl,
            contentDescription = s.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(4.dp))
        Text(s.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        s.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaceholderCard() {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(Modifier.width(CardWidth)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
        )
    }
}

/** A titled, horizontally scrolling row of suggestions with loading and error states. Tapping opens AniList. */
@Composable
fun SuggestionsSection(
    title: String,
    hint: String?,
    items: List<Suggestion>,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = LocalUriHandler.current
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
                items(5) { PlaceholderCard() }
            }
            failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load suggestions.", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { s ->
                    SuggestionCard(s) { runCatching { uri.openUri(s.url) } }
                }
            }
        }
    }
}

private sealed interface SimilarState {
    data object Loading : SimilarState
    data object Failed : SimilarState
    data class Done(val items: List<Suggestion>) : SimilarState
}

/** "Similar to <title>" for the series detail page. Hidden when suggestions are off or nothing was found. */
@Composable
fun SimilarSection(series: Series, vm: LibraryViewModel, modifier: Modifier = Modifier) {
    val enabled by vm.showSuggestions.collectAsState()
    if (!enabled) return

    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<SimilarState>(SimilarState.Loading, series.key, attempt) {
        value = SimilarState.Loading
        value = try {
            SimilarState.Done(vm.similar(series))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SimilarState.Failed
        }
    }

    val title = "Similar to ${series.title}"
    val hint = "Community picks from AniList"
    when (val s = state) {
        SimilarState.Loading -> SuggestionsSection(title, hint, emptyList(), loading = true, failed = false, onRetry = {}, modifier = modifier)
        SimilarState.Failed -> SuggestionsSection(title, hint, emptyList(), loading = false, failed = true, onRetry = { attempt++ }, modifier = modifier)
        is SimilarState.Done -> if (s.items.isNotEmpty()) {
            SuggestionsSection(title, hint, s.items, loading = false, failed = false, onRetry = {}, modifier = modifier)
        }
    }
}
