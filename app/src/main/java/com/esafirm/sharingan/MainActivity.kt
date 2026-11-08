package com.esafirm.sharingan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.esafirm.sharingan.ui.LibraryScreen
import com.esafirm.sharingan.ui.ReaderScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repo get() = (application as SharinganApp).repo
    private var openId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val id = openId
                if (id == null) {
                    LibraryScreen(repo = repo, onOpen = { openId = it })
                } else {
                    BackHandler { openId = null }
                    ReaderScreen(repo = repo, id = id, onClose = { openId = null })
                }
            }
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** PDFs sent from Telegram (or any app) via "Open with" or "Share". */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
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
                imported.size == 1 -> openId = imported[0].id // single file: start reading right away
                imported.size > 1 -> {
                    openId = null
                    Toast.makeText(this@MainActivity, "Added ${imported.size} manga", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
