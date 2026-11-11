package com.esafirm.sharingan.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterListExtractorTest {
    /** Shape of a typical single-series WordPress fan site home page. */
    private val fanSite = """
        <html><head>
        <title>One Punch Man Manga Online</title>
        <meta property="og:title" content="One Punch Man Manga Online" />
        </head><body>
        <nav><a href="https://onepunch.example.com/">Home</a> <a href="/about/">About</a>
          <a href="https://twitter.com/x">Twitter</a></nav>
        <div class="latest">Latest: <a href="https://onepunch.example.com/manga/one-punch-man-chapter-180/">Read the newest chapter</a></div>
        <ul class="su-posts">
          <li><a href="https://onepunch.example.com/manga/one-punch-man-chapter-180/">One Punch Man, Chapter 180</a></li>
          <li><a href="https://onepunch.example.com/manga/one-punch-man-chapter-179-5/">One Punch Man, Chapter 179.5</a></li>
          <li><a href="https://onepunch.example.com/manga/one-punch-man-chapter-179/">One Punch Man, Chapter 179</a></li>
          <li><a href="https://onepunch.example.com/manga/one-punch-man-chapter-2/">One Punch Man, Chapter 2</a></li>
          <li><a href="https://onepunch.example.com/manga/one-punch-man-chapter-1/">One Punch Man, Chapter 1</a></li>
        </ul>
        <a href="https://onepunch.example.com/2024/05/news-post-12/">News 12</a>
        <a href="https://other.example.org/manga/x-chapter-3/">Chapter 3 elsewhere</a>
        </body></html>
    """.trimIndent()

    @Test fun listsChaptersOfAFanSiteInOrder() {
        val listing = ChapterListExtractor.extract(fanSite, "https://onepunch.example.com/")
        assertEquals("One Punch Man", listing.title)
        assertEquals(listOf(1.0, 2.0, 179.0, 179.5, 180.0), listing.chapters.map { it.number })
        // The "latest" link and the list link are the same chapter: kept once, with the fuller name.
        assertEquals("One Punch Man, Chapter 180", listing.chapters.last().name)
        assertTrue(listing.chapters.all { it.url.startsWith("https://onepunch.example.com/manga/") })
    }

    @Test fun numberFromUrlWhenTextHasNone() {
        val html = """
            <a href="/series/abc/ch-10">Ten</a><a href="/series/abc/ch-11">Eleven</a>
            <a href="/series/abc/ch-12">Twelve</a><a href="/series/abc/">Back</a>
        """.trimIndent()
        val listing = ChapterListExtractor.extract(html, "https://site.example/series/abc/")
        assertEquals(listOf(10.0, 11.0, 12.0), listing.chapters.map { it.number })
        assertEquals("https://site.example/series/abc/ch-10", listing.chapters.first().url)
    }

    @Test fun chapterNumberParsing() {
        assertEquals(12.5, ChapterListExtractor.chapterNumber("Ch.12.5", "https://a.b/x")!!, 0.0)
        assertEquals(7.0, ChapterListExtractor.chapterNumber("Episode 7 – Start", "https://a.b/x")!!, 0.0)
        assertEquals(30.0, ChapterListExtractor.chapterNumber("", "https://a.b/manga/foo-chapter-30-english/")!!, 0.0)
        assertEquals(30.5, ChapterListExtractor.chapterNumber("", "https://a.b/manga/foo-chapter-30-5/")!!, 0.0)
        // "ch" inside a word is not a chapter marker.
        assertEquals(null, ChapterListExtractor.chapterNumber("One Punch 5", "https://a.b/punch-5/"))
    }

    @Test fun cleansSiteTitles() {
        assertEquals("One Punch Man", ChapterListExtractor.cleanTitle("Read One Punch Man Manga Online Free"))
        assertEquals("Berserk", ChapterListExtractor.cleanTitle("Berserk | Best Manga Site"))
        assertEquals("Solo Leveling", ChapterListExtractor.cleanTitle("Solo Leveling Manhwa"))
    }

    @Test fun searchResultsMatchAllWords() {
        val html = """
            <div class="result"><a href="/manga/one-punch-man/" title="One Punch Man"><img src="/c/opm.jpg" alt="One Punch Man"></a>
            <a href="/manga/one-punch-man/">One Punch Man</a></div>
            <a href="/manga/one-piece/">One Piece</a>
            <a href="/manga/one-punch-man/chapter-3/">One Punch Man Chapter 3</a>
        """.trimIndent()
        val results = ChapterListExtractor.searchResults(html, "https://site.example/?s=one+punch", "one punch", "src")
        assertEquals(1, results.size)
        assertEquals("One Punch Man", results[0].title)
        assertEquals("https://site.example/manga/one-punch-man/", results[0].url)
        assertEquals("https://site.example/c/opm.jpg", results[0].coverUrl)
    }

    @Test fun chapterTitlesGroupIntoTheSeries() {
        val c = SourceChapter("x", "u", "Chapter 12", 12.0)
        assertEquals("One Punch Man - Ch. 12", chapterFileTitle("One Punch Man", c))
        val parsed = com.esafirm.sharingan.data.FileNameParser.parse(chapterFileTitle("One Punch Man", c.copy(number = 179.5)))
        assertEquals("One Punch Man", parsed.series)
        assertEquals(179.5, parsed.chapter!!, 0.0)
    }
}
