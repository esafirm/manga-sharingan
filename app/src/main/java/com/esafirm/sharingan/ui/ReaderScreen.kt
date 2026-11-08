package com.esafirm.sharingan.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.esafirm.sharingan.data.Manga
import com.esafirm.sharingan.data.MangaRepository
import com.esafirm.sharingan.data.SeriesItem
import com.esafirm.sharingan.pdf.PdfDoc
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

enum class ReadMode(val label: String) {
    SCROLL("Scroll"),
    PAGER_RTL("Pages ←"),
    PAGER_LTR("Pages →"),
}

@Composable
fun ReaderScreen(
    repo: MangaRepository,
    id: Long,
    next: SeriesItem?,
    onClose: () -> Unit,
    onOpenNext: (Long) -> Unit,
) {
    val manga by produceState<Manga?>(null, id) { value = repo.get(id) }
    val m = manga
    if (m == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    ReaderContent(repo, m, next, onClose, onOpenNext)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    repo: MangaRepository,
    m: Manga,
    next: SeriesItem?,
    onClose: () -> Unit,
    onOpenNext: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(runCatching { ReadMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(ReadMode.SCROLL))
    }
    var showBars by remember { mutableStateOf(false) }
    val count = m.pageCount

    // 1.25x screen width: crisp enough for zoom without making renders slow or the cache huge.
    val widthPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.25f).toInt().coerceAtMost(1800)
    }
    val doc by produceState<PdfDoc?>(null, m.id) {
        value = PdfDoc.open(repo.pdfFile(m), widthPx)
        awaitDispose { val d = value; if (d != null) scope.launch { d.close() } }
    }

    // Both modes are usable immediately (page count is in the DB) and start on the saved page.
    val startPage = remember { m.lastPage.coerceIn(0, count - 1) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
    val pager = rememberPagerState(initialPage = startPage) { count }
    var page by remember { mutableIntStateOf(startPage) }
    val pageNow by rememberUpdatedState(page)
    val docNow by rememberUpdatedState(doc)

    // Keep both modes on the same page, and track it from whichever one is active.
    LaunchedEffect(mode) {
        if (mode == ReadMode.SCROLL) {
            listState.scrollToItem(page)
            snapshotFlow { currentScrollPage(listState, count) }.collect { page = it }
        } else {
            pager.scrollToPage(page)
            snapshotFlow { pager.settledPage }.collect { page = it }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { page }.collect { p ->
            repo.saveProgress(m, p)
            docNow?.prefetch(this, p)
        }
    }
    LaunchedEffect(doc) { doc?.prefetch(this, page) }
    DisposableEffect(Unit) { onDispose { repo.saveProgress(m, pageNow) } }

    // Immersive while reading; screen stays awake.
    val view = LocalView.current
    DisposableEffect(showBars) {
        val c = WindowCompat.getInsetsController((view.context as Activity).window, view)
        if (showBars) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            WindowCompat.getInsetsController((view.context as Activity).window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (mode == ReadMode.SCROLL) {
            ScrollPages(doc, count, listState, onTap = { showBars = !showBars }) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp).navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("End of ${m.title}", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (next != null) {
                        Button(onClick = { onOpenNext(next.manga.id) }) { Text("Next: ${next.displayLabel}", maxLines = 1) }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(onClick = onClose) { Text("Back") }
                }
            }
        } else {
            HorizontalPager(
                state = pager,
                reverseLayout = mode == ReadMode.PAGER_RTL,
                beyondViewportPageCount = 1,
                key = { it },
                modifier = Modifier.fillMaxSize(),
            ) { index -> PdfPage(doc, index, onTap = { showBars = !showBars }) }
        }

        if (!showBars) {
            LinearProgressIndicator(
                progress = { (page + 1f) / count },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                trackColor = Color.Transparent,
            )
        }

        AnimatedVisibility(
            showBars,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            TopAppBar(
                title = { Text(m.title, maxLines = 1) },
                navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton({
                        mode = ReadMode.entries[(mode.ordinal + 1) % ReadMode.entries.size]
                        prefs.edit().putString("mode", mode.name).apply()
                    }) { Text(mode.label) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.85f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        AnimatedVisibility(
            showBars,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.85f)).navigationBarsPadding().padding(16.dp),
            ) {
                Text("${page + 1} / $count", color = Color.White)
                if (count > 1) {
                    Slider(
                        value = page.toFloat(),
                        onValueChange = { v ->
                            scope.launch {
                                if (mode == ReadMode.SCROLL) listState.scrollToItem(v.toInt()) else pager.scrollToPage(v.toInt())
                            }
                        },
                        valueRange = 0f..(count - 1).toFloat(),
                        modifier = Modifier.graphicsLayer { scaleX = if (mode == ReadMode.PAGER_RTL) -1f else 1f },
                    )
                }
            }
        }
    }
}

/** The page at the top of the scroll view; the last page once the footer is on screen. */
private fun currentScrollPage(state: LazyListState, count: Int): Int =
    if (state.layoutInfo.visibleItemsInfo.any { it.index >= count }) count - 1
    else state.firstVisibleItemIndex.coerceIn(0, count - 1)

/** Continuous vertical reading with pinch / double-tap zoom. */
@Composable
private fun ScrollPages(
    doc: PdfDoc?,
    count: Int,
    state: LazyListState,
    onTap: () -> Unit,
    footer: @Composable () -> Unit,
) {
    val zoom = remember { ZoomState() }
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize().zoomable(
            zoom = zoom,
            scope = scope,
            onTap = onTap,
            // Zoomed in: one-finger vertical drags scroll the list; horizontal drags pan the zoom.
            onVerticalPan = { dy -> state.dispatchRawDelta(dy) },
        ),
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        ) {
            items(count, key = { it }) { index -> ScrollPage(doc, index) }
            item(key = "footer") { footer() }
        }
    }
}

