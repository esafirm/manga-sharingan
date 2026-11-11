package com.esafirm.sharingan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.esafirm.sharingan.data.nextAfter
import com.esafirm.sharingan.ui.LibraryScreen
import com.esafirm.sharingan.ui.LibraryViewModel
import com.esafirm.sharingan.ui.BrowseScreen
import com.esafirm.sharingan.ui.BrowseViewModel
import com.esafirm.sharingan.ui.ChaptersScreen
import com.esafirm.sharingan.ui.ReaderScreen
import com.esafirm.sharingan.ui.ScraperScreen
import com.esafirm.sharingan.ui.ScraperViewModel
import com.esafirm.sharingan.ui.SeriesScreen
import com.esafirm.sharingan.ui.SettingsScreen
import com.esafirm.sharingan.ui.SharinganTheme
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Library : Screen
    data object Settings : Screen
    data object Scraper : Screen
    data object Browse : Screen
    data object Chapters : Screen
    data class Series(val key: String) : Screen
    data class Reader(val id: Long) : Screen
}

class MainActivity : ComponentActivity() {
    private val repo get() = (application as SharinganApp).repo
    private val vm: LibraryViewModel by viewModels()
    private val scraperVm: ScraperViewModel by viewModels()
    private val browseVm: BrowseViewModel by viewModels()
    private val stack = mutableStateListOf<Screen>(Screen.Library)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SharinganTheme { Root() } }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun pop() {
        if (stack.last() == Screen.Chapters) browseVm.closeChapters()
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    private fun openReader(id: Long) {
        if (stack.last() is Screen.Reader) stack[stack.lastIndex] = Screen.Reader(id) else stack.add(Screen.Reader(id))
    }

    private fun openScraper() {
        if (stack.last() != Screen.Scraper) stack.add(Screen.Scraper)
    }

    private fun push(screen: Screen) {
        if (stack.last() != screen) stack.add(screen)
    }

    @Composable
    private fun Root() {
        val gridState = rememberLazyGridState()
        val all by vm.allSeries.collectAsState()
        BackHandler(enabled = stack.size > 1) { pop() }

        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }) togetherWith fadeOut(tween(120))
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.Library -> LibraryScreen(
                    vm = vm,
                    gridState = gridState,
                    onOpenSeries = { s ->
                        // A series with a single file skips the detail page and just opens the book.
                        if (s.items.size == 1) openReader(s.items.first().manga.id) else stack.add(Screen.Series(s.key))
                    },
                    onResume = { openReader(it) },
                    onOpenSettings = { stack.add(Screen.Settings) },
                    onOpenScraper = { push(Screen.Browse) },
                )
                Screen.Settings -> SettingsScreen(
                    settings = (application as SharinganApp).settings,
                    onBack = { pop() },
                )
                Screen.Browse -> BrowseScreen(
                    vm = browseVm,
                    downloads = scraperVm,
                    onBack = { pop() },
                    onOpenSeries = { push(Screen.Chapters) },
                    onOpenDownloads = { openScraper() },
                )
                Screen.Chapters -> ChaptersScreen(
                    vm = browseVm,
                    downloads = scraperVm,
                    onBack = { pop() },
                    onOpenDownloads = { openScraper() },
                    onDownloadAsChapter = { url ->
                        scraperVm.prefill(url)
                        scraperVm.scanInput()
                        openScraper()
                    },
                )
                Screen.Scraper -> ScraperScreen(
                    vm = scraperVm,
                    onBack = { pop() },
                    onRead = { openReader(it) },
                )
                is Screen.Series -> SeriesScreen(
                    vm = vm,
                    key = screen.key,
                    onBack = { pop() },
                    onOpen = { openReader(it) },
                )
                is Screen.Reader -> ReaderScreen(
                    repo = repo,
                    id = screen.id,
                    next = all?.nextAfter(screen.id),
                    onClose = { pop() },
                    onOpenNext = { openReader(it) },
                )
            }
        }
    }

    /** PDFs sent from Telegram (or any app) via "Open with" or "Share"; links shared from a browser go to the scraper. */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString("\n")
            if (ScraperViewModel.extractLinks(text).isEmpty()) {
                Toast.makeText(this, "No link found in what was shared", Toast.LENGTH_SHORT).show()
                return
            }
            scraperVm.prefill(text)
            openScraper()
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val results = uris.map { repo.import(it) }
            val imported = results.mapNotNull { it.getOrNull() }
            val failed = results.size - imported.size
            if (failed > 0) Toast.makeText(this@MainActivity, "$failed file(s) couldn't be imported", Toast.LENGTH_LONG).show()
            when {
                imported.size == 1 -> openReader(imported[0].id) // single file: start reading right away
                imported.size > 1 -> {
                    while (stack.size > 1) stack.removeAt(stack.lastIndex)
                    Toast.makeText(this@MainActivity, "Added ${imported.size} manga", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
