package com.voiceassistant.ai_cloud.model

/**
 * Configuração do modelo cloud via Firebase AI Logic (Gemini).
 *
 * Estes parâmetros são injetados no [FirebaseCloudInferenceService] via Hilt
 * e podem ser alterados em runtime sem recompilar.
 */
data class CloudModelConfig(
    /**
     * Nome do modelo Gemini.
     * Opções comuns:
     *  - "gemini-2.0-flash-lite" → leve, quota gratuita disponível (recomendado)
     *  - "gemini-2.0-flash"      → mais capaz, mas pode exigir billing
     *  - "gemini-1.5-flash"      → geração anterior, quota gratuita estável
     *  - "gemini-1.5-pro"        → melhor qualidade, mais lento
     */
    val modelName: String = "gemini-2.0-flash-lite",

    /** Máximo de tokens na resposta gerada */
    val maxOutputTokens: Int = 1024,

    /** Temperatura (0.0 = determinístico, 1.0 = criativo) */
    val temperature: Float = 0.7f,

    /** Nucleus sampling — filtra tokens cuja probabilidade acumulada ultrapassa topP */
    val topP: Float = 0.95f,

    /**
     * Nível de bloqueio de conteúdo sensível.
     *  - NONE          → desabilita filtros (não recomendado para educação)
     *  - ONLY_HIGH     → bloqueia apenas conteúdo claramente nocivo
     *  - MEDIUM_AND_ABOVE → bloqueio moderado (padrão para apps educacionais)
     *  - LOW_AND_ABOVE → mais restritivo
     */
    val safetyBlockThreshold: SafetyBlockThreshold = SafetyBlockThreshold.MEDIUM_AND_ABOVE,

    /**
     * Backend da API Gemini:
     *  - GOOGLE_AI   → Gemini Developer API (plano Spark, sem billing obrigatório)
     *  - VERTEX_AI   → Vertex AI Gemini API (requer billing / plano Blaze)
     */
    val backend: CloudBackend = CloudBackend.GOOGLE_AI
)

/**
 * Mapeamento para HarmBlockThreshold do Firebase AI SDK.
 * Mantém o domain model desacoplado da API do Firebase.
 */
enum class SafetyBlockThreshold {
    NONE,
    ONLY_HIGH,
    MEDIUM_AND_ABOVE,
    LOW_AND_ABOVE
}

enum class CloudBackend {
    GOOGLE_AI,
    VERTEX_AI
}
