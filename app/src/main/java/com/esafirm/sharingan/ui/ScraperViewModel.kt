package com.esafirm.sharingan.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esafirm.sharingan.SharinganApp
import com.esafirm.sharingan.data.scraper.RenderMode
import com.esafirm.sharingan.data.scraper.ScanResult
import com.esafirm.sharingan.data.source.MangaSource
import com.esafirm.sharingan.data.source.SourceChapter
import com.esafirm.sharingan.data.source.chapterFileTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL
import java.net.UnknownHostException

enum class ScrapeStatus { SCANNING, READY, EMPTY, QUEUED, DOWNLOADING, DONE, FAILED }

/** One chapter link moving through scan → review → download → library. */
data class ScrapeJob(
    val id: Long,
    val url: String,
    val status: ScrapeStatus,
    val title: String = "",
    val scan: ScanResult? = null,
    val done: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    val mangaId: Long? = null,
    /** Set for chapters picked from a source: pages are resolved through it right before downloading. */
    val sourceId: String? = null,
    val chapter: SourceChapter? = null,
) {
    val busy get() = status == ScrapeStatus.SCANNING || status == ScrapeStatus.QUEUED || status == ScrapeStatus.DOWNLOADING
}

class ScraperViewModel(app: Application) : AndroidViewModel(app) {
    private val sharingan = app as SharinganApp
    private val repo = sharingan.repo
    /** Browser engine for JavaScript-built readers; its WebView is shown on screen while it works. */
    val renderer = sharingan.renderer
    private val scraper = sharingan.scraper
    private val sources = sharingan.sources

    /** Links typed/pasted/shared in, one per line. Lives here so it survives leaving the screen. */
    val input = MutableStateFlow("")
    /** Optional "image URL contains…" override when the automatic page detection guesses wrong. */
    val filter = MutableStateFlow("")
    /** Skip the plain-HTML attempt and always load pages in the browser engine. */
    val alwaysRender = MutableStateFlow(false)

    private val _jobs = MutableStateFlow<List<ScrapeJob>>(emptyList())
    val jobs: StateFlow<List<ScrapeJob>> = _jobs.asStateFlow()

    private var nextId = 1L
    private val running = mutableMapOf<Long, Job>()
    /** One chapter downloads at a time (each already fetches several images in parallel): polite to the site. */
    private val downloadLock = Mutex()

    /** Prefills the link box, e.g. from a URL shared by the browser. */
    fun prefill(text: String) {
        val links = extractLinks(text)
        if (links.isEmpty()) return
        input.value = (input.value.lines().filter { it.isNotBlank() } + links).distinct().joinToString("\n")
    }

    fun scanInput() {
        val links = extractLinks(input.value).ifEmpty { input.value.lines().map { it.trim() }.filter { it.isNotEmpty() } }
        if (links.isEmpty()) return
        input.value = ""
        val known = _jobs.value.map { it.url }.toSet()
        links.filterNot { it in known }.forEach { url ->
            val job = ScrapeJob(nextId++, url, ScrapeStatus.SCANNING)
            _jobs.update { it + job }
            scan(job.id)
        }
    }

    fun rescan(id: Long) {
        edit(id) { it.copy(status = ScrapeStatus.SCANNING, error = null, scan = null, done = 0, total = 0) }
        scan(id)
    }

    private fun scan(id: Long) {
        val url = find(id)?.url ?: return
        val f = filter.value
        val mode = if (alwaysRender.value) RenderMode.ALWAYS else RenderMode.AUTO
        running[id] = viewModelScope.launch {
            try {
                val result = scraper.scan(url, f, mode)
                edit(id) {
                    it.copy(
                        status = if (result.images.isEmpty()) ScrapeStatus.EMPTY else ScrapeStatus.READY,
                        scan = result,
                        title = it.title.ifBlank { result.title ?: hostOf(url) },
                        total = result.images.size,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                edit(id) { it.copy(status = ScrapeStatus.FAILED, error = describe(e)) }
            }
        }
    }

    fun setTitle(id: Long, title: String) = edit(id) { it.copy(title = title) }

    /**
     * Queues chapters picked from a source; each is downloaded as "[series] - Ch. N" so they group
     * into one series on the shelf. Chapters already in the queue are skipped. Returns how many were added.
     */
    fun enqueue(source: MangaSource, series: String, chapters: List<SourceChapter>): Int {
        val known = _jobs.value.filter { it.status != ScrapeStatus.FAILED }.map { it.url }.toSet()
        val fresh = chapters.filterNot { it.url in known }
        fresh.forEach { c ->
            val job = ScrapeJob(
                id = nextId++,
                url = c.url,
                status = ScrapeStatus.READY,
                title = chapterFileTitle(series, c),
                sourceId = source.id,
                chapter = c,
            )
            _jobs.update { it + job }
            download(job.id)
        }
        return fresh.size
    }

    /** What the retry button does: source chapters and scanned pages download again, failed scans rescan. */
    fun retry(id: Long) {
        val job = find(id) ?: return
        if (job.chapter != null || (job.scan != null && job.status != ScrapeStatus.EMPTY)) download(id) else rescan(id)
    }

    fun downloadAll() = _jobs.value.filter { it.status == ScrapeStatus.READY }.forEach { download(it.id) }

    fun download(id: Long) {
        val job = find(id)
            ?.takeIf { it.status == ScrapeStatus.READY || it.status == ScrapeStatus.FAILED }
            ?.takeIf { it.scan != null || it.chapter != null } ?: return
        edit(id) { it.copy(status = ScrapeStatus.QUEUED, error = null, done = 0) }
        running[id] = viewModelScope.launch {
            downloadLock.withLock {
                edit(id) { it.copy(status = ScrapeStatus.DOWNLOADING) }
                try {
                    // Source chapters resolve their pages now: some image links (MangaDex) expire within minutes.
                    val scan = if (job.chapter != null) {
                        val source = job.sourceId?.let(sources::get) ?: error("That source was removed")
                        source.pages(job.chapter).also { s -> edit(id) { it.copy(scan = s, total = s.images.size) } }
                    } else {
                        job.scan!!
                    }
                    if (scan.images.isEmpty()) error("No page images found on the chapter page")
                    val pdf = scraper.download(scan) { done, total -> edit(id) { it.copy(done = done, total = total) } }
                    try {
                        val title = find(id)?.title?.trim().orEmpty().ifEmpty { scan.title ?: hostOf(scan.pageUrl) }
                        val manga = repo.importFile(pdf.file, title).getOrThrow()
                        edit(id) { it.copy(status = ScrapeStatus.DONE, mangaId = manga.id, skipped = pdf.skipped, total = pdf.pages) }
                    } finally {
                        pdf.file.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    edit(id) { it.copy(status = ScrapeStatus.FAILED, error = describe(e)) }
                }
            }
        }
    }

    /** Cancels whatever the job is doing and forgets it. */
    fun remove(id: Long) {
        running.remove(id)?.cancel()
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() = _jobs.update { list -> list.filterNot { it.status == ScrapeStatus.DONE } }

    fun imageHeaders(url: String, referer: String) = scraper.imageHeaders(url, referer)

    private fun find(id: Long) = _jobs.value.firstOrNull { it.id == id }

    private fun edit(id: Long, change: (ScrapeJob) -> ScrapeJob) =
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "No connection, or the site doesn't exist"
        is java.net.SocketTimeoutException -> "The site took too long to answer"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun hostOf(url: String) = runCatching { URL(url).host.removePrefix("www.") }.getOrNull() ?: url

    companion object {
        private val LINK = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

        fun extractLinks(text: String): List<String> =
            LINK.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()
    }
}
