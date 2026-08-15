package com.voiceassistant.llama

/** Convenção do projeto: -1 significa "métrica indisponível" (igual a `confidence`). */
const val LLAMA_UNAVAILABLE: Double = -1.0

/**
 * Parâmetros de runtime + sampling de uma sessão llama.cpp.
 *
 * Os defaults de sampling replicam os do `LocalModelConfig` que o MediaPipe usava,
 * para que a troca de runtime não se confunda com troca de sampling na comparação.
 */
data class LlamaParams(
    /** Janela de contexto. Limitada ao `n_ctx_train` do modelo pelo lado nativo. */
    val contextSize: Int = 2048,
    /** 0 = heurística (núcleos - 2, entre 2 e 4). */
    val threads: Int = 0,
    val batchSize: Int = 256,
    val maxTokens: Int = 256,
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Float = 0.85f,
    /** 0 = aleatório; qualquer outro valor fixa a seed (reprodutibilidade nos blocos). */
    val seed: Int = 42,

    /**
     * Flash Attention. **Explícito de propósito** — o llama.cpp tem um modo AUTO que
     * decide em runtime, e uma decisão diferente entre aparelhos ou execuções quebraria
     * a comparabilidade exigida pelo protocolo. Ligado por padrão porque é o que o AUTO
     * escolheu nos aparelhos-alvo; desligar é uma condição a ser medida, não um acidente.
     */
    val flashAttention: Boolean = true
)

/** Ficha do modelo carregado — alimenta a tabela `model_load_log` (Fase 3). */
data class LlamaModelInfo(
    val description: String,
    val sizeBytes: Long,
    val contextSize: Int,
    val backends: String
)

/**
 * Métricas de uma geração, direto do `llama_perf_context` + relógio da ponte.
 * Cobre H2 (TTFT, ingestão vs geração) e H3 (tokens/s) do plano de hardware.
 */
data class LlamaStats(
    /** Tempo até o primeiro token, medido do início de `generate` (inclui o prefill). */
    val ttftMs: Double = LLAMA_UNAVAILABLE,
    /** `t_p_eval_ms`: ingestão do prompt. */
    val prefillMs: Double = LLAMA_UNAVAILABLE,
    /** `t_eval_ms`: geração token-a-token. */
    val decodeMs: Double = LLAMA_UNAVAILABLE,
    val totalMs: Double = LLAMA_UNAVAILABLE,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0
) {
    val promptTokensPerSec: Double
        get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else LLAMA_UNAVAILABLE

    val generatedTokensPerSec: Double
        get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else LLAMA_UNAVAILABLE

    internal companion object {
        fun fromArray(values: DoubleArray?): LlamaStats {
            if (values == null || values.size < 6) return LlamaStats()
            return LlamaStats(
                ttftMs = values[0],
                prefillMs = values[1],
                decodeMs = values[2],
                totalMs = values[3],
                promptTokens = values[4].toInt(),
                generatedTokens = values[5].toInt()
            )
        }
    }
}

/** Resultado de uma geração: texto (contrato atual) + telemetria em paralelo. */
data class LlamaGeneration(
    val text: String,
    val stats: LlamaStats
)

/**
 * Resultado do carregamento.
 *
 * [Failure] existe porque, na pesquisa, um modelo que não cabe no aparelho é um
 * **resultado a registrar** — não uma exceção a engolir. É o caso esperado do
 * Device 2 (4 GB de RAM).
 */
sealed interface LlamaLoadResult {
    data class Success(val info: LlamaModelInfo, val loadMs: Long) : LlamaLoadResult
    data class Failure(val reason: String, val loadMs: Long) : LlamaLoadResult
}
