package com.voiceassistant.core.model

/**
 * Indica qual motor de inferência gerou uma resposta.
 * Exibido na UI para transparência ao usuário.
 */
enum class InferenceSource {
    /** Resposta gerada pelo modelo local via MediaPipe LLM Inference */
    LOCAL,

    /**
     * Resposta gerada pelo tier servidor (llama.cpp `llama-server` na rede local).
     * Único tier que expõe logprobs → `InferenceResult.confidence` é significativo.
     */
    SERVER,

    /** Resposta gerada pela API do Firebase AI Logic (Gemini) */
    CLOUD,

    /**
     * Resposta gerada pela nuvem após falha da inferência local.
     * Sinaliza ao usuário que o modelo local estava indisponível.
     */
    FALLBACK
}
