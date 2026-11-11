package com.esafirm.sharingan.data.source

import android.content.Context
import com.esafirm.sharingan.data.scraper.WebScraper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Built-in sources plus websites the user added (kept in SharedPreferences as JSON). */
class SourceRepository(context: Context, private val scraper: WebScraper) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(prefs.getString(KEY_LANG, null) ?: "en")
    /** Chapter language for API sources (MangaDex). */
    val language: StateFlow<String> = _language.asStateFlow()

    /** Lists whatever page the user pastes; not shown in the source list. */
    val link = LinkSource(scraper)
    private val builtIns: List<MangaSource> = listOf(MangaDexSource { _language.value }, link)

    private val _sources = MutableStateFlow(builtIns + loadWebsites().map { WebsiteSource(it, scraper) })
    val sources: StateFlow<List<MangaSource>> = _sources.asStateFlow()

    fun get(id: String): MangaSource? = _sources.value.firstOrNull { it.id == id }

    fun setLanguage(code: String) {
        prefs.edit().putString(KEY_LANG, code).apply()
        _language.value = code
    }

    fun addWebsite(name: String, url: String, searchUrl: String?): WebsiteSource {
        val config = WebsiteConfig(
            id = "web-" + UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { WebsiteSource.host(url) },
            url = url.trim(),
            searchUrl = searchUrl?.trim()?.takeIf { it.contains("{query}") },
        )
        val source = WebsiteSource(config, scraper)
        _sources.value = _sources.value + source
        save()
        return source
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        _sources.value = _sources.value.map { s ->
            if (s is WebsiteSource && s.id == id) WebsiteSource(s.config.copy(name = clean), scraper) else s
        }
        save()
    }

    fun remove(id: String) {
        _sources.value = _sources.value.filterNot { it.id == id && !it.builtIn }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _sources.value.filterIsInstance<WebsiteSource>().forEach { s ->
            arr.put(
                JSONObject().put("id", s.config.id).put("name", s.config.name).put("url", s.config.url)
                    .put("searchUrl", s.config.searchUrl ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY_WEBSITES, arr.toString()).apply()
    }

    private fun loadWebsites(): List<WebsiteConfig> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_WEBSITES, null) ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WebsiteConfig(
                id = o.optString("id").ifEmpty { return@mapNotNull null },
                name = o.optString("name"),
                url = o.optString("url").ifEmpty { return@mapNotNull null },
                searchUrl = if (o.isNull("searchUrl")) null else o.optString("searchUrl").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_WEBSITES = "websites"
        const val KEY_LANG = "language"
    }
}
