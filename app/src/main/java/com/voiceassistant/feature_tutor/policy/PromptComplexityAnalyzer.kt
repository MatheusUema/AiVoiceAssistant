package com.voiceassistant.feature_tutor.policy

import com.voiceassistant.core.model.PromptComplexity
import javax.inject.Inject

/**
 * Analisa heuristicamente a complexidade de um prompt para orientar o InferenceRouter.
 * Pode ser evoluído para usar um classificador de ML leve no futuro.
 */
class PromptComplexityAnalyzer @Inject constructor() {

    /**
     * Classifica a complexidade baseando-se em:
     * - Comprimento do texto
     * - Presença de palavras-chave que indicam raciocínio complexo
     * - Número de sentenças / perguntas aninhadas
     */
    fun analyze(prompt: String): PromptComplexity {
        val wordCount = prompt.trim().split(WHITESPACE_REGEX).size
        val lowerPrompt = prompt.lowercase()
        val hasComplexKeywords = COMPLEX_KEYWORD_PATTERNS.any { it.containsMatchIn(lowerPrompt) }
        val questionCount = prompt.count { it == '?' }

        return when {
            wordCount > COMPLEX_WORD_THRESHOLD || hasComplexKeywords || questionCount >= 3 ->
                PromptComplexity.COMPLEX

            wordCount > MODERATE_WORD_THRESHOLD || questionCount == 2 ->
                PromptComplexity.MODERATE

            else -> PromptComplexity.SIMPLE
        }
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        private const val MODERATE_WORD_THRESHOLD = 20
        private const val COMPLEX_WORD_THRESHOLD = 50

        /**
         * Palavras que indicam necessidade de raciocínio mais elaborado.
         * Usam \b (word boundary) para evitar falso-positivos em substrings
         * (ex.: "tese" dentro de "Fotossíntese").
         */
        private val COMPLEX_KEYWORD_PATTERNS = listOf(
            "explique", "compare", "analise", "demonstre", "prove",
            "diferencie", "calcule", "resolva", "derive", "integre",
            "essay", "redação", "dissertação", "\\btese\\b", "argumente",
            "por que", "como funciona", "quais são as causas",
            "qual a diferença entre", "explica detalhadamente"
        ).map { Regex(it) }
    }
}
