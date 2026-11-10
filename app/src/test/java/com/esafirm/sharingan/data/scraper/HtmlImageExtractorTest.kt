package com.esafirm.sharingan.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlImageExtractorTest {
    private val base = "https://read.example.com/series/berserk/chapter-12"

    @Test fun picksTheChapterGroupAndDropsSiteChrome() {
        val html = """
            <html><head><title>Berserk - Chapter 12 | Example Reader</title></head><body>
            <img src="/static/logo.png" alt="logo">
            <img src="https://cdn.example.com/ads/banner-728.jpg">
            <div class="reader">
              <img src="https://img.example.net/berserk/12/001.jpg">
              <img src="https://img.example.net/berserk/12/002.jpg">
              <img src="https://img.example.net/berserk/12/003.jpg">
              <img src="https://img.example.net/berserk/12/004.jpg">
            </div>
            <img src="https://img.example.net/avatars/u1.jpg">
            </body></html>
        """.trimIndent()
        val page = HtmlImageExtractor.extract(html, base)
        assertEquals("Berserk - Chapter 12", page.title)
        assertEquals((1..4).map { "https://img.example.net/berserk/12/00$it.jpg" }, page.images)
    }

    @Test fun lazyLoadAttributesWinOverPlaceholderSrc() {
        val html = """
            <img src="/img/loading.gif" data-src="pages/p1.webp" class="lazy">
            <img src="data:image/gif;base64,R0lGOD" data-lazy-src="pages/p2.webp">
            <img src="/img/blank.png" data-original=" pages/p3.webp ">
            <img srcset="pages/p4-small.webp 400w, pages/p4.webp 1200w">
        """.trimIndent()
        val page = HtmlImageExtractor.extract(html, base)
        assertEquals(
            listOf(
                "https://read.example.com/series/berserk/pages/p1.webp",
                "https://read.example.com/series/berserk/pages/p2.webp",
                "https://read.example.com/series/berserk/pages/p3.webp",
                "https://read.example.com/series/berserk/pages/p4.webp",
            ),
            page.images,
        )
    }

    @Test fun resolvesProtocolRelativeAndDecodesEntities() {
        val html = """
            <img src='//cdn.example.org/c/1.jpg?token=a&amp;w=800'>
            <img src='//cdn.example.org/c/2.jpg?token=a&amp;w=800'>
            <img src='//cdn.example.org/c/3.jpg?token=a&amp;w=800'>
        """.trimIndent()
        val page = HtmlImageExtractor.extract(html, base)
        assertEquals("https://cdn.example.org/c/1.jpg?token=a&w=800", page.images.first())
        assertEquals(3, page.images.size)
    }

    @Test fun fallsBackToUrlsInScripts() {
        val html = """
            <img src="/static/site-logo.png">
            <script>var pages = ["https:\/\/img.example.net\/one\/a.png","https:\/\/img.example.net\/one\/b.png",
            "https:\/\/img.example.net\/one\/c.png"];</script>
        """.trimIndent()
        val page = HtmlImageExtractor.extract(html, base)
        assertEquals(
            listOf("https://img.example.net/one/a.png", "https://img.example.net/one/b.png", "https://img.example.net/one/c.png"),
            page.images,
        )
    }

    @Test fun filterOverridesGrouping() {
        val html = """
            <img src="https://a.example/x/1.jpg"><img src="https://a.example/x/2.jpg"><img src="https://a.example/x/3.jpg">
            <img src="https://b.example/manga/p1.jpg"><img src="https://b.example/manga/p2.jpg">
        """.trimIndent()
        val page = HtmlImageExtractor.extract(html, base, filter = "/MANGA/")
        assertEquals(listOf("https://b.example/manga/p1.jpg", "https://b.example/manga/p2.jpg"), page.images)
    }

    @Test fun duplicatesAreDroppedButOrderKept() {
        val html = (listOf(1, 2, 2, 3, 1).joinToString("") { "<img src=\"/p/$it.jpg\">" })
        val page = HtmlImageExtractor.extract(html, base)
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg", "/p/3.jpg").map { "https://read.example.com$it" }, page.images)
    }

    @Test fun ogTitleIsPreferred() {
        val html = """<title>Ignored</title><meta content="One Piece &#8211; Ch. 1000" property="og:title">"""
        assertEquals("One Piece – Ch. 1000", HtmlImageExtractor.title(html))
    }

    @Test fun noImagesAndNoTitle() {
        val page = HtmlImageExtractor.extract("<p>nothing here</p>", base)
        assertTrue(page.images.isEmpty())
        assertNull(page.title)
    }
}
