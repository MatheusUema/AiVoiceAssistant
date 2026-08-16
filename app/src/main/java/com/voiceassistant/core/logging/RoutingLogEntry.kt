package com.voiceassistant.core.logging

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Registro de pesquisa de uma interação de roteamento (uma linha por pergunta).
 *
 * Alimenta a análise da hipótese central do projeto: comparar roteamento com
 * confiança (tier servidor, via logprobs) vs. heurística (tier local), calibrar
 * thresholds com dados reais e verificar se as decisões de rota estão corretas.
 *
 * Campos derivados de enums são persistidos por `.name` (String) para manter a
 * tabela estável mesmo se a ordem dos enums mudar.
 */
@Entity(tableName = "routing_log")
data class RoutingLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val questionText: String,
    /** Complexidade estimada pelo pré-filtro (PromptComplexity.name). */
    val complexityPreFilter: String,
    /** Decisão de rota (RoutingDecision.name). */
    val routeDecision: String,
    /** Confiança em [0,1], ou -1 quando indisponível. */
    val confidenceScore: Float,
    /** Como a confiança foi obtida: "logprobs_mean" | "heuristic" | "none". */
    val confidenceMethod: String,
    /** Tier que efetivamente respondeu (InferenceSource.name). */
    val finalTier: String,
    /** Modo pedagógico aplicado (TutorMode.name). */
    val pedagogicalMode: String,
    val latencyMs: Long,
    /** Identificador do modelo usado, ex.: "gemma-4-e2b-it-q4_k_m" | "gemini-2.0-flash-lite". */
    val modelId: String,
    /** Conectividade no momento: "offline" | "lan" | "internet". */
    val connectivity: String,

    // ── Métricas de hardware (doc 06 §2/§3.1) ────────────────────────────────
    // Convenção do projeto: -1 = indisponível. Tiers que não instrumentam (cloud)
    // deixam tudo em -1 em vez de zerar, para não virar "custo zero" na análise.

    /** Aparelho que produziu esta linha — FK para `device_profile`. */
    val deviceId: String = "",

    /** Motor de inferência: "llamacpp" | "mediapipe" | "llama-server" | "gemini". */
    val runtime: String? = null,

    /** H3: tokens do prompt após o chat template. */
    val promptTokens: Int = UNAVAILABLE_INT,
    /** H3: tokens gerados, **incluindo** raciocínio. */
    val generatedTokens: Int = UNAVAILABLE_INT,
    /**
     * Tokens gastos no canal de raciocínio (Gemma 4 e afins).
     * Separado porque, sem isso, tokens/s de um modelo que raciocina não é comparável
     * com o de um que não raciocina — e a matriz do estudo mistura os dois.
     */
    val reasoningTokens: Int = UNAVAILABLE_INT,

    /** H2: tempo até o primeiro token (ms). */
    val ttftMs: Double = UNAVAILABLE_DOUBLE,
    /** H2: ingestão do prompt — prefill (ms). */
    val ingestionMs: Double = UNAVAILABLE_DOUBLE,
    /** H2: geração token-a-token — decode (ms). */
    val generationMs: Double = UNAVAILABLE_DOUBLE,
    /** H3: tokens gerados por segundo. */
    val tokensPerSec: Double = UNAVAILABLE_DOUBLE,

    /** H4: pico de PSS do processo durante esta inferência (MB). */
    val peakProcessRamMb: Long = UNAVAILABLE_LONG,

    /** Threads de inferência em uso. */
    val threads: Int = UNAVAILABLE_INT,
    /** Backends ggml ativos ("CPU", "Vulkan,CPU"). */
    val backends: String? = null,

    /**
     * Por que a geração terminou: "END_OF_GENERATION" | "MAX_TOKENS" | "TIMEOUT".
     * `TIMEOUT` é resultado sobre o aparelho, não erro — precisa ser filtrável.
     */
    val stopReason: String? = null,
    /** True se a resposta não saiu inteira (teto de tokens ou timeout). */
    val truncated: Boolean = false,

    // ── Modo teste (Fase 4) ──────────────────────────────────────────────────

    /** Bloco de medição a que esta linha pertence — junta com a energia (H5). */
    val blockId: String? = null,
    /** k-ésima repetição da mesma questão dentro do bloco. */
    val runIndex: Int? = null
) {
    companion object {
        const val UNAVAILABLE_INT: Int = -1
        const val UNAVAILABLE_LONG: Long = -1L
        const val UNAVAILABLE_DOUBLE: Double = -1.0
    }
}
