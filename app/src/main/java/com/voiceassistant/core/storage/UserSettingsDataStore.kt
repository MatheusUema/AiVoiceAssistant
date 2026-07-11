package com.voiceassistant.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voiceassistant.core.model.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

/**
 * Camada de acesso às preferências do usuário via Jetpack DataStore.
 * Thread-safe, coroutine-friendly e não bloqueia a thread principal.
 */
@Singleton
open class UserSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val PRIVACY_MODE = booleanPreferencesKey("privacy_mode")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val SERVER_TIER_ENABLED = booleanPreferencesKey("server_tier_enabled")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
    }

    open val settings: Flow<UserSettings>
        get() = context.dataStore.data.map { prefs ->
            UserSettings(
                privacyModeEnabled = prefs[Keys.PRIVACY_MODE] ?: false,
                ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
                preferredLanguage = prefs[Keys.PREFERRED_LANGUAGE] ?: "pt-BR",
                ttsSpeechRate = prefs[Keys.TTS_SPEECH_RATE] ?: 1.0f,
                serverTierEnabled = prefs[Keys.SERVER_TIER_ENABLED] ?: false,
                serverBaseUrl = prefs[Keys.SERVER_BASE_URL] ?: ""
            )
        }

    suspend fun setPrivacyMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PRIVACY_MODE] = enabled }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    suspend fun setPreferredLanguage(language: String) {
        context.dataStore.edit { it[Keys.PREFERRED_LANGUAGE] = language }
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        context.dataStore.edit { it[Keys.TTS_SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setServerTierEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SERVER_TIER_ENABLED] = enabled }
    }

    suspend fun setServerBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.SERVER_BASE_URL] = url.trim() }
    }
}
