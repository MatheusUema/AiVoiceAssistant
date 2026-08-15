package com.voiceassistant.feature_benchmark.data

/**
 * Uma questão do ENEM do conjunto `maritaca_enem_irt.csv` (540 itens, 135 por área).
 *
 * O mesmo conjunto usado no artigo 1 — o que permite comparar a acurácia obtida no
 * aparelho com a já medida no computador, isolando o efeito do hardware.
 */
data class EnemQuestion(
    val id: String,
    val year: Int,
    /** Área: LC (linguagens), CH (humanas), CN (natureza), MT (matemática). */
    val area: String,
    val statement: String,
    /** Alternativas na ordem A..E. */
    val alternatives: List<String>,
    /** Letra correta (A..E). */
    val label: String,
    /** Dificuldade estimada por TRI — usada para estratificar a amostra. */
    val difficultyScore: Double,
    /**
     * Descrição textual da imagem, quando o item depende de uma.
     * Sem ela, itens ilustrados seriam impossíveis para um modelo só de texto — e a
     * queda de acurácia seria atribuída ao aparelho em vez de à falta do enunciado.
     */
    val description: String? = null
) {
    /** True se o item depende de imagem e não tem descrição — inelegível para texto puro. */
    val needsImageButHasNoDescription: Boolean
        get() = statement.contains(IMAGE_PLACEHOLDER) && description.isNullOrBlank()

    companion object {
        const val IMAGE_PLACEHOLDER = "[[placeholder]]"
        val LETTERS = listOf("A", "B", "C", "D", "E")
    }
}
