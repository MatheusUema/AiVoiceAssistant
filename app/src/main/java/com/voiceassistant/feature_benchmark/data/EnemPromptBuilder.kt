package com.voiceassistant.feature_benchmark.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monta o prompt de múltipla escolha e interpreta a resposta.
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

    /**
     * Extrai a letra escolhida. Aceita "C", "C)", "Resposta: C", "**C**" e afins.
     *
     * @return a letra em maiúscula, ou null se a resposta não contiver nenhuma —
     *   distinguir "errou" de "não respondeu" importa: um modelo truncado antes de
     *   emitir a letra não é a mesma coisa que um modelo que escolheu errado.
     */
    fun parseAnswer(raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null

        // Primeira letra A–E isolada (não no meio de uma palavra).
        return ANSWER_PATTERN.find(cleaned)?.groupValues?.getOrNull(1)?.uppercase()
    }

    private companion object {
        /**
         * Sem pedir justificativa: o protocolo do artigo 1 usa `n_predict=4`, o que só
         * dá espaço para a letra. Pedir raciocínio aqui mudaria o custo medido e a
         * acurácia ao mesmo tempo, confundindo as duas coisas.
         */
        const val INSTRUCTION =
            "Responda à questão de múltipla escolha abaixo. " +
                "Responda APENAS com a letra da alternativa correta (A, B, C, D ou E)."

        val ANSWER_PATTERN = Regex("""\b([A-Ea-e])\b""")
    }
}
