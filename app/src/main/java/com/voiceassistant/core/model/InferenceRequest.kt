package com.voiceassistant.core.model

/**
 * Encapsula uma requisição de inferência enviada ao InferenceRouter.
 * Contém o prompt e metadados que influenciam a decisão de roteamento
 * e a construção do prompt final.
 */
data class InferenceRequest(
    val prompt: String,
    val sessionId: String,
    /** Histórico recente da conversa para fornecer contexto ao modelo */
    val conversationHistory: List<ChatMessage> = emptyList(),
    /** Estimativa de complexidade da pergunta (calculada pelo PromptComplexityAnalyzer) */
    val complexity: PromptComplexity = PromptComplexity.SIMPLE,
    /** Modo pedagógico que define o estilo da resposta */
    val tutorMode: TutorMode = TutorMode.EXPLAIN
)

enum class PromptComplexity {
    SIMPLE,
    MODERATE,
    COMPLEX
}
