package com.esafirm.sharingan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class CoverResult(
    val title: String,
    val subtitle: String?,
    val imageUrl: String,
    /** All known names (romaji/english) used for matching. */
    val names: List<String>,
)

/** Looks up manga covers on AniList (free, no API key). Network failures throw [IOException]. */
object CoverFinder {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val GQL =
        "query (\$q: String) { Page(perPage: 12) { media(search: \$q, type: MANGA, sort: SEARCH_MATCH) " +
            "{ title { romaji english } startDate { year } format coverImage { large } } } }"

    suspend fun search(query: String): List<CoverResult> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("query", GQL)
            .put("variables", JSONObject().put("q", query))
            .toString()
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<CoverResult> {
        val media = JSONObject(json).getJSONObject("data").getJSONObject("Page").getJSONArray("media")
        return (0 until media.length()).mapNotNull { i ->
            val m = media.getJSONObject(i)
            val url = m.optJSONObject("coverImage")?.optString("large").orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            val t = m.optJSONObject("title")
            val romaji = t?.optString("romaji").orEmpty().takeIf { it.isNotEmpty() && it != "null" }
            val english = t?.optString("english").orEmpty().takeIf { it.isNotEmpty() && it != "null" }
            val year = m.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
            val format = m.optString("format").takeIf { it.isNotEmpty() && it != "null" }
            CoverResult(
                title = english ?: romaji ?: return@mapNotNull null,
                subtitle = listOfNotNull(format?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }, year?.toString())
                    .joinToString(" · ").ifEmpty { null },
                imageUrl = url,
                names = listOfNotNull(english, romaji),
            )
        }
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** 0..1 similarity between a file-derived title and a candidate name. */
    fun score(query: String, candidate: String): Double {
        val a = norm(query)
        val b = norm(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length)
        return 0.0
    }

    /** Best result for [query], or null if nothing is a convincing match (we'd rather show no cover than a wrong one). */
    fun bestMatch(query: String, results: List<CoverResult>, threshold: Double = 0.6): CoverResult? =
        results.maxByOrNull { r -> r.names.maxOfOrNull { score(query, it) } ?: 0.0 }
            ?.takeIf { r -> (r.names.maxOfOrNull { score(query, it) } ?: 0.0) >= threshold }
}
