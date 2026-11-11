package com.esafirm.sharingan.data.source

import com.esafirm.sharingan.data.scraper.RenderMode
import com.esafirm.sharingan.data.scraper.ScanResult
import com.esafirm.sharingan.data.scraper.WebScraper
import java.net.URL
import java.net.URLEncoder

/** What the user typed when adding a website; stored as JSON by [SourceRepository]. */
data class WebsiteConfig(
    val id: String,
    val name: String,
    /** A series page, or the home page of a site devoted to one series. Its chapter list is read. */
    val url: String,
    /** Optional search address with `{query}` in it, e.g. `https://site.com/?s={query}`. */
    val searchUrl: String? = null,
)

/**
 * Any manga website, read with the generic scrapers: [ChapterListExtractor] for chapter lists and
 * search results, [com.esafirm.sharingan.data.scraper.HtmlImageExtractor] for pages. JavaScript
 * sites go through the browser engine automatically.
 */
class WebsiteSource(val config: WebsiteConfig, private val scraper: WebScraper) : MangaSource {
    override val id get() = config.id
    override val name get() = config.name
    override val description: String
        get() = host(config.url) + if (config.searchUrl != null) " · search" else ""
    override val builtIn = false

    /** The configured page as a series, e.g. the one manga a fan site is about. */
    val home get() = SourceManga(config.id, config.url, config.name, config.url, subtitle = host(config.url))

    override suspend fun search(query: String): List<SourceManga> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val words = q.lowercase().split(Regex("\\s+"))
        val selfMatch = listOf(home).filter { m -> words.all { m.title.lowercase().contains(it) } }
        val template = config.searchUrl ?: return selfMatch
        val url = template.replace("{query}", URLEncoder.encode(q, "UTF-8"))
        val found = scraper.load(url, RenderMode.AUTO, enough = 1, count = { it.size }) { pageUrl, html, _ ->
            ChapterListExtractor.searchResults(html, pageUrl, q, config.id)
        }
        return (selfMatch + found).distinctBy { it.url.trimEnd('/') }
    }

    override suspend fun chapters(manga: SourceManga): ChapterListing = listChapters(scraper, manga.url)

    override suspend fun pages(chapter: SourceChapter): ScanResult = scraper.scan(chapter.url)

    companion object {
        fun host(url: String) = runCatching { URL(url).host.removePrefix("www.") }.getOrNull() ?: url

        /** Chapter list of any page; JS-built lists fall back to the browser engine. */
        suspend fun listChapters(scraper: WebScraper, url: String): ChapterListing =
            scraper.load(url, RenderMode.AUTO, enough = 2, count = { it.chapters.size }) { pageUrl, html, _ ->
                ChapterListExtractor.extract(html, pageUrl)
            }
    }
}
