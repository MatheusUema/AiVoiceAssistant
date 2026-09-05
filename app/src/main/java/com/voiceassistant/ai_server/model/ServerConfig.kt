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

    /**
     * Timeout de leitura da resposta.
     *
     * Subiu de 30 s para 120 s junto com `maxTokens` (512 → 1024): com o teto de tokens
     * dobrado, uma geração longa passa de 30 s e seria cancelada, e o dado medido seria
     * o teto configurado em vez do servidor. Medido no Qwen2.5-7B em loopback, a questão
     * mais lenta levou 17,7 s — 30 s não deixariam margem para a rede nem para a cauda.
     * É a mesma correção já aplicada ao tier local, onde 180 s cancelavam questões
     * legítimas no Redmi Note 8.
     */
    val timeoutMs: Long = 120_000,

    /** Timeout de conexão — curto, para falhar rápido quando o servidor está fora. */
    val connectTimeoutMs: Long = 5_000,

    // ── Amostragem ───────────────────────────────────────────────────────────
    //
    // ALINHADOS AO TIER LOCAL (`LocalModelConfig`) em 2026-09-05. Antes eram
    // 512 / 0.7f / 0.9f / 40 — os defaults de conversa do llama.cpp.
    //
    // Por quê: o estudo de elasticidade compara os dois tiers na MESMA tarefa, e
    // parâmetros diferentes de amostragem medem regimes de geração diferentes, não
    // hardware. Com `maxTokens=512` metade das respostas do protocolo de resposta livre
    // seria truncada (a mediana medida é de 272 tokens no 1.5B e 354 no 7B, com cauda
    // passando de 1.000), e `temperature=0.7` produziria outro texto para a mesma
    // questão. A diferença apareceria como "o servidor é pior", quando seria só outra
    // configuração.
    //
    // Consequência fora do benchmark: o tier servidor passa a responder de forma mais
    // determinística e mais longa no app. É o comportamento desejado para um tutor que
    // explica o raciocínio, e é o mesmo do tier local — a transição entre tiers deixa de
    // mudar o estilo da resposta.

    /** Máximo de tokens gerados (`n_predict`). Igual a `LocalModelConfig.maxTokens`. */
    val maxTokens: Int = 1024,

    /** Temperatura de sampling. Igual a `LocalModelConfig.temperature`. */
    val temperature: Float = 0.2f,

    /** Nucleus sampling. Igual a `LocalModelConfig.topP`. */
    val topP: Float = 0.85f,

    /** Top-k sampling. Igual a `LocalModelConfig.topK`. */
    val topK: Int = 20,

    /**
     * Seed do amostrador, espelhando `LocalModelConfig.randomSeed`. 0 = aleatorio.
     *
     * Ate 2026-09-05 o tier servidor nao enviava seed alguma, entao o `llama-server`
     * sorteava uma por requisicao: a mesma questao produzia textos diferentes a cada
     * chamada. Isso impedia comparar o tier servidor com as rodadas de referencia e
     * tornava o determinismo -- que vale dentro de cada maquina e sustenta k=1 -- falso
     * neste tier.
     */
    val randomSeed: Int = 42,

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
