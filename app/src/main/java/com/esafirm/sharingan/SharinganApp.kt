package com.esafirm.sharingan

import android.app.Application
import com.esafirm.sharingan.data.AppSettings
import com.esafirm.sharingan.data.MangaRepository
import com.esafirm.sharingan.data.scraper.WebScraper
import com.esafirm.sharingan.data.scraper.WebViewRenderer
import com.esafirm.sharingan.data.source.SourceRepository
import java.io.File

class SharinganApp : Application() {
    val repo by lazy { MangaRepository(this) }
    val settings by lazy { AppSettings(this) }

    /** Browser engine for JavaScript sites; its WebView is shown on the download screens while it works. */
    val renderer by lazy { WebViewRenderer(this) }
    val scraper by lazy {
        val work = File(cacheDir, "scraper").apply { deleteRecursively(); mkdirs() } // drop leftovers from a killed run
        WebScraper(work, renderer)
    }
    val sources by lazy { SourceRepository(this, scraper) }
}
