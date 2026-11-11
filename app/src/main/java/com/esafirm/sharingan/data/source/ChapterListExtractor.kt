package com.esafirm.sharingan.data.source

import com.esafirm.sharingan.data.scraper.HtmlImageExtractor
import java.net.URL

/**
 * Reads a series page (or a single-series fan site's home page) and finds its chapter list.
 * Pure Kotlin, unit-tested.
 *
 * Every same-site link whose text or address carries a chapter number is a candidate. The chapter
 * list is the biggest group of links sharing one address shape (digits ignored), so the "latest
 * chapter" widget, navigation and comment links fall away. Result is sorted by chapter number.
 *
 * NOTE: Android compiles regexes with ICU, so `]`, `}` and friends are always escaped.
 */
object ChapterListExtractor {
    private val ANCHOR = Regex("<a\\b([^>]*)>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val HREF = Regex("\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", RegexOption.IGNORE_CASE)
    private val TITLE_ATTR = Regex("\\btitle\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val ALT_ATTR = Regex("\\balt\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("<img\\b[^>]*?\\b(?:data-src|data-lazy-src|src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")
    private val H1 = Regex("<h1\\b[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DIGITS = Regex("\\d+(?:[-_.]\\d+)*")

    /** "Chapter 12", "Ch.12.5", "Episode 3", "#7" in link text. */
    private val TEXT_NUMBER = Regex("(?<![a-z])(?:chapter|chap|ch|episode|ep)\\.?\\s*#?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    /** `.../chapter-12/`, `chapter_12-5`, `/ch/12` in the address (`-5` after the number = `.5`). */
    private val URL_NUMBER = Regex(
        "(?<![a-z])(?:chapter|chap|ch|episode|ep)[-_/.]?(\\d+)(?:[-_.](\\d{1,2}))?(?=[/?#]|$|[-_.][^\\d])",
        RegexOption.IGNORE_CASE,
    )

    fun extract(html: String, baseUrl: String): ChapterListing {
        val baseSite = siteOf(baseUrl)
        val candidates = links(html, baseUrl)
            .filter { siteOf(it.url) == baseSite }
            .mapNotNull { link ->
                val n = chapterNumber(link.text, link.url) ?: return@mapNotNull null
                SourceChapter(id = link.url, url = link.url, name = link.text.ifEmpty { "Chapter ${formatNumber(n)}" }, number = n)
            }
            .filter { it.url.substringBefore('#') != baseUrl.substringBefore('#') }
        val chapters = pickList(candidates)
            .groupBy { it.url.substringBefore('#') }.values
            .map { same -> same.maxBy { it.name.length } } // same chapter linked twice: keep the more descriptive name
            .sortedWith(compareBy<SourceChapter> { it.number ?: Double.MAX_VALUE })
        return ChapterListing(seriesTitle(html), chapters)
    }

    /**
     * Series links on a search results page: links whose text (or image alt) contains every word
     * of [query]. Chapter links are left out; covers come from an image inside the link.
     */
    fun searchResults(html: String, baseUrl: String, query: String, sourceId: String): List<SourceManga> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val baseSite = siteOf(baseUrl)
        val hits = links(html, baseUrl).filter { l ->
            val hay = normalize(l.text + " " + l.label)
            siteOf(l.url) == baseSite && words.all { hay.contains(it) } && chapterNumber("", l.url) == null
        }
        return hits.groupBy { it.url.substringBefore('#').trimEnd('/') }.values.map { same ->
            val best = same.maxBy { it.text.length }
            SourceManga(
                sourceId = sourceId,
                id = best.url,
                title = best.text.ifEmpty { best.label }.ifEmpty { best.url },
                url = best.url,
                coverUrl = same.firstNotNullOfOrNull { it.image },
            )
        }.filter { it.title.length <= 200 }
    }

    /** og:title, else the first `<h1>`, else `<title>`, minus site-name and "read online" noise. */
    fun seriesTitle(html: String): String? {
        val raw = HtmlImageExtractor.title(html)
            ?: H1.find(html)?.groupValues?.get(1)?.let { text(it) }
            ?: return null
        return cleanTitle(raw)
    }

    internal fun cleanTitle(raw: String): String? {
        var t = raw.replace(SPACES, " ").trim()
        t = t.split(" | ", " – ", " — ").first().trim()
        t = t.replace(Regex("^read\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(?:manga|manhwa|manhua|comics?)?\\s*(?:online|free|english|raw|scans?)(?:\\s+(?:online|free|english))*\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(?:manga|manhwa|manhua)\\s*$", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', '-', ':', ',').ifEmpty { null }
    }

    internal fun chapterNumber(text: String, url: String): Double? {
        TEXT_NUMBER.find(text)?.let { return it.groupValues[1].toDoubleOrNull() }
        val path = runCatching { URL(url).path }.getOrNull() ?: return null
        val segment = path.trimEnd('/').substringAfterLast('/')
        val m = URL_NUMBER.findAll(segment).lastOrNull() ?: URL_NUMBER.findAll(path).lastOrNull() ?: return null
        val whole = m.groupValues[1]
        val frac = m.groupValues[2]
        return (if (frac.isEmpty()) whole else "$whole.$frac").toDoubleOrNull()
    }

    /** Biggest group of links with the same address shape; everything if no group stands out. */
    private fun pickList(all: List<SourceChapter>): List<SourceChapter> {
        if (all.size < 3) return all
        val best = all.groupBy { shape(it.url) }.values.maxBy { it.size }
        return if (best.size >= 2) best else all
    }

    private fun shape(url: String) = url.substringBefore('#').substringBefore('?').trimEnd('/').replace(DIGITS, "#")

    private data class Link(val url: String, val text: String, val label: String, val image: String?)

    private fun links(html: String, baseUrl: String): List<Link> =
        ANCHOR.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            val href = HREF.find(attrs)?.let { h -> h.groupValues.drop(1).firstOrNull { it.isNotEmpty() } } ?: return@mapNotNull null
            if (href.startsWith("#") || href.startsWith("javascript:", true) || href.startsWith("mailto:", true)) return@mapNotNull null
            val url = HtmlImageExtractor.resolve(baseUrl, href.trim()) ?: return@mapNotNull null
            val label = listOfNotNull(
                TITLE_ATTR.find(attrs)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() },
                ALT_ATTR.find(inner)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() },
            ).joinToString(" ") { HtmlImageExtractor.decodeEntities(it) }
            val image = IMG_SRC.find(inner)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                ?.takeIf { !it.startsWith("data:") }?.let { HtmlImageExtractor.resolve(baseUrl, it) }
            Link(url, text(inner), label, image)
        }.toList()

    private fun text(html: String) = HtmlImageExtractor.decodeEntities(html.replace(TAG, " ")).replace(SPACES, " ").trim()

    private fun normalize(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").replace(SPACES, " ").trim()

    /** `www.site.com` and `site.com` are the same site; `cdn.site.com` too. */
    internal fun siteOf(url: String): String? =
        runCatching { URL(url).host.lowercase() }.getOrNull()?.split('.')?.takeLast(2)?.joinToString(".")
}
