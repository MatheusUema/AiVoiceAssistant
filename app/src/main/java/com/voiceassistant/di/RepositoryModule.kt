package com.voiceassistant.di

import com.voiceassistant.data.repository.ChatRepositoryImpl
import com.voiceassistant.data.repository.SettingsRepositoryImpl
import com.voiceassistant.domain.repository.ChatRepository
import com.voiceassistant.domain.repository.InferenceRepository
import com.voiceassistant.domain.repository.SettingsRepository
import com.voiceassistant.feature_tutor.policy.InferenceRouter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Liga as interfaces de domínio às suas implementações concretas.
 * Usar @Binds em vez de @Provides é mais eficiente — sem overhead de factory.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /**
     * O InferenceRouter é a implementação de InferenceRepository.
     * A UI nunca conhece o InferenceRouter diretamente.
     */
    @Binds
    @Singleton
    abstract fun bindInferenceRepository(router: InferenceRouter): InferenceRepository
}
