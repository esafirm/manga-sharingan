package com.esafirm.sharingan.data.scraper

import java.net.URL

/** What a chapter page looks like to the scraper: a suggested title and its page images, in reading order. */
data class ScrapedPage(val title: String?, val images: List<String>)

/**
 * Finds the manga page images in a chapter's HTML. Pure Kotlin (no Android), so it is unit-tested.
 *
 * Strategy:
 * 1. Every `<img>`: lazy-load attributes (`data-src`, …) win over `src`, which is often a placeholder.
 * 2. Pages of a chapter live next to each other on the image host, so the biggest group of images
 *    sharing one directory is the chapter; logos, avatars and ads elsewhere fall away.
 * 3. Readers that build the page list in JavaScript still ship the URLs in a `<script>`, so if
 *    `<img>` tags give too little, image URLs anywhere in the source are tried the same way.
 *
 * A non-blank [filter] skips the guessing: every image whose URL contains it is kept.
 */
object HtmlImageExtractor {
    private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")
    private val SCRIPT_URL = Regex(
        "https?://[^\\s\"'<>()\\\\]+?\\.(?:jpe?g|png|webp|avif)(?:\\?[^\\s\"'<>()\\\\]*)?(?=[\\s\"'<>()\\\\,]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|quot|apos|lt|gt|nbsp);")

    /** `src` last: lazy loaders park a spinner/blank there and keep the real page in a data attribute. */
    private val SRC_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-lazy", "data-echo", "data-cfsrc", "src")
    private val SRCSET_ATTRS = listOf("data-srcset", "data-lazy-srcset", "srcset")
    private val IMAGE_EXT = Regex("\\.(?:jpe?g|png|webp|avif|bmp)$", RegexOption.IGNORE_CASE)
    private val NOT_A_PAGE = Regex("\\.(?:svg|gif|ico)$", RegexOption.IGNORE_CASE)
    private val JUNK_NAME = Regex("logo|icon|avatar|banner|sprite|emoji|loading|spinner|placeholder|favicon|blank\\.|pixel\\.", RegexOption.IGNORE_CASE)

    /** A group needs at least this many images to look like a chapter rather than page decoration. */
    private const val MIN_GROUP = 3

    fun extract(html: String, baseUrl: String, filter: String? = null): ScrapedPage {
        val fromTags = imgTagUrls(html, baseUrl)
        val needle = filter?.trim().orEmpty()
        val images = if (needle.isNotEmpty()) {
            (fromTags + scriptUrls(html)).distinct().filter { it.contains(needle, ignoreCase = true) }
        } else {
            val tagged = pickChapter(fromTags)
            if (tagged.size >= MIN_GROUP) tagged
            else pickChapter(scriptUrls(html)).takeIf { it.size > tagged.size } ?: tagged
        }
        return ScrapedPage(title(html), images)
    }

    /** `og:title`, else `<title>`, without a trailing "| Site name". */
    fun title(html: String): String? {
        val og = META.findAll(html).map { attrs(it.value) }
            .firstOrNull { (it["property"] ?: it["name"]).equals("og:title", ignoreCase = true) }
            ?.get("content")
        val raw = og?.takeIf { it.isNotBlank() } ?: TITLE.find(html)?.groupValues?.get(1) ?: return null
        return decodeEntities(raw).replace(Regex("\\s+"), " ").substringBefore(" | ").trim().ifEmpty { null }
    }

    private fun imgTagUrls(html: String, baseUrl: String): List<String> =
        IMG.findAll(html).mapNotNull { m ->
            val a = attrs(m.value)
            val refs = SRC_ATTRS.mapNotNull { a[it]?.trim() } + SRCSET_ATTRS.mapNotNull { a[it]?.let(::largestFromSrcset) }
            refs.asSequence()
                .filter { it.isUsable() }
                .mapNotNull { resolve(baseUrl, it) }
                .firstOrNull { looksLikePage(it) }
        }.distinct().toList()

    private fun scriptUrls(html: String): List<String> {
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        return SCRIPT_URL.findAll(unescaped).map { decodeEntities(it.value) }.filter { looksLikePage(it) }.distinct().toList()
    }

    /** The biggest same-directory group (first one on ties), or everything if no group is convincing. */
    internal fun pickChapter(urls: List<String>): List<String> {
        if (urls.isEmpty()) return urls
        val groups = urls.groupBy { directoryOf(it) }
        val best = groups.values.maxBy { it.size } // maxBy keeps the first of equal maxima
        return if (best.size >= MIN_GROUP) best else urls
    }

    private fun String.isUsable() = isNotEmpty() && !startsWith("data:") && !startsWith("javascript:") && !startsWith("about:")

    private fun looksLikePage(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        if (NOT_A_PAGE.containsMatchIn(path)) return false
        if (JUNK_NAME.containsMatchIn(name)) return false
        // Extension-less URLs (image proxies, CDNs) are fine; any other extension is not an image.
        return IMAGE_EXT.containsMatchIn(path) || !name.contains('.')
    }

    private fun directoryOf(url: String) = url.substringBefore('#').substringBefore('?').substringBeforeLast('/')

    private fun largestFromSrcset(srcset: String): String? {
        val candidates = srcset.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { c ->
            val parts = c.split(Regex("\\s+"))
            parts[0] to (parts.getOrNull(1)?.dropLast(1)?.toDoubleOrNull() ?: 0.0)
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    internal fun resolve(base: String, ref: String): String? = runCatching {
        URL(URL(base), decodeEntities(ref).replace(" ", "%20")).toString()
    }.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { m ->
            val v = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            m.groupValues[1].lowercase() to decodeEntities(v)
        }

    internal fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            else -> {
                val code = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}
