package com.voiceassistant.feature_tutor.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Trava as divergências Kotlin↔Python **uma a uma**, sem depender do fixture.
 *
 * O `StringFeatureParityTest` é o juiz final, mas ele roda em aparelho e só depois que o
 * CSV do Python chegar. Estes casos rodam na JVM a cada build e isolam exatamente o
 * comportamento que difere entre as duas linguagens — quando um deles quebra, o nome do
 * teste já diz qual armadilha foi reintroduzida, em vez de apontar uma questão do ENEM
 * cuja feature saiu 0,003 diferente.
 *
 * Cada caso aqui corresponde a uma nota na doc do [StringFeatureExtractor].
 */
class StringFeatureExtractorTest {

    private val ex = StringFeatureExtractor()

    private fun f(cru: String, alts: List<String> = ALTS, area: String? = "MT") =
        ex.extract(cru, alts, area)

    // ── contagem de caracteres ────────────────────────────────────────────────

    @Test
    fun `n_chars conta code points, nao unidades UTF-16`() {
        // U+1D465 (𝑥 matemático) ocupa 2 unidades UTF-16 mas é 1 code point.
        // `len()` do Python devolveria 1; `String.length` devolveria 2.
        val cru = "a𝑥b"
        assertEquals(3.0, f(cru)["n_chars"]!!, 0.0)
    }

    // ── dígitos ───────────────────────────────────────────────────────────────

    @Test
    fun `dens_digitos conta sobrescritos, como str isdigit do Python`() {
        // '²'.isdigit() é True no Python; Character.isDigit('²') é false.
        // "x²" → 1 dígito em 2 caracteres.
        assertEquals(0.5, f("x²")["dens_digitos"]!!, 1e-12)
    }

    @Test
    fun `dens_digitos NAO conta fracao vulgar`() {
        // '½'.isdigit() é False no Python (é isnumeric). Se a implementação usasse
        // getType == OTHER_NUMBER para pegar os sobrescritos, pegaria esta também.
        assertEquals(0.0, f("x½")["dens_digitos"]!!, 1e-12)
    }

    // ── palavras ──────────────────────────────────────────────────────────────

    @Test
    fun `n_palavras nao gera vazio inicial com espaco a esquerda`() {
        // `split(Regex("\\s+"))` devolveria ["", "a", "b"] → 3. O `str.split()` do
        // Python descarta os vazios → 2.
        assertEquals(2.0, f("  a b")["n_palavras"]!!, 0.0)
    }

    @Test
    fun `n_palavras quebra em espaco nao-separavel, como o Python`() {
        // U+00A0: `Py_UNICODE_ISSPACE` diz que é espaço; `Character.isWhitespace` diz
        // que não. Sem tratar, o literal viraria UMA palavra.
        assertEquals(2.0, f("a b")["n_palavras"]!!, 0.0)
    }

    // ── fronteira de palavra Unicode ──────────────────────────────────────────

    @Test
    fun `n_chaves respeita fronteira de palavra`() {
        // O conserto da Fase 1: "comparecer" não pode disparar "compare".
        assertEquals(0.0, f("o aluno vai comparecer")["n_chaves"]!!, 0.0)
        assertEquals(1.0, f("compare os dois textos")["n_chaves"]!!, 0.0)
    }

    @Test
    fun `n_chaves trata letra acentuada como caractere de palavra`() {
        // Este é o caso que exige `(?U)`. Sem ele, o `\w` do Java é ASCII, o 'ç' vira
        // não-palavra, surge uma fronteira que o Python não vê, e "çcompare" casaria.
        assertEquals(0.0, f("çcompare")["n_chaves"]!!, 0.0)
    }

    // ── desvio das alternativas ───────────────────────────────────────────────

    @Test
    fun `alt_len_desvio e populacional`() {
        // Comprimentos 1..5: média 3, variância populacional 2 → sqrt(2) ≈ 1,4142.
        // O desvio amostral daria sqrt(2,5) ≈ 1,5811.
        val alts = listOf("a", "a a", "a a a", "a a a a", "a a a a a")
        assertEquals(sqrt(2.0), f("x", alts)["alt_len_desvio"]!!, 1e-12)
    }

    @Test
    fun `alt_len_desvio e zero com uma alternativa so`() {
        assertEquals(0.0, f("x", listOf("a"))["alt_len_desvio"]!!, 0.0)
    }

    @Test
    fun `alt_numericas usa o limiar de um quarto dos caracteres`() {
        // "12345" → 5 dígitos em 5 chars = 1,0 > 0,25 → numérica.
        // "abcdefgh1" → 1 em 9 ≈ 0,11 → não.
        val alts = listOf("12345", "abcdefgh1")
        assertEquals(0.5, f("x", alts)["alt_numericas"]!!, 1e-12)
    }

    // ── área one-hot ──────────────────────────────────────────────────────────

    @Test
    fun `area one-hot liga exatamente uma`() {
        val r = f("x", ALTS, "CN")
        assertEquals(1.0, r["area_CN"]!!, 0.0)
        assertEquals(0.0, r["area_LC"]!!, 0.0)
        assertEquals(0.0, r["area_CH"]!!, 0.0)
        assertEquals(0.0, r["area_MT"]!!, 0.0)
    }

    @Test
    fun `area desconhecida zera as quatro`() {
        // O caso do uso real: não há rótulo de área para o que o aluno digita.
        val r = f("x", ALTS, null)
        assertEquals(0.0, StringFeatureExtractor.AREAS.sumOf { r["area_$it"]!! }, 0.0)
    }

    // ── invólucro do prompt ───────────────────────────────────────────────────

    @Test
    fun `enunciadoCru remove cabecalho e Resposta final`() {
        val prompt = StringFeatureExtractor.CABECALHO + "\n\nEnunciado.\n\nA) um\nB) dois\n" +
            "\nResposta:"
        assertEquals("Enunciado.\n\nA) um\nB) dois", ex.enunciadoCru(prompt))
    }

    @Test
    fun `enunciadoCru e idempotente em texto de chat`() {
        // No caminho real o texto do aluno já é o cru; passar por aqui não pode alterá-lo.
        val texto = "Como funciona a fotossíntese?"
        assertEquals(texto, ex.enunciadoCru(texto))
    }

    // ── contrato do conjunto ──────────────────────────────────────────────────

    @Test
    fun `extract emite exatamente as 18 features declaradas`() {
        val r = f("Enunciado qualquer com 2 palavras.")
        assertEquals(18, StringFeatureExtractor.FEATURE_NAMES.size)
        assertEquals(StringFeatureExtractor.FEATURE_NAMES.toSet(), r.keys)
    }

    @Test
    fun `enunciado vazio nao produz NaN`() {
        // Densidades dividem por `max(1, n_chars)`. Sem isso, 0/0 = NaN — que atravessaria
        // a padronização e o sigmoide sem estourar, virando um score inútil.
        f("", emptyList(), null).forEach { (nome, v) ->
            assertEquals("$nome virou NaN", false, v.isNaN())
        }
    }

    private companion object {
        val ALTS = listOf("um", "dois", "tres", "quatro", "cinco")
    }
}
