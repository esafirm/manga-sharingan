package com.esafirm.sharingan.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esafirm.sharingan.SharinganApp
import com.esafirm.sharingan.data.FileNameParser
import com.esafirm.sharingan.data.source.ChapterListing
import com.esafirm.sharingan.data.source.LinkSource
import com.esafirm.sharingan.data.source.MangaSource
import com.esafirm.sharingan.data.source.SourceChapter
import com.esafirm.sharingan.data.source.SourceManga
import com.esafirm.sharingan.data.source.WebsiteSource
import com.esafirm.sharingan.data.source.chapterFileTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.UnknownHostException

/** Search results of one source. */
data class SourceResults(
    val source: MangaSource,
    val loading: Boolean = true,
    val items: List<SourceManga> = emptyList(),
    val error: String? = null,
)

/** The chapter list screen's data. */
data class ChapterState(
    val manga: SourceManga,
    val loading: Boolean = true,
    val listing: ChapterListing? = null,
    val error: String? = null,
)

class BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val sharingan = app as SharinganApp
    private val repo = sharingan.repo
    val sourceRepo = sharingan.sources
    val renderer = sharingan.renderer

    /** Sources the user sees (the hidden "link" source is left out). */
    val sources: StateFlow<List<MangaSource>> = sourceRepo.sources
        .map { list -> list.filterNot { it.id == LinkSource.ID } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val language: StateFlow<String> = sourceRepo.language

    val query = MutableStateFlow("")
    val link = MutableStateFlow("")
    private val _results = MutableStateFlow<List<SourceResults>>(emptyList())
    val results: StateFlow<List<SourceResults>> = _results
    private var searchJob: Job? = null

    private val _chapters = MutableStateFlow<ChapterState?>(null)
    val chapters: StateFlow<ChapterState?> = _chapters
    private var chapterJob: Job? = null

    /** Name the chapters are saved under ("<series> - Ch. N"); editable before downloading. */
    val seriesTitle = MutableStateFlow("")
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val newestFirst = MutableStateFlow(true)
    private var anchor: String? = null

    /** Ids of listed chapters that are already on the shelf (same series name + chapter number). */
    val inLibrary: StateFlow<Set<String>> =
        combine(repo.observeAll(), _chapters, seriesTitle) { library, state, series ->
            val chapters = state?.listing?.chapters.orEmpty()
            if (chapters.isEmpty() || series.isBlank()) return@combine emptySet<String>()
            val owned = library.map { FileNameParser.parse(it.title) }.map { it.key to it.chapter }.toSet()
            chapters.filter { c ->
                val p = FileNameParser.parse(chapterFileTitle(series, c))
                p.chapter != null && (p.key to p.chapter) in owned
            }.map { it.id }.toSet()
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun search() {
        val q = query.value.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            _results.value = emptyList()
            return
        }
        val list = sources.value
        _results.value = list.map { SourceResults(it) }
        searchJob = viewModelScope.launch {
            list.forEach { source ->
                launch {
                    val r = try {
                        SourceResults(source, loading = false, items = source.search(q))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        SourceResults(source, loading = false, error = describe(e))
                    }
                    _results.update { all -> all.map { if (it.source.id == source.id) r else it } }
                }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        query.value = ""
        _results.value = emptyList()
    }

    fun openSeries(manga: SourceManga) {
        seriesTitle.value = manga.title
        selection.value = emptySet()
        anchor = null
        loadChapters(manga)
    }

    /** Lists the chapters of any pasted page. */
    fun openLink(): Boolean {
        val raw = link.value.trim().ifEmpty { return false }
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        openSeries(sourceRepo.link.manga(url))
        return true
    }

    /** A website source opens straight onto its configured page. */
    fun openWebsite(source: WebsiteSource) = openSeries(source.home)

    fun reloadChapters() = _chapters.value?.manga?.let(::loadChapters)

    private fun loadChapters(manga: SourceManga) {
        chapterJob?.cancel()
        _chapters.value = ChapterState(manga)
        chapterJob = viewModelScope.launch {
            try {
                val source = sourceRepo.get(manga.sourceId) ?: error("That source was removed")
                val listing = source.chapters(manga)
                _chapters.value = ChapterState(manga, loading = false, listing = listing)
                // A pasted link starts out named after the host; the page usually knows the real title.
                val generic = manga.title == manga.subtitle || manga.sourceId == LinkSource.ID
                if (generic && seriesTitle.value == manga.title) listing.title?.let { seriesTitle.value = it }
                // A website added without a name takes the series title its page shows (so search finds it).
                val site = sourceRepo.get(manga.sourceId)
                if (site is WebsiteSource && site.name == WebsiteSource.host(site.config.url)) {
                    listing.title?.let {
                        sourceRepo.rename(site.id, it)
                        seriesTitle.value = it
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _chapters.value = ChapterState(manga, loading = false, error = describe(e))
            }
        }
    }

    /** Stops loading when the chapter screen is left (the state stays for the exit animation). */
    fun closeChapters() {
        if (_chapters.value?.loading == true) {
            chapterJob?.cancel()
            _chapters.value = null
        }
    }

    /** Tap: toggle one chapter. */
    fun toggle(c: SourceChapter) {
        selection.update { if (c.id in it) it - c.id else it + c.id }
        anchor = c.id
    }

    /** Long-press: select everything between the last tapped chapter and this one. */
    fun selectRange(c: SourceChapter, visible: List<SourceChapter>) {
        val from = anchor?.let { a -> visible.indexOfFirst { it.id == a } }?.takeIf { it >= 0 }
        val to = visible.indexOfFirst { it.id == c.id }
        if (from == null || to < 0) return toggle(c)
        val range = visible.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }
        selection.update { it + range }
        anchor = c.id
    }

    fun selectAll() {
        selection.value = _chapters.value?.listing?.chapters.orEmpty().map { it.id }.toSet()
    }

    fun selectNew() {
        val owned = inLibrary.value
        selection.value = _chapters.value?.listing?.chapters.orEmpty().map { it.id }.filterNot { it in owned }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    /** Hands the selected chapters to the download queue. */
    fun downloadSelected(downloads: ScraperViewModel) {
        val state = _chapters.value ?: return
        val source = sourceRepo.get(state.manga.sourceId) ?: return
        val picked = state.listing?.chapters.orEmpty().filter { it.id in selection.value }
        if (picked.isEmpty()) return
        val series = seriesTitle.value.trim().ifEmpty { state.listing?.title ?: state.manga.title }
        val added = downloads.enqueue(source, series, picked)
        selection.value = emptySet()
        toast(if (added == 0) "Already in the download queue" else "Downloading $added chapter${if (added == 1) "" else "s"}")
    }

    fun addWebsite(name: String, url: String, searchUrl: String?): WebsiteSource {
        val raw = url.trim()
        val full = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        return sourceRepo.addWebsite(name, full, searchUrl)
    }

    /** "Save as source" for a pasted link, named after the series. */
    fun saveLinkAsSource() {
        val state = _chapters.value ?: return
        if (state.manga.sourceId != LinkSource.ID) return
        val source = addWebsite(seriesTitle.value, state.manga.url, null)
        _chapters.value = state.copy(manga = state.manga.copy(sourceId = source.id))
        toast("Saved “${source.name}” to your sources")
    }

    fun removeSource(id: String) = sourceRepo.remove(id)

    fun setLanguage(code: String) {
        sourceRepo.setLanguage(code)
        if (query.value.isNotBlank() && _results.value.isNotEmpty()) search()
    }

    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "No connection, or the site doesn't exist"
        is java.net.SocketTimeoutException -> "The site took too long to answer"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
}
