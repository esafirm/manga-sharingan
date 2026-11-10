package com.esafirm.sharingan.data.scraper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A scanned chapter page: where it was found and the images that look like its pages. */
data class ScanResult(
    val pageUrl: String,
    val title: String?,
    val images: List<String>,
    /** True if the pages came from the browser engine (JavaScript ran), false for the plain HTML. */
    val rendered: Boolean = false,
)

enum class RenderMode {
    /** Plain HTML first; the browser engine only if that finds too few pages or is blocked. */
    AUTO,
    /** Always load with the browser engine. */
    ALWAYS,
}

/** A finished download: the PDF (in the scraper's work dir, caller moves/deletes it) and pages that failed. */
data class ScrapedPdf(val file: File, val pages: Int, val skipped: Int)

/**
 * Turns a chapter web page into a PDF: fetch the HTML, pick the page images
 * ([HtmlImageExtractor]), download them a few at a time, normalise each to a JPEG
 * (long webtoon strips are cut into screen-sized pages) and pack them with [ImagePdfWriter].
 *
 * Pages built by JavaScript are loaded in a [WebViewRenderer] first. Downloads reuse its user
 * agent and cookies, so a site that let the browser in (e.g. after a challenge) lets them in too.
 */
class WebScraper(private val workDir: File, private val renderer: WebViewRenderer) {

