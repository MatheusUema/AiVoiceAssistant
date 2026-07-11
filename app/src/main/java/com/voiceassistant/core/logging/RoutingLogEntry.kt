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
    /** Identificador do modelo usado, ex.: "gemma3-1b-it-int4.task" | "gemini-2.0-flash-lite". */
    val modelId: String,
    /** Conectividade no momento: "offline" | "lan" | "internet". */
    val connectivity: String
)
