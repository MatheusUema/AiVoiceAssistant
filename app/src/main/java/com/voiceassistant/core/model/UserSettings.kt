package com.voiceassistant.core.model

/**
 * Preferências persistidas do usuário via DataStore.
 */
data class UserSettings(
    /**
     * Quando true, o InferenceRouter prioriza inferência local sempre que possível,
     * evitando enviar dados para servidores externos.
     */
    val privacyModeEnabled: Boolean = false,

    /** Habilita ou desabilita o Text-to-Speech para respostas do assistente */
    val ttsEnabled: Boolean = true,

    /** Idioma preferido para STT/TTS (ex: "pt-BR", "en-US") */
    val preferredLanguage: String = "pt-BR",

    /** Velocidade de fala do TTS (0.5 a 2.0) */
    val ttsSpeechRate: Float = 1.0f
)
