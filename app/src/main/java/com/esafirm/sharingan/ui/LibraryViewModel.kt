package com.esafirm.sharingan.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esafirm.sharingan.SharinganApp
import com.esafirm.sharingan.data.Manga
import com.esafirm.sharingan.data.ReadStatus
import com.esafirm.sharingan.data.Series
import com.esafirm.sharingan.data.buildSeries
import com.esafirm.sharingan.data.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortMode(val label: String) {
    RECENT_READ("Recently read"),
    RECENT_ADDED("Recently added"),
    TITLE("Title A–Z"),
}

class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as SharinganApp).repo

    val sort = MutableStateFlow(SortMode.RECENT_READ)
    val filter = MutableStateFlow<ReadStatus?>(null)
    val query = MutableStateFlow("")
    val importing = MutableStateFlow(0)

    /** Every series, grouped from file names. Null until the first database read finishes. */
    val allSeries: StateFlow<List<Series>?> =
        combine(repo.observeAll(), repo.observeSeriesMeta()) { list, metas ->
            buildSeries(list, metas.associate { it.seriesKey to it.coverName }, repo.coverDir)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** [allSeries] after search, status filter and sort. */
    val shown: StateFlow<List<Series>> =
        combine(allSeries, sort, filter, query) { all, sort, filter, q ->
            val needle = q.trim()
            val list = all.orEmpty().filter { s ->
                (filter == null || s.status == filter) &&
                    (needle.isEmpty() || s.title.contains(needle, true) || s.items.any { it.manga.title.contains(needle, true) })
            }
            when (sort) {
                SortMode.RECENT_READ -> list.sortedWith(compareByDescending<Series> { it.lastReadAt }.thenByDescending { it.addedAt })
                SortMode.RECENT_ADDED -> list.sortedByDescending { it.addedAt }
                SortMode.TITLE -> list.sortedWith(Comparator { a, b -> naturalCompare(a.sortTitle, b.sortTitle) })
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Automatically look up covers for any series we haven't checked yet.
        viewModelScope.launch {
            allSeries.filterNotNull()
                .map { list -> list.associate { it.key to it.title } }
                .distinctUntilChanged()
                .collectLatest { wanted -> repo.fetchMissingCovers(wanted) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()

    fun import(uris: List<Uri>) {
        uris.forEach { uri ->
            importing.update { it + 1 }
            viewModelScope.launch {
                repo.import(uri).onFailure { toast("Couldn't import: ${it.message ?: "invalid PDF"}") }
                importing.update { it - 1 }
            }
        }
    }

    fun setCoverFromUrl(series: Series, url: String) {
        viewModelScope.launch {
            repo.setSeriesCoverFromUrl(series.key, url)
                .onSuccess { toast("Cover updated") }
                .onFailure { toast("Couldn't download that cover") }
        }
    }

    fun setCoverFromUri(series: Series, uri: Uri) {
        viewModelScope.launch {
            repo.setSeriesCoverFromUri(series.key, uri)
                .onSuccess { toast("Cover updated") }
                .onFailure { toast("Couldn't use that image") }
        }
    }

    fun resetCover(series: Series) {
        viewModelScope.launch { repo.clearSeriesCover(series.key) }
    }

    fun markAllRead(series: Series) {
        viewModelScope.launch { repo.markAllCompleted(series.items.map { it.manga }) }
    }

    fun markAllUnread(series: Series) {
        viewModelScope.launch { repo.markAllUnread(series.items.map { it.manga }) }
    }

    fun markRead(m: Manga) {
        viewModelScope.launch { repo.markCompleted(m) }
    }

    fun markUnread(m: Manga) {
        viewModelScope.launch { repo.markUnread(m) }
    }

    fun rename(m: Manga, title: String) {
        viewModelScope.launch { repo.rename(m, title) }
    }

    fun delete(m: Manga) {
        viewModelScope.launch { repo.delete(m) }
    }

    fun deleteSeries(series: Series) {
        viewModelScope.launch { repo.deleteSeries(series.key, series.items.map { it.manga }) }
    }
}
