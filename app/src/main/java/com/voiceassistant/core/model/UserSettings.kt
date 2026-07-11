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
    val ttsSpeechRate: Float = 1.0f,

    /**
     * Liga o tier servidor (llama.cpp na LAN). Fonte de verdade em runtime do
     * roteamento para o servidor — o InferenceRouter só considera o servidor (e só
     * paga o health-check) quando isto é true. Default false: comportamento idêntico
     * a só local/cloud. Ativado por escola que tenha `llama-server` na rede.
     */
    val serverTierEnabled: Boolean = false,

    /**
     * URL base do `llama-server`, ex.: "http://192.168.1.100:8080". Em branco, o
     * InferenceRouter usa a URL padrão de [com.voiceassistant.ai_server.model.ServerConfig].
     */
    val serverBaseUrl: String = ""
)
