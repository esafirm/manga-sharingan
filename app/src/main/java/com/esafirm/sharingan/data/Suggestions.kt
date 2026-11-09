package com.esafirm.sharingan.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

data class Suggestion(
    val id: Int,
    val title: String,
    /** e.g. "Manga · 2001 · ★ 8.4" */
    val subtitle: String?,
    val imageUrl: String,
    val url: String,
    /** All known names (english/romaji), used to hide titles that are already in the library. */
    val names: List<String>,
)

/**
 * "Similar manga" from a title, using AniList's community recommendations (topped up with popular
 * titles in the same genres when there are too few). Results are cached on disk for a week so the
 * shelf and series pages open instantly and we stay well within AniList's rate limits.
 *
 * Network failures throw [IOException] (and are never cached) so callers can offer a retry.
 */
class SuggestionFinder(private val cacheDir: File) {

    suspend fun similarTo(title: String, key: String): List<Suggestion> {
        readCache(key)?.let { return it }
        val result = fetch(title)
        writeCache(key, result)
        return result
    }

    private suspend fun fetch(title: String): List<Suggestion> {
        val root = AniList.query(SEARCH_GQL, JSONObject().put("q", title))
        val media = root.getJSONObject("data").getJSONObject("Page").getJSONArray("media")

        // Find the AniList entry that is really *this* title; a wrong seed gives irrelevant suggestions.
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until media.length()) {
            val m = media.getJSONObject(i)
            val score = names(m).maxOfOrNull { CoverFinder.score(title, it) } ?: 0.0
            if (score > bestScore) {
                best = m
                bestScore = score
            }
        }
        val seed = best ?: return emptyList()
        if (bestScore < MATCH_THRESHOLD) return emptyList()
        val seedId = seed.optInt("id")

        val nodes = seed.optJSONObject("recommendations")?.optJSONArray("nodes")
        val recs = if (nodes == null) emptyList() else (0 until nodes.length()).mapNotNull { i ->
            val node = nodes.optJSONObject(i) ?: return@mapNotNull null
            if (node.optInt("rating", 0) < 1) return@mapNotNull null // net-downvoted
            node.optJSONObject("mediaRecommendation")?.let { parseMedia(it) }
        }

        val all = if (recs.size >= MIN_RECS) {
            recs
        } else {
            val genres = seed.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty().take(2)
            recs + if (genres.isEmpty()) emptyList() else byGenres(genres, seedId)
        }
        return all.filter { it.id != seedId }.distinctBy { it.id }.take(MAX_RESULTS)
    }

    /** Popular titles sharing the seed's genres. Best effort: a failure here keeps what we already have. */
    private suspend fun byGenres(genres: List<String>, excludeId: Int): List<Suggestion> =
        try {
            val vars = JSONObject().put("g", JSONArray(genres)).put("id", excludeId)
            val media = AniList.query(GENRE_GQL, vars).getJSONObject("data").getJSONObject("Page").getJSONArray("media")
            (0 until media.length()).mapNotNull { parseMedia(media.getJSONObject(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private fun names(m: JSONObject): List<String> {
        val t = m.optJSONObject("title")
        return listOfNotNull(t?.optString("english"), t?.optString("romaji")).filter { it.isNotEmpty() && it != "null" }
    }

    private fun parseMedia(m: JSONObject): Suggestion? {
        if (m.optBoolean("isAdult", false)) return null
        val id = m.optInt("id", 0).takeIf { it > 0 } ?: return null
        val image = m.optJSONObject("coverImage")?.optString("large").orEmpty()
        if (image.isEmpty() || image == "null") return null
        val names = names(m)
        val title = names.firstOrNull() ?: return null

        val format = m.optString("format").takeIf { it.isNotEmpty() && it != "null" }
            ?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
        val year = m.optJSONObject("startDate")?.optInt("year", 0)?.takeIf { it > 0 }
        val score = m.optInt("averageScore", 0).takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it / 10.0) }
        val url = m.optString("siteUrl").takeIf { it.startsWith("http") } ?: "https://anilist.co/manga/$id"

        return Suggestion(
            id = id,
            title = title,
            subtitle = listOfNotNull(format, year?.toString(), score).joinToString(" · ").ifEmpty { null },
            imageUrl = image,
            url = url,
            names = names,
        )
    }

    // ---- disk cache --------------------------------------------------------------------

    private fun cacheFile(key: String) = File(cacheDir, key.hashCode().toUInt().toString(16) + ".json")

    private suspend fun readCache(key: String): List<Suggestion>? = withContext(Dispatchers.IO) {
        try {
            val f = cacheFile(key)
            if (!f.exists()) return@withContext null
            val root = JSONObject(f.readText())
            if (System.currentTimeMillis() - root.getLong("ts") > TTL_MS) return@withContext null
            val items = root.getJSONArray("items")
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                val names = o.getJSONArray("names")
                Suggestion(
                    id = o.getInt("id"),
                    title = o.getString("title"),
                    subtitle = if (o.isNull("subtitle")) null else o.getString("subtitle"),
                    imageUrl = o.getString("image"),
                    url = o.getString("url"),
                    names = (0 until names.length()).map { names.getString(it) },
                )
            }
        } catch (e: Exception) {
            null // unreadable cache is just a miss
        }
    }

    private suspend fun writeCache(key: String, list: List<Suggestion>) = withContext(Dispatchers.IO) {
        try {
            cacheDir.mkdirs()
            val items = JSONArray()
            list.forEach {
                items.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("subtitle", it.subtitle ?: JSONObject.NULL)
                        .put("image", it.imageUrl)
                        .put("url", it.url)
                        .put("names", JSONArray(it.names)),
                )
            }
            cacheFile(key).writeText(JSONObject().put("ts", System.currentTimeMillis()).put("items", items).toString())
        } catch (e: Exception) {
            // cache is best effort
        }
    }

    private companion object {
        const val MATCH_THRESHOLD = 0.6
        const val MIN_RECS = 6
        const val MAX_RESULTS = 15
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        const val MEDIA_FIELDS =
            "id siteUrl format averageScore isAdult startDate { year } title { romaji english } coverImage { large }"

        const val SEARCH_GQL =
            "query (\$q: String) { Page(perPage: 6) { media(search: \$q, type: MANGA, sort: SEARCH_MATCH) { " +
                "id genres title { romaji english } " +
                "recommendations(sort: RATING_DESC, perPage: 15) { nodes { rating mediaRecommendation { $MEDIA_FIELDS } } } } } }"

        const val GENRE_GQL =
            "query (\$g: [String], \$id: Int) { Page(perPage: 15) { " +
                "media(type: MANGA, genre_in: \$g, id_not: \$id, sort: POPULARITY_DESC, isAdult: false) { $MEDIA_FIELDS } } }"
    }
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** Drops suggestions the user already has (matched on normalized title). */
fun List<Suggestion>.excludingLibrary(library: List<Series>): List<Suggestion> {
    val have = library.flatMap { s -> listOf(s.key) + s.items.map { it.parsed.key } }.toSet()
    return filter { sug -> sug.names.none { norm(it) in have } }
}
