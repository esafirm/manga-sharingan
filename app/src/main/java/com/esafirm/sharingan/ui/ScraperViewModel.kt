package com.esafirm.sharingan.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esafirm.sharingan.SharinganApp
import com.esafirm.sharingan.data.scraper.RenderMode
import com.esafirm.sharingan.data.scraper.ScanResult
import com.esafirm.sharingan.data.scraper.WebViewRenderer
import com.esafirm.sharingan.data.scraper.WebScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
) {
    val busy get() = status == ScrapeStatus.SCANNING || status == ScrapeStatus.QUEUED || status == ScrapeStatus.DOWNLOADING
}

class ScraperViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as SharinganApp).repo
    private val workDir = File(app.cacheDir, "scraper").apply { deleteRecursively(); mkdirs() } // drop leftovers
    /** Browser engine for JavaScript-built readers; its WebView is shown on screen while it works. */
    val renderer = WebViewRenderer(app)
    private val scraper = WebScraper(workDir, renderer)

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

    fun downloadAll() = _jobs.value.filter { it.status == ScrapeStatus.READY }.forEach { download(it.id) }

    fun download(id: Long) {
        val job = find(id)?.takeIf { it.status == ScrapeStatus.READY || it.status == ScrapeStatus.FAILED && it.scan != null } ?: return
        val scan = job.scan ?: return
        edit(id) { it.copy(status = ScrapeStatus.QUEUED, error = null, done = 0) }
        running[id] = viewModelScope.launch {
            downloadLock.withLock {
                edit(id) { it.copy(status = ScrapeStatus.DOWNLOADING) }
                try {
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

    override fun onCleared() {
        renderer.destroy()
    }

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
