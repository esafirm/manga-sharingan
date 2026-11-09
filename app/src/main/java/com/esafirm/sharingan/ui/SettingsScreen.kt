package com.esafirm.sharingan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.esafirm.sharingan.data.AppSettings
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    var fastScroll by remember { mutableStateOf(settings.fastScrollEnabled) }
    var holdMs by remember { mutableIntStateOf(settings.fastScrollHoldMs) }
    val suggestions by settings.showSuggestions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Reading")

            SwitchRow(
                title = "Fast-scroll gesture",
                description = "Hold a finger still on a page, then drag up or down to jump through the book.",
                checked = fastScroll,
                onChange = { fastScroll = it; settings.fastScrollEnabled = it },
            )

            Column(Modifier.alpha(if (fastScroll) 1f else 0.4f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Hold time", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        String.format(Locale.US, "%.1f s", holdMs / 1000f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = holdMs.toFloat(),
                    onValueChange = {
                        holdMs = (it / 100f).roundToInt() * 100
                        settings.fastScrollHoldMs = holdMs
                    },
                    valueRange = AppSettings.MIN_HOLD_MS.toFloat()..AppSettings.MAX_HOLD_MS.toFloat(),
                    steps = 16,
                    enabled = fastScroll,
                )
                Text(
                    "How long to hold before fast-scroll starts. Shorter is quicker; longer avoids triggering it by accident.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(
                    onClick = {
                        holdMs = AppSettings.DEFAULT_HOLD_MS
                        settings.fastScrollHoldMs = holdMs
                    },
                    enabled = fastScroll && holdMs != AppSettings.DEFAULT_HOLD_MS,
                ) { Text("Reset to 1.0 s") }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Discover")

            SwitchRow(
                title = "Suggested manga",
                description = "Show similar manga on your shelf and on series pages, based on titles you read. " +
                    "Titles are sent to AniList to find them.",
                checked = suggestions,
                onChange = { settings.setShowSuggestions(it) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
