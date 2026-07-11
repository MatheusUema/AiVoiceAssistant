package com.voiceassistant.domain.repository

import com.voiceassistant.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

/**
 * Contrato de domínio para leitura e gravação das preferências do usuário.
 */
interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setPrivacyMode(enabled: Boolean)
    suspend fun setTtsEnabled(enabled: Boolean)
    suspend fun setPreferredLanguage(language: String)
    suspend fun setTtsSpeechRate(rate: Float)
    suspend fun setServerTierEnabled(enabled: Boolean)
    suspend fun setServerBaseUrl(url: String)
}
