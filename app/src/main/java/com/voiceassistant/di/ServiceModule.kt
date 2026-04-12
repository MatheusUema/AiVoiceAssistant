package com.voiceassistant.di

import com.voiceassistant.ai_cloud.model.CloudModelConfig
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_cloud.service.FirebaseCloudInferenceService
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.ai_local.service.MediaPipeLocalInferenceService
import com.voiceassistant.feature_voice.service.AndroidSpeechToTextService
import com.voiceassistant.feature_voice.service.AndroidTextToSpeechService
import com.voiceassistant.feature_voice.service.SpeechToTextService
import com.voiceassistant.feature_voice.service.TextToSpeechService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceModule {

    @Binds
    @Singleton
    abstract fun bindSpeechToText(impl: AndroidSpeechToTextService): SpeechToTextService

    @Binds
    @Singleton
    abstract fun bindTextToSpeech(impl: AndroidTextToSpeechService): TextToSpeechService

    @Binds
    @Singleton
    abstract fun bindLocalInference(impl: MediaPipeLocalInferenceService): LocalInferenceService

    @Binds
    @Singleton
    abstract fun bindCloudInference(impl: FirebaseCloudInferenceService): CloudInferenceService

    companion object {
        /**
         * Caminho do modelo local: [LocalModelConfig.DEFAULT_MODEL_ASSET_PATH] -> ficheiro em
         * `app/src/main/assets/` com esse caminho relativo. Para outro modelo, sobrescreve aqui, ex.:
         * `LocalModelConfig(modelAssetPath = "models/gemma3-1b-it-int8.task")`.
         */
        @Provides
        fun provideLocalModelConfig(): LocalModelConfig = LocalModelConfig(
            modelAssetPath = LocalModelConfig.DEFAULT_MODEL_ASSET_PATH
        )

        @Provides
        fun provideCloudModelConfig(): CloudModelConfig = CloudModelConfig()
    }
}
