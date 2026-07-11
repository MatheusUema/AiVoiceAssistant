package com.voiceassistant.data.repository

import com.voiceassistant.core.model.UserSettings
import com.voiceassistant.core.storage.UserSettingsDataStore
import com.voiceassistant.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Implementação concreta de [SettingsRepository] usando Jetpack DataStore.
 */
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: UserSettingsDataStore
) : SettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.settings

    override suspend fun setPrivacyMode(enabled: Boolean) =
        dataStore.setPrivacyMode(enabled)

    override suspend fun setTtsEnabled(enabled: Boolean) =
        dataStore.setTtsEnabled(enabled)

    override suspend fun setPreferredLanguage(language: String) =
        dataStore.setPreferredLanguage(language)

    override suspend fun setTtsSpeechRate(rate: Float) =
        dataStore.setTtsSpeechRate(rate)

    override suspend fun setServerTierEnabled(enabled: Boolean) =
        dataStore.setServerTierEnabled(enabled)

    override suspend fun setServerBaseUrl(url: String) =
        dataStore.setServerBaseUrl(url)
}