@Composable
private fun ScrollPage(doc: PdfDoc?, index: Int) {
    val bitmap by produceState<Bitmap?>(doc?.cached(index), doc, index) { value = doc?.page(index) }
    val ratio = bitmap?.let { it.height.toFloat() / it.width } ?: doc?.ratio(index) ?: 1.4f
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(Color(0xFF101010)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.35f), strokeWidth = 2.dp)
        }
    }
}

/** One zoomable page for the paged modes. */
@Composable
private fun PdfPage(doc: PdfDoc?, index: Int, onTap: () -> Unit) {
    val bitmap by produceState<Bitmap?>(doc?.cached(index), doc, index) { value = doc?.page(index) }
    val zoom = remember(index) { ZoomState() }
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize().zoomable(zoom = zoom, scope = scope, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
            )
        }
    }
}

/**
 * Pinch / double-tap zoom. The layer scales around its centre, so every change is expressed as a
 * translation that keeps the touched point under the finger.
 */
@Stable
private class ZoomState(
    private val maxScale: Float = 4f,
    private val doubleTapScale: Float = 2.5f,
) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    private var job: Job? = null

    val isZoomed: Boolean get() = scale > 1.001f

    fun cancelAnimation() {
        job?.cancel()
    }

    private fun clamp(t: Offset, s: Float, size: Size): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
    }

    /** Direct (finger-driven) change: pinch around [centroid] plus [pan]. */
    fun transform(centroid: Offset, pan: Offset, zoomChange: Float, size: Size, lockY: Boolean) {
        val s0 = scale
        val s1 = (s0 * zoomChange).coerceIn(1f, maxScale)
        val center = Offset(size.width / 2f, size.height / 2f)
        val focus = centroid - center
        val movement = if (lockY) Offset(pan.x, 0f) else pan
        val t = focus - (focus - offset) * (s1 / s0) + movement
        scale = s1
        offset = if (s1 <= 1f) Offset.Zero else clamp(t, s1, size)
    }

    /** Double-tap: animate to [doubleTapScale] centred on [tap], or back out if already zoomed. */
    fun toggle(tap: Offset, size: Size, scope: CoroutineScope) {
        job?.cancel()
        val s0 = scale
        val t0 = offset
        val s1: Float
        val t1: Offset
        if (s0 > 1.05f) {
            s1 = 1f
            t1 = Offset.Zero
        } else {
            s1 = doubleTapScale
            val focus = tap - Offset(size.width / 2f, size.height / 2f)
            t1 = clamp(focus - (focus - t0) * (s1 / s0), s1, size)
        }
        job = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)) { f, _ ->
                scale = s0 + (s1 - s0) * f
                offset = Offset(t0.x + (t1.x - t0.x) * f, t0.y + (t1.y - t0.y) * f)
            }
        }
    }
}

/**
 * Tap, double-tap-to-zoom and pinch/pan. Gestures are only claimed when pinching, or once a finger
 * has moved past touch slop while zoomed in, so plain taps and 1x swipes (list scroll / pager) keep working.
 *
 * @param onVerticalPan if set, a one-finger vertical drag while zoomed is forwarded here (as a scroll
 * delta) instead of moving the zoomed image.
 */
@Composable
private fun Modifier.zoomable(
    zoom: ZoomState,
    scope: CoroutineScope,
    onTap: () -> Unit,
    onVerticalPan: ((Float) -> Unit)? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val verticalPan by rememberUpdatedState(onVerticalPan)
    return this
        .pointerInput(zoom) {
            detectTapGestures(
                onTap = { tap() },
                onDoubleTap = { pos -> zoom.toggle(pos, size.toSize(), scope) },
            )
        }
        .pointerInput(zoom) {
            awaitEachGesture {
                // Initial pass: runs before children (list / pager) see the event.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                zoom.cancelAnimation()
                var travelled = 0f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val multi = event.changes.size > 1
                    if (zoom.isZoomed || multi) {
                        val pan = event.calculatePan()
                        travelled += pan.getDistance()
                        if (multi || travelled > viewConfiguration.touchSlop) {
                            val forwardY = verticalPan != null && !multi
                            zoom.transform(
                                centroid = event.calculateCentroid(),
                                pan = pan,
                                zoomChange = event.calculateZoom(),
                                size = size.toSize(),
                                lockY = forwardY,
                            )
                            if (forwardY) verticalPan?.invoke(-pan.y / zoom.scale)
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
}
