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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.esafirm.sharingan.SharinganApp
import com.esafirm.sharingan.data.Manga
import com.esafirm.sharingan.data.MangaRepository
import com.esafirm.sharingan.data.SeriesItem
import com.esafirm.sharingan.pdf.PdfDoc
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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

    // Fast-scroll hold time / on-off come from Settings (read once per reading session).
    val fastScrollHoldMs = remember {
        val settings = (context.applicationContext as SharinganApp).settings
        if (settings.fastScrollEnabled) settings.fastScrollHoldMs.toLong() else null
    }

    // Long-press-and-drag "fast scroll" for the continuous reader.
    val scroller = remember(listState, count, doc) {
        FastScroller(listState, count) { i -> listState.layoutInfo.viewportSize.width * (doc?.ratio(i) ?: 1.4f) }
    }

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
        snapshotFlow { page }.collectLatest { p ->
            docNow?.prefetch(this, p)
            delay(300) // don't hit the database for every page while fast-scrolling
            repo.saveProgress(m, p)
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
            ScrollPages(doc, count, listState, scroller, fastScrollHoldMs, onTap = { showBars = !showBars }) {
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

        FastScrollIndicator(visible = scroller.active, page = page, count = count)

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
    scroller: FastScroller,
    fastScrollHoldMs: Long?,
    onTap: () -> Unit,
    footer: @Composable () -> Unit,
) {
    val zoom = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val fastScroll = remember(scroller, haptic, fastScrollHoldMs) {
        if (fastScrollHoldMs == null) null else FastScrollHandler(
            holdMillis = fastScrollHoldMs,
            onStart = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scroller.start()
            },
            onDrag = { dy -> scroller.drag(dy) },
            onEnd = { scroller.end() },
        )
    }

    Box(
        Modifier.fillMaxSize().zoomable(
            zoom = zoom,
            scope = scope,
            onTap = onTap,
            // Zoomed in: one-finger vertical drags scroll the list; horizontal drags pan the zoom.
            onVerticalPan = { dy -> state.dispatchRawDelta(dy) },
            fastScroll = fastScroll,
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
            items(count, key = { it }) { index -> ScrollPage(doc, index, deferRender = scroller.active) }
            item(key = "footer") { footer() }
        }
    }
}

@Composable
private fun ScrollPage(doc: PdfDoc?, index: Int, deferRender: Boolean) {
    // While fast-scrolling, only show what is already cached; render for real once the finger lifts.
    val bitmap by produceState<Bitmap?>(doc?.cached(index), doc, index, deferRender) {
        value = doc?.cached(index) ?: if (deferRender) null else doc?.page(index)
    }
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
 * Tap, double-tap-to-zoom, pinch/pan and long-press fast-scroll. Gestures are only claimed when they
 * are clearly meant for us (pinching, a long press, or movement past touch slop while zoomed), so plain
 * taps and 1x swipes (list scroll / pager) keep working.
 *
 * @param onVerticalPan if set, a one-finger vertical drag while zoomed is forwarded here (as a scroll
 * delta) instead of moving the zoomed image.
 * @param fastScroll if set, holding a finger still (long press) hands the following vertical drag to it.
 */
@Composable
private fun Modifier.zoomable(
    zoom: ZoomState,
    scope: CoroutineScope,
    onTap: () -> Unit,
    onVerticalPan: ((Float) -> Unit)? = null,
    fastScroll: FastScrollHandler? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val verticalPan by rememberUpdatedState(onVerticalPan)
    val fast by rememberUpdatedState(fastScroll)
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

                // Pinch / pan while zoomed. Claims the event only when it is ours.
                fun handle(event: PointerEvent) {
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
                }

                val handler = fast
                if (handler != null) {
                    // Wait for a long press: one finger that stays put until the timeout.
                    val interrupted: PointerEvent? = withTimeoutOrNull(handler.holdMillis) {
                        var result: PointerEvent? = null
                        var moved = 0f
                        while (result == null) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            moved += ev.calculatePan().getDistance()
                            if (ev.changes.size > 1 || ev.changes.none { it.pressed } || moved > viewConfiguration.touchSlop) {
                                result = ev
                            }
                        }
                        result
                    }
                    if (interrupted == null) {
                        // Long press -> fast-scroll mode. We own every event until the finger lifts.
                        handler.onStart()
                        try {
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                handler.onDrag(ev.calculatePan().y)
                                ev.changes.forEach { it.consume() }
                            } while (ev.changes.any { it.pressed })
                        } finally {
                            handler.onEnd()
                        }
                        return@awaitEachGesture
                    }
                    if (interrupted.changes.none { it.pressed }) return@awaitEachGesture // it was a tap
                    handle(interrupted) // moved / second finger before the timeout: normal gesture
                }

                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    handle(event)
                } while (event.changes.any { it.pressed })
            }
        }
}

private class FastScrollHandler(
    val holdMillis: Long,
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * Fast-scroll for the continuous reader. Dragging moves a *position in pages*, scaled so a drag across
 * the screen covers about the whole book, and jumps the list straight there (no scrolling through the
 * pages in between). Drag up = forward, like a normal scroll.
 */
@Stable
private class FastScroller(
    private val state: LazyListState,
    private val count: Int,
    private val pageHeightPx: (Int) -> Float,
) {
    var active by mutableStateOf(false)
        private set

    private var pos = 0f

    fun start() {
        val i = state.firstVisibleItemIndex.coerceIn(0, count - 1)
        val h = pageHeightPx(i).coerceAtLeast(1f)
        pos = (i + state.firstVisibleItemScrollOffset / h).coerceIn(0f, (count - 1).toFloat())
        active = true
    }

    fun drag(dy: Float) {
        val viewport = state.layoutInfo.viewportSize.height.coerceAtLeast(1)
        val pagesPerPx = maxOf(count.toFloat(), 8f) / viewport
        pos = (pos - dy * pagesPerPx).coerceIn(0f, (count - 1).toFloat())
        val i = pos.toInt()
        state.requestScrollToItem(i, ((pos - i) * pageHeightPx(i)).toInt())
    }

    fun end() {
        active = false
    }
}

/** Big page number in the middle plus a position track on the right edge, shown while fast-scrolling. */
@Composable
private fun FastScrollIndicator(visible: Boolean, page: Int, count: Int) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.9f),
        exit = fadeOut(tween(250)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${page + 1}", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("of $count", color = Color.White.copy(alpha = 0.7f))
            }

            val fraction = if (count > 1) page / (count - 1f) else 0f
            Column(
                Modifier.align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .fillMaxHeight(0.7f)
                    .width(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
            ) {
                Spacer(Modifier.weight(fraction.coerceIn(0.0001f, 0.9999f)))
                Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.weight((1f - fraction).coerceIn(0.0001f, 0.9999f)))
            }
        }
    }
}
