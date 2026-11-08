package com.esafirm.sharingan.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.esafirm.sharingan.data.Manga
import com.esafirm.sharingan.data.MangaRepository
import com.esafirm.sharingan.pdf.PdfDoc
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(repo: MangaRepository, id: Long, onClose: () -> Unit) {
    val manga by produceState<Manga?>(null, id) { value = repo.get(id) }
    val m = manga
    if (m == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    ReaderContent(repo, m, onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(repo: MangaRepository, m: Manga, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    var rtl by remember { mutableStateOf(prefs.getBoolean("rtl", true)) }
    var showBars by remember { mutableStateOf(false) }

    // 1.5x screen width: crisp enough for zoom without making renders slow.
    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.5f).toInt().coerceAtMost(2200) }
    val doc by produceState<PdfDoc?>(null, m.id) {
        value = PdfDoc.open(repo.pdfFile(m), widthPx)
        awaitDispose { val d = value; if (d != null) scope.launch { d.close() } }
    }

    // Pager is usable immediately (page count is in the DB) and starts on the saved page.
    val pager = rememberPagerState(initialPage = m.lastPage.coerceIn(0, m.pageCount - 1)) { m.pageCount }

    LaunchedEffect(doc) { doc?.prefetch(this, pager.currentPage) }
    LaunchedEffect(doc, pager) {
        snapshotFlow { pager.settledPage }.collectLatest { page ->
            repo.saveProgress(m, page)
            doc?.prefetch(this, page)
        }
    }
    DisposableEffect(Unit) { onDispose { repo.saveProgress(m, pager.settledPage) } }

    // Immersive mode while reading.
    val view = LocalView.current
    DisposableEffect(showBars) {
        val window = (view.context as android.app.Activity).window
        val c = WindowCompat.getInsetsController(window, view)
        if (showBars) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            reverseLayout = rtl,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            PdfPage(doc, index, onTap = { showBars = !showBars })
        }

        if (showBars) {
            TopAppBar(
                title = { Text(m.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton({ rtl = !rtl; prefs.edit().putBoolean("rtl", rtl).apply() }) {
                        Text(if (rtl) "RTL" else "LTR")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f)).navigationBarsPadding().padding(16.dp),
            ) {
                Text("${pager.currentPage + 1} / ${m.pageCount}", color = Color.White)
                if (m.pageCount > 1) {
                    Slider(
                        value = pager.currentPage.toFloat(),
                        onValueChange = { scope.launch { pager.scrollToPage(it.toInt()) } },
                        valueRange = 0f..(m.pageCount - 1).toFloat(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc?, index: Int, onTap: () -> Unit) {
    val bitmap by produceState<Bitmap?>(doc?.cached(index), doc, index) {
        value = doc?.page(index)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f
                    },
                )
            }
            .pointerInput(Unit) {
                // Only claim gestures when pinching or already zoomed, so 1x swipes reach the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (scale > 1f || event.changes.size > 1) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            val pan = event.calculatePan()
                            offset = androidx.compose.ui.geometry.Offset(
                                (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                                (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                            )
                            if (scale == 1f) offset = androidx.compose.ui.geometry.Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
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
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}
