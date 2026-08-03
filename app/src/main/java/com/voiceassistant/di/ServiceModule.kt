package com.voiceassistant.di

import com.voiceassistant.ai_cloud.model.CloudModelConfig
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_cloud.service.FirebaseCloudInferenceService
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.service.LlamaCppLocalInferenceService
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.ai_server.model.ServerConfig
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

    /**
     * Tier local roda em llama.cpp/JNI (módulo `:llama`).
     * Para comparar paridade com o runtime antigo, troque por
     * `MediaPipeLocalInferenceService` — a interface é a mesma (doc 06 §1.4).
     */
    @Binds
    @Singleton
    abstract fun bindLocalInference(impl: LlamaCppLocalInferenceService): LocalInferenceService

    @Binds
    @Singleton
    abstract fun bindCloudInference(impl: FirebaseCloudInferenceService): CloudInferenceService

    companion object {
        /**
         * Modelo local do estudo: o mesmo GGUF `Q4_K_M` nos 3 aparelhos, com um modelo
         * menor de fallback para quando o primário não couber (Device 2, 4 GB).
         * O arquivo físico vai em `app/src/main/assets/models/` — ver o README de lá.
         *
         * Para validar só o build da ponte JNI, troque `primary` por
         * `LocalModelConfig.SMOKE_TEST_TINY`.
         */
        @Provides
        fun provideLocalModelConfig(): LocalModelConfig = LocalModelConfig(
            primary = LocalModelConfig.GEMMA_4_E2B_Q4_K_M,
            fallback = LocalModelConfig.GEMMA_3_1B_Q4_K_M
        )

        @Provides
        fun provideCloudModelConfig(): CloudModelConfig = CloudModelConfig()

        /**
         * Defaults do tier servidor (llama.cpp): timeouts, thresholds de confiança,
         * parâmetros de sampling e a URL de fallback. O liga/desliga e a URL efetivos
         * em runtime vêm de `UserSettings` (`serverTierEnabled` / `serverBaseUrl`),
         * configuráveis por escola sem recompilar. O mesmo singleton é injetado no
         * InferenceRouter e no ServerInferenceService.
         */
        @Provides
        @Singleton
        fun provideServerConfig(): ServerConfig = ServerConfig()
    }
}
