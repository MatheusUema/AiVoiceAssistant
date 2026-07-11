package com.voiceassistant.ai_server.model

/**
 * Configuração do tier servidor (llama.cpp `llama-server` na rede local).
 *
 * Este tier expõe logprobs via o endpoint `/completion` (`n_probs > 0`), permitindo
 * calcular um sinal de confiança contínuo — o principal diferencial em relação ao
 * tier local (MediaPipe), que só oferece heurísticas de output.
 *
 * Injetado no [com.voiceassistant.ai_server.service.ServerInferenceService] e no
 * InferenceRouter via Hilt. Guarda os **defaults** do tier (timeouts, thresholds,
 * parâmetros de sampling e uma URL de fallback). O liga/desliga e a URL efetivos em
 * runtime vêm de [com.voiceassistant.core.model.UserSettings] (`serverTierEnabled` /
 * `serverBaseUrl`), permitindo configuração por escola sem recompilar.
 */
data class ServerConfig(
    /**
     * URL base padrão do `llama-server`, usada quando `UserSettings.serverBaseUrl`
     * está em branco. Ex.: `http://192.168.1.100:8080`. A barra final é opcional —
     * [ServerInferenceService] a normaliza antes de passar ao Retrofit (que exige
     * `baseUrl` terminando em `/`).
     */
    val baseUrl: String = "http://192.168.1.100:8080",

    /** Timeout de leitura da resposta (geração pode ser lenta em modelos maiores). */
    val timeoutMs: Long = 30_000,

    /** Timeout de conexão — curto, para falhar rápido quando o servidor está fora. */
    val connectTimeoutMs: Long = 5_000,

    /** Máximo de tokens gerados (`n_predict`). */
    val maxTokens: Int = 512,

    /** Temperatura de sampling. */
    val temperature: Float = 0.7f,

    /** Nucleus sampling. */
    val topP: Float = 0.9f,

    /** Top-k sampling. */
    val topK: Int = 40,

    /**
     * Quantidade de probabilidades top-k retornadas por token (`n_probs`).
     * Precisa ser > 0 para o servidor devolver `completion_probabilities`.
     */
    val nProbs: Int = 5,

    /** Confiança acima disto → entrega direta (direct-answer). */
    val confidenceThresholdHigh: Float = 0.7f,

    /** Confiança abaixo disto → escalona para cloud. */
    val confidenceThresholdLow: Float = 0.3f
)
