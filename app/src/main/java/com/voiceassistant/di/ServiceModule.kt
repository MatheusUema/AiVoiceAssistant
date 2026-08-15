package com.voiceassistant.di

import com.voiceassistant.BuildConfig
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
         * Modelo local ativo, escolhido em tempo de build por `-Plocal.model=<chave>`
         * (default `gemma4-e2b`). A matriz de testes é sequencial: uma bateria completa
         * por modelo, e trocar de bateria é só recompilar com outra chave.
         *
         * O fallback (`-Plocal.model.fallback`, `none` desliga) só entra quando o
         * primário não carrega — o caso do Device 2, com 4 GB de RAM.
         */
        @Provides
        fun provideLocalModelConfig(): LocalModelConfig = LocalModelConfig(
            primary = LocalModelConfig.variantOf(BuildConfig.LOCAL_MODEL),
            fallback = BuildConfig.LOCAL_MODEL_FALLBACK
                .takeUnless { it.equals("none", ignoreCase = true) }
                ?.let { LocalModelConfig.variantOf(it) },
            maxTokens = BuildConfig.LOCAL_MAX_TOKENS
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
