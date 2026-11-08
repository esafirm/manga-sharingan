package com.esafirm.sharingan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.esafirm.sharingan.ui.LibraryScreen
import com.esafirm.sharingan.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as SharinganApp).repo
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var openId by rememberSaveable { mutableStateOf<Long?>(null) }
                val id = openId
                if (id == null) {
                    LibraryScreen(repo = repo, onOpen = { openId = it })
                } else {
                    BackHandler { openId = null }
                    ReaderScreen(repo = repo, id = id, onClose = { openId = null })
                }
            }
        }
    }
}
