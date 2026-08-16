package com.voiceassistant.feature_benchmark.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monta o prompt de múltipla escolha.
 *
 * Não interpreta a resposta: qual alternativa o modelo escolheu é decidido lendo o
 * texto, na classificação manual do `answers.csv`.
 *
 * Deliberadamente **igual ao protocolo do artigo 1**: pedir só a letra. Mudar o formato
 * aqui tornaria a acurácia medida no aparelho incomparável com a já medida no
 * computador — e é justamente essa comparação que isola o efeito do hardware.
 */
@Singleton
class EnemPromptBuilder @Inject constructor() {

    /**
     * @param includeDescription inclui a descrição da imagem, quando existe. Sem ela,
     *   itens ilustrados ficam sem enunciado completo e a perda de acurácia seria
     *   creditada ao aparelho.
     */
    fun build(question: EnemQuestion, includeDescription: Boolean = true): String = buildString {
        append(INSTRUCTION).append("\n\n")

        if (includeDescription && !question.description.isNullOrBlank()) {
            append(question.description.trim()).append("\n\n")
        }

        append(question.statement.replace(EnemQuestion.IMAGE_PLACEHOLDER, "").trim()).append("\n\n")

        question.alternatives.forEachIndexed { index, alternative ->
            EnemQuestion.LETTERS.getOrNull(index)?.let { letter ->
                append(letter).append(") ").append(alternative.trim()).append('\n')
            }
        }

        append("\nResposta:")
    }

    private companion object {
        /**
         * O modelo **responde livremente** e a alternativa escolhida é determinada
         * depois, analisando a resposta — é o protocolo do artigo 1, cada questão numa
         * conversa nova.
         *
         * Não é detalhe de redação: sufocar a resposta num teto curto de tokens mede
         * truncamento, não acurácia. Medido no Device 1, o Gemma 4 E2B gastou 161 tokens
         * raciocinando e só 3 respondendo antes de ser cortado — com teto apertado a
         * acurácia de um modelo que raciocina sai zero por construção, e a diferença
         * entre modelos vira artefato do teto.
         *
         * Pedir a letra ao final é o que torna a resposta classificável sem ambiguidade,
         * pela extração automática e pela conferência manual.
         */
        const val INSTRUCTION =
            "Responda à questão de múltipla escolha abaixo. " +
                "Explique seu raciocínio e termine indicando a alternativa correta " +
                "no formato \"Resposta: X\", onde X é A, B, C, D ou E."
    }
}
