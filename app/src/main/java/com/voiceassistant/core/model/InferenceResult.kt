package com.voiceassistant.core.model

/**
 * Resultado de uma inferência, independentemente de ter vindo do modelo local ou da nuvem.
 */
data class InferenceResult(
    val text: String,
    val source: InferenceSource,
    val latencyMs: Long
)
