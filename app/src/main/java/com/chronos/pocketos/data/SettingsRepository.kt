package com.chronos.pocketos.data

import android.content.Context
import com.chronos.pocketos.core.settings.SettingsCodec
import com.chronos.pocketos.core.settings.ShellSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persists [ShellSettings] as JSON in app-private SharedPreferences. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(SettingsCodec.decode(prefs.getString(KEY, null)))
    val settings: StateFlow<ShellSettings> = _settings.asStateFlow()

    fun update(transform: (ShellSettings) -> ShellSettings) {
        val next = transform(_settings.value).sanitized()
        _settings.value = next
        prefs.edit().putString(KEY, SettingsCodec.encode(next)).apply()
    }

    fun resetToDefaults() = update { ShellSettings(onboardingCompleted = it.onboardingCompleted) }

    /** Persisted Storage Access Framework tree URIs the user granted. */
    fun grantedTrees(): Set<String> = prefs.getStringSet(KEY_TREES, emptySet()) ?: emptySet()
    fun setGrantedTrees(uris: Set<String>) { prefs.edit().putStringSet(KEY_TREES, uris).apply() }

    fun loadSession(): String? = prefs.getString(KEY_SESSION, null)
    fun saveSession(json: String) { prefs.edit().putString(KEY_SESSION, json).apply() }

    fun clearAll() { prefs.edit().clear().apply(); _settings.value = ShellSettings() }

    companion object {
        const val PREFS = "pocketos_settings"
        private const val KEY = "shell_settings_v1"
        private const val KEY_TREES = "granted_trees"
        private const val KEY_SESSION = "desktop_session_v1"
    }
}
