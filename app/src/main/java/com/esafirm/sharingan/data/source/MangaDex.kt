package com.esafirm.sharingan.data.source

import com.esafirm.sharingan.data.scraper.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Built-in source backed by the public MangaDex API (no account or key). Chapters are listed in
 * [language]; titles with no chapters in that language show an empty list.
 * Image links from the at-home server expire, so [pages] is only called right before a download.
 */
class MangaDexSource(private val language: () -> String) : MangaSource {
    override val id = ID
    override val name = "MangaDex"
    override val description = "mangadex.org · search by title"
    override val builtIn = true

    override suspend fun search(query: String): List<SourceManga> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$API/manga?title=${enc(q)}&limit=24&includes[]=cover_art&order[relevance]=desc" +
            RATINGS + "&availableTranslatedLanguage[]=${enc(language())}"
        return MangaDexParser.searchResults(get(url))
    }

    override suspend fun chapters(manga: SourceManga): ChapterListing {
        val all = mutableListOf<SourceChapter>()
        var offset = 0
        while (true) {
            val url = "$API/manga/${manga.id}/feed?translatedLanguage[]=${enc(language())}&limit=$PAGE&offset=$offset" +
                "&order[volume]=asc&order[chapter]=asc&includeExternalUrl=0&includeEmptyPages=0&includes[]=scanlation_group" + RATINGS
            val json = get(url)
            all += MangaDexParser.chapters(json)
            offset += PAGE
            if (offset >= json.optInt("total") || offset >= MAX_CHAPTERS) break
        }
        return ChapterListing(manga.title, MangaDexParser.dedupe(all))
    }

    override suspend fun pages(chapter: SourceChapter): ScanResult {
        val images = MangaDexParser.pageUrls(get("$API/at-home/server/${chapter.id}"))
        if (images.isEmpty()) throw IOException("This chapter has no pages on MangaDex (it may be hosted on an official site)")
        return ScanResult(chapter.url, chapter.name, images)
    }

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            // MangaDex asks API clients to identify themselves rather than pose as a browser.
            setRequestProperty("User-Agent", "Sharingan (Android manga reader)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 429) throw IOException("MangaDex is rate limiting, try again in a minute")
            if (code !in 200..299) throw IOException("MangaDex answered HTTP $code")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val ID = "mangadex"
        private const val API = "https://api.mangadex.org"
        private const val PAGE = 500
        private const val MAX_CHAPTERS = 5000
        private const val RATINGS = "&contentRating[]=safe&contentRating[]=suggestive"

        val LANGUAGES = listOf(
            "en" to "English", "id" to "Indonesian", "es-la" to "Spanish (LatAm)", "es" to "Spanish",
            "pt-br" to "Portuguese (BR)", "fr" to "French", "de" to "German", "it" to "Italian",
            "ru" to "Russian", "vi" to "Vietnamese", "th" to "Thai", "tr" to "Turkish",
        )
    }
}

/** Pure JSON → model mapping for [MangaDexSource]; unit-tested. */
object MangaDexParser {
    fun searchResults(root: JSONObject): List<SourceManga> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return objects(data).mapNotNull { m ->
            val id = m.optString("id").ifEmpty { return@mapNotNull null }
            val a = m.optJSONObject("attributes") ?: return@mapNotNull null
            val title = localized(a.optJSONObject("title"))
                ?: a.optJSONArray("altTitles")?.let { alts -> objects(alts).firstNotNullOfOrNull { localized(it) } }
                ?: return@mapNotNull null
            val cover = m.optJSONArray("relationships")?.let { rel ->
                objects(rel).firstOrNull { it.optString("type") == "cover_art" }
                    ?.optJSONObject("attributes")?.optString("fileName")?.takeIf { it.isNotEmpty() }
            }
            val year = a.optInt("year", 0).takeIf { it > 0 }
            val status = a.optString("status").takeIf { it.isNotEmpty() && it != "null" }?.replaceFirstChar { it.uppercase() }
            SourceManga(
                sourceId = MangaDexSource.ID,
                id = id,
                title = title,
                url = "https://mangadex.org/title/$id",
                coverUrl = cover?.let { "https://uploads.mangadex.org/covers/$id/$it.256.jpg" },
                subtitle = listOfNotNull(status, year?.toString()).joinToString(" · ").ifEmpty { null },
            )
        }
    }

    fun chapters(root: JSONObject): List<SourceChapter> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return objects(data).mapNotNull { c ->
            val id = c.optString("id").ifEmpty { return@mapNotNull null }
            val a = c.optJSONObject("attributes") ?: return@mapNotNull null
            if (a.optString("externalUrl").let { it.isNotEmpty() && it != "null" }) return@mapNotNull null
            val number = str(a, "chapter")?.toDoubleOrNull()
            val volume = str(a, "volume")
            val title = str(a, "title")
            val group = c.optJSONArray("relationships")?.let { rel ->
                objects(rel).firstOrNull { it.optString("type") == "scanlation_group" }
                    ?.optJSONObject("attributes")?.let { str(it, "name") }
            }
            val name = buildString {
                if (volume != null) append("Vol. $volume ")
                append(if (number != null) "Ch. ${formatNumber(number)}" else "Oneshot")
                if (title != null) append(" – $title")
            }
            SourceChapter(id, "https://mangadex.org/chapter/$id", name, number, volume, group)
        }
    }

    /** Several groups often translate the same chapter: keep the first upload of each number. */
    fun dedupe(chapters: List<SourceChapter>): List<SourceChapter> {
        val seen = HashSet<Double>()
        return chapters.filter { c -> c.number == null || seen.add(c.number) }
            .sortedWith(compareBy<SourceChapter> { it.number ?: Double.MAX_VALUE })
    }

    fun pageUrls(root: JSONObject): List<String> {
        val base = root.optString("baseUrl").ifEmpty { return emptyList() }
        val ch = root.optJSONObject("chapter") ?: return emptyList()
        val hash = ch.optString("hash").ifEmpty { return emptyList() }
        val files = ch.optJSONArray("data") ?: return emptyList()
        return (0 until files.length()).map { "$base/data/$hash/${files.optString(it)}" }
    }

    private fun localized(o: JSONObject?): String? {
        o ?: return null
        for (k in listOf("en", "ja-ro", "ko-ro", "zh-ro")) str(o, k)?.let { return it }
        return o.keys().asSequence().firstNotNullOfOrNull { str(o, it) }
    }

    private fun str(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