    suspend fun scan(url: String, filter: String? = null, mode: RenderMode = RenderMode.AUTO): ScanResult {
        val target = normalizeUrl(url)
        warmUp()
        var plain: ScanResult? = null
        var plainError: Throwable? = null
        if (mode == RenderMode.AUTO) {
            try {
                plain = withContext(Dispatchers.IO) {
                    val (finalUrl, html) = fetchHtml(target)
                    val page = HtmlImageExtractor.extract(html, finalUrl, filter)
                    ScanResult(finalUrl, page.title, page.images, rendered = false)
                }
                if (plain.images.size >= ENOUGH_PLAIN_PAGES) return plain
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                plainError = e // blocked (403/503 bot check) or similar: the browser engine may get in
            }
        }
        val rendered = try {
            val page = renderer.render(target)
            val found = withContext(Dispatchers.Default) { HtmlImageExtractor.extract(page.html, page.url, filter) }
            ScanResult(page.url, found.title, found.images, rendered = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            plain ?: throw (plainError ?: e)
        }
        return if (plain != null && plain.images.size > rendered.images.size) plain else rendered
    }

    /** Headers an image request needs to look like it came from the page (thumbnails use these too). */
    fun imageHeaders(url: String, referer: String): Map<String, String> = buildMap {
        put("User-Agent", renderer.userAgent)
        put("Referer", referer)
        cookieFor(url)?.let { put("Cookie", it) }
    }

    private fun cookieFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun download(scan: ScanResult, onProgress: (done: Int, total: Int) -> Unit): ScrapedPdf {
        warmUp()
        return withContext(Dispatchers.IO) {
            require(scan.images.isNotEmpty()) { "No images found on this page" }
            val dir = File(workDir, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val done = AtomicInteger(0)
                val total = scan.images.size
                onProgress(0, total)
                val gate = Semaphore(PARALLEL)
                val perImage: List<List<File>?> = coroutineScope {
                    scan.images.mapIndexed { i, url ->
                        async {
                            gate.withPermit {
                                val pages = runCatching { toJpegPages(fetchBytes(url, scan.pageUrl), dir, i) }
                                    .onFailure { if (it is CancellationException) throw it }
                                    .getOrNull()
                                onProgress(done.incrementAndGet(), total)
                                pages
                            }
                        }
                    }.awaitAll()
                }
                val pages = perImage.filterNotNull().flatten()
                if (pages.isEmpty()) throw IOException("Couldn't download any of the ${scan.images.size} images")
                val pdf = File(workDir, "${UUID.randomUUID()}.pdf")
                pdf.outputStream().use { ImagePdfWriter.write(pages, it) }
                ScrapedPdf(pdf, pages.size, perImage.count { it == null })
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /** The WebView user agent is looked up on the main thread once, before any background request needs it. */
    private suspend fun warmUp() = withContext(Dispatchers.Main) { renderer.userAgent }

    // ---- Network -------------------------------------------------------------------------

    private fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        require(u.isNotEmpty()) { "Enter a link" }
        return if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else "https://$u"
    }

    private suspend fun fetchHtml(url: String): Pair<String, String> = retrying {
        val conn = open(url, referer = null, accept = "text/html,application/xhtml+xml,*/*;q=0.8")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("The site answered HTTP $code")
            val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(conn.contentType.orEmpty())?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it.trim('"')) }.getOrNull() } ?: Charsets.UTF_8
            val bytes = conn.inputStream.use { it.readLimited(MAX_HTML) }
            conn.url.toString() to String(bytes, charset) // conn.url is the post-redirect address
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun fetchBytes(url: String, referer: String): ByteArray = retrying {
        val conn = open(url, referer, accept = "image/avif,image/webp,image/*,*/*;q=0.8")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            conn.inputStream.use { it.readLimited(MAX_IMAGE) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, referer: String?, accept: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", renderer.userAgent)
            cookieFor(url)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "en;q=0.9,*;q=0.5")
            // Most image hosts refuse hotlinks without the chapter page as referer.
            referer?.let { setRequestProperty("Referer", it) }
        }

    private suspend fun <T> retrying(block: () -> T): T {
        var last: IOException? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: IOException) {
                last = e
                delay(600L * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun java.io.InputStream.readLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Response too large")
        }
        return out.toByteArray()
    }

    // ---- Images --------------------------------------------------------------------------

    /**
     * One downloaded image as JPEG page file(s) named so they sort in reading order.
     * Plain grey/RGB JPEGs are kept byte-for-byte; anything else (PNG, WebP, AVIF, CMYK JPEG)
     * is decoded and re-encoded. Strips taller than [MAX_RATIO]× their width are cut into slices.
     */
    private fun toJpegPages(bytes: ByteArray, dir: File, index: Int): List<File> {
        fun out(part: Int) = File(dir, "${index.toString().padStart(5, '0')}_${part.toString().padStart(3, '0')}.jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) throw IOException("Not an image")
        if (w < MIN_SIDE && h < MIN_SIDE) throw IOException("Too small to be a page") // stray icon

        // Downscale very wide pages; keeps memory and file size sane (manga is legible well below this).
        var sample = 1
        while (w / (sample * 2) >= MAX_WIDTH) sample *= 2

        if (h <= w * MAX_RATIO) {
            val info = Jpeg.readInfo(bytes)
            if (info != null && sample == 1 && (info.components == 1 || info.components == 3)) {
                return listOf(out(0).apply { writeBytes(bytes) })
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("Couldn't decode image")
            return listOf(saveJpeg(bmp, out(0)))
        }

        // Long strip: decode slice by slice so a 800×30 000 strip never sits in memory at once.
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        try {
            val slice = w * SLICE_RATIO
            val count = (h + slice - 1) / slice
            return (0 until count).map { part ->
                val top = part * slice
                val rect = Rect(0, top, w, minOf(h, top + slice))
                val bmp = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IOException("Couldn't decode image")
                saveJpeg(bmp, out(part))
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun saveJpeg(src: Bitmap, file: File): File {
        // JPEG has no alpha: flatten transparent PNGs onto white instead of black.
        val bmp = if (src.hasAlpha()) {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
                src.recycle()
            }
        } else src
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bmp.recycle()
        return file
    }

    companion object {
        /** Fewer pages than this in the plain HTML → probably a JS reader, try the browser engine. */
        private const val ENOUGH_PLAIN_PAGES = 5
        private const val PARALLEL = 4
        private const val MAX_HTML = 8 * 1024 * 1024
        private const val MAX_IMAGE = 40 * 1024 * 1024
        private const val MAX_WIDTH = 2400
        private const val MIN_SIDE = 200
        private const val MAX_RATIO = 3
        private const val SLICE_RATIO = 2
        private const val JPEG_QUALITY = 90
    }
}
