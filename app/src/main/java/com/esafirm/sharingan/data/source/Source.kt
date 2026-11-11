package com.esafirm.sharingan.data.source

import com.esafirm.sharingan.data.scraper.ScanResult

/** A series as a source knows it: enough to show a result and fetch its chapter list. */
data class SourceManga(
    val sourceId: String,
    /** Source-specific id: an API id (MangaDex) or the series page URL (websites). */
    val id: String,
    val title: String,
    /** Web page for the series; shown to the user and used as image referer. */
    val url: String,
    val coverUrl: String? = null,
    val subtitle: String? = null,
)

data class SourceChapter(
    /** Source-specific id: an API id (MangaDex) or the chapter page URL (websites). */
    val id: String,
    val url: String,
    /** As the source names it, e.g. "One Punch Man, Chapter 180" or "Ch. 12 – The Hero". */
    val name: String,
    val number: Double? = null,
    val volume: String? = null,
    val group: String? = null,
)

data class ChapterListing(val title: String?, val chapters: List<SourceChapter>)

/** Somewhere manga can be searched, listed by chapter and downloaded from. */
interface MangaSource {
    val id: String
    val name: String
    /** One line shown under the name, e.g. the site address. */
    val description: String
    /** Built-in sources ship with the app and can't be removed. */
    val builtIn: Boolean

    suspend fun search(query: String): List<SourceManga>

    suspend fun chapters(manga: SourceManga): ChapterListing

    /** The chapter's page images, resolved right before downloading (some image links expire). */
    suspend fun pages(chapter: SourceChapter): ScanResult
}

/** "One Punch Man - Ch. 180": a title [com.esafirm.sharingan.data.FileNameParser] groups into the series. */
fun chapterFileTitle(series: String, chapter: SourceChapter): String {
    val s = series.trim()
    val n = chapter.number
    return if (n != null) "$s - Ch. ${formatNumber(n)}" else "$s - ${chapter.name.trim()}"
}

fun formatNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()
