package com.voiceassistant.core.model

/**
 * Métricas de hardware de **uma** inferência, preenchidas pelo tier que a executou.
 *
 * Cobre as métricas prioritárias on-device do plano (doc 06 §2): H2 (TTFT e ingestão vs
 * geração), H3 (tokens/s) e H4 (pico de RAM do processo). H1 (latência total) e H8
 * (conectividade) já vivem no `InferenceResult`/`routing_log`; H6/H7 são por carregamento
 * e vão em `model_load_log`, não aqui.
 *
 * Convenção do projeto: **-1 significa indisponível** — a mesma de `confidence`. Um tier
 * que não instrumenta (cloud, ou o MediaPipe antigo) simplesmente não devolve telemetria.
 */
data class InferenceTelemetry(
    /** Modelo que **de fato** respondeu — não o configurado. Ver [modelId] no log. */
    val modelId: String? = null,

    /** Motor de inferência: "llamacpp", "mediapipe", "llama-server", "gemini". */
    val runtime: String? = null,

    /** Tokens do prompt após o chat template (`n_p_eval`). */
    val promptTokens: Int = UNAVAILABLE_INT,

    /** Tokens gerados (`n_eval`), incluindo raciocínio quando o modelo raciocina. */
    val generatedTokens: Int = UNAVAILABLE_INT,

    /**
     * Tokens gastos no canal de raciocínio, quando o modelo tem um (Gemma 4).
     * Separar isso é o que torna tokens/s comparável entre um modelo que raciocina
     * e um que não raciocina — sem isso a matriz compara grandezas diferentes.
     */
    val reasoningTokens: Int = UNAVAILABLE_INT,

    /** Tempo até o primeiro token, do início da geração (inclui a ingestão). */
    val ttftMs: Double = UNAVAILABLE,

    /** Ingestão do prompt — `t_p_eval_ms` do llama.cpp (prefill). */
    val ingestionMs: Double = UNAVAILABLE,

    /** Geração token-a-token — `t_eval_ms` do llama.cpp (decode). */
    val generationMs: Double = UNAVAILABLE,

    /** Pico de PSS do processo durante a inferência, em MB (H4). */
    val peakProcessRamMb: Long = UNAVAILABLE_LONG,

    /** Threads de inferência usadas — parte das condições de execução. */
    val threads: Int = UNAVAILABLE_INT,

    /** Backends ggml ativos ("CPU", "Vulkan,CPU"). */
    val backends: String? = null,

    /**
     * True se a geração foi cortada pelo teto de tokens em vez de terminar sozinha.
     *
     * Uma resposta truncada mede o teto configurado, não a capacidade do aparelho —
     * misturá-la com as demais enviesa acurácia e latência ao mesmo tempo. Com modelos
     * que raciocinam isso é comum, então precisa ser filtrável na análise.
     */
    val truncated: Boolean = false,

    /**
     * Por que a geração terminou: `END_OF_GENERATION`, `MAX_TOKENS` ou `TIMEOUT`.
     *
     * `TIMEOUT` é o caso que mais interessa ao estudo: significa que o aparelho não
     * sustentou o modelo dentro de um tempo que um aluno aceitaria esperar.
     */
    val stopReason: String? = null,

    /**
     * Confiança em [0,1]: média da probabilidade do token **escolhido** em cada posição
     * gerada. -1 quando o tier não expõe logprobs (cloud).
     *
     * Mesma definição do tier servidor (`ServerInferenceService.calculateConfidence`) e
     * da `app_confidence()` do artigo 1. Ter a mesma fórmula nos dois tiers é o que
     * permite comparar a confiança medida no aparelho com a do `llama-server` — com
     * definições diferentes, a comparação mediria a fórmula, não os modelos.
     */
    val confidence: Float = UNAVAILABLE_FLOAT
) {
    /** Tokens de prompt por segundo (velocidade de ingestão). */
    val promptTokensPerSec: Double
        get() = if (ingestionMs > 0 && promptTokens > 0) {
            promptTokens * 1000.0 / ingestionMs
        } else UNAVAILABLE

    /** Tokens gerados por segundo (velocidade de geração). */
    val generatedTokensPerSec: Double
        get() = if (generationMs > 0 && generatedTokens > 0) {
            generatedTokens * 1000.0 / generationMs
        } else UNAVAILABLE

    companion object {
        const val UNAVAILABLE: Double = -1.0
        const val UNAVAILABLE_INT: Int = -1
        const val UNAVAILABLE_LONG: Long = -1L
        const val UNAVAILABLE_FLOAT: Float = -1f
    }
}
