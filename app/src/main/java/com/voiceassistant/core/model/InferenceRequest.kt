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
    val tutorMode: TutorMode = TutorMode.EXPLAIN,

    /**
     * Envia [prompt] ao modelo **exatamente como está**, sem o enquadramento do
     * TutorPromptBuilder.
     *
     * Existe para a bateria de medição: as questões do ENEM já vêm com instrução e
     * alternativas no formato do artigo 1, e embrulhá-las em instrução pedagógica mudaria
     * o prompt — e portanto a acurácia e o custo de prefill — tornando os números
     * incomparáveis com os medidos no computador.
     */
    val rawPrompt: Boolean = false,

    /** Bloco de medição a que esta inferência pertence (H5). Null fora do modo teste. */
    val blockId: String? = null,

    /** k-ésima repetição desta questão dentro do bloco. Null fora do modo teste. */
    val runIndex: Int? = null
)

enum class PromptComplexity {
    SIMPLE,
    MODERATE,
    COMPLEX
}
