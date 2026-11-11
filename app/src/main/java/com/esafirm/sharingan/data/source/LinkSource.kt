package com.esafirm.sharingan.data.source

import com.esafirm.sharingan.data.scraper.ScanResult
import com.esafirm.sharingan.data.scraper.WebScraper

/** Hidden source behind "open a link": lists any series page the user pastes, without saving it. */
class LinkSource(private val scraper: WebScraper) : MangaSource {
    override val id = ID
    override val name = "Link"
    override val description = "Any series page"
    override val builtIn = true

    override suspend fun search(query: String): List<SourceManga> = emptyList()

    override suspend fun chapters(manga: SourceManga): ChapterListing = WebsiteSource.listChapters(scraper, manga.url)

    override suspend fun pages(chapter: SourceChapter): ScanResult = scraper.scan(chapter.url)

    fun manga(url: String) = SourceManga(ID, url, WebsiteSource.host(url), url, subtitle = WebsiteSource.host(url))

    companion object {
        const val ID = "link"
    }
}
