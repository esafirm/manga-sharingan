package com.esafirm.sharingan.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User preferences, backed by SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Whether the long-press-and-drag fast-scroll gesture is on. */
    var fastScrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_FS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FS_ENABLED, value).apply()

    /** How long (ms) a finger must stay still before fast-scroll starts. */
    var fastScrollHoldMs: Int
        get() = prefs.getInt(KEY_FS_HOLD, DEFAULT_HOLD_MS).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        set(value) = prefs.edit().putInt(KEY_FS_HOLD, value.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)).apply()

    private val _showSuggestions = MutableStateFlow(prefs.getBoolean(KEY_SUGGEST, true))

    /** Whether similar-manga suggestions are shown (they send titles to AniList). */
    val showSuggestions: StateFlow<Boolean> = _showSuggestions

    fun setShowSuggestions(value: Boolean) {
        prefs.edit().putBoolean(KEY_SUGGEST, value).apply()
        _showSuggestions.value = value
    }

    companion object {
        const val DEFAULT_HOLD_MS = 1000
        const val MIN_HOLD_MS = 300
        const val MAX_HOLD_MS = 2000
        private const val KEY_FS_ENABLED = "fast_scroll_enabled"
        private const val KEY_FS_HOLD = "fast_scroll_hold_ms"
        private const val KEY_SUGGEST = "show_suggestions"
    }
}
