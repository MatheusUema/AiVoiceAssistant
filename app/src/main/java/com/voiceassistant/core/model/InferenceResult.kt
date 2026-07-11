package com.voiceassistant.core.model

/**
 * Resultado de uma inferência, independentemente de ter vindo do modelo local ou da nuvem.
 */
data class InferenceResult(
    val text: String,
    val source: InferenceSource,
    val latencyMs: Long,
    /**
     * Confiança do modelo na resposta, em [0, 1], derivada dos logprobs do tier
     * servidor. Vale [CONFIDENCE_UNAVAILABLE] (-1) quando não há sinal de confiança
     * (tiers local e cloud, ou servidor sem logprobs).
     */
    val confidence: Float = CONFIDENCE_UNAVAILABLE
) {
    companion object {
        /** Sentinela: confiança não disponível para esta origem. */
        const val CONFIDENCE_UNAVAILABLE = -1f
    }
}
