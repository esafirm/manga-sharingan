package com.esafirm.sharingan.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A PDF opened for reading. [PdfRenderer] allows a single open page at a time, so renders are
 * serialised by [mutex]; finished bitmaps live in an LRU cache and neighbours are prefetched,
 * which is what makes page turns feel instant.
 */
class PdfDoc(file: File, private val renderWidth: Int) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    private var closed = false

    val pageCount = renderer.pageCount

    /** Height/width of page 1; used to size not-yet-rendered pages so scrolling doesn't jump. */
    private val defaultRatio: Float =
        runCatching { renderer.openPage(0).use { it.height.toFloat() / it.width } }.getOrDefault(1.4f)
    private val ratios = ConcurrentHashMap<Int, Float>()

    /** Best known height/width for [index] (exact once rendered). */
    fun ratio(index: Int): Float = ratios[index] ?: defaultRatio

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    fun cached(index: Int): Bitmap? = cache[index]

    suspend fun page(index: Int): Bitmap? {
        cache[index]?.let { return it }
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureActive() // skip pages the user already flicked past
                cache[index] ?: if (closed) null else render(index)?.also { cache.put(index, it) }
            }
        }
    }

    private fun render(index: Int): Bitmap? = runCatching {
        renderer.openPage(index).use { page ->
            val h = (renderWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(renderWidth, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            ratios[index] = h.toFloat() / renderWidth
            bmp
        }
    }.getOrNull()

    private var prefetchJob: Job? = null

    /** Warm the cache around [center], reading direction first. Replaces any earlier prefetch. */
    fun prefetch(scope: CoroutineScope, center: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (i in intArrayOf(center, center + 1, center + 2, center + 3, center - 1)) {
                if (i in 0 until pageCount) page(i)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closed = true
            cache.evictAll()
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        /** Opens off the main thread. Null if the file is corrupt/unreadable. */
        suspend fun open(file: File, renderWidth: Int): PdfDoc? = withContext(Dispatchers.IO) {
            runCatching { PdfDoc(file, renderWidth) }.getOrNull()
        }
    }
}
