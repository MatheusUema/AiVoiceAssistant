package com.voiceassistant.feature_tutor.policy

import com.voiceassistant.core.model.PromptComplexity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PromptComplexityAnalyzerTest {

    private lateinit var analyzer: PromptComplexityAnalyzer

    @Before
    fun setUp() {
        analyzer = PromptComplexityAnalyzer()
    }

    // ── SIMPLE ────────────────────────────────────────────────────────────

    @Test
    fun `short question returns SIMPLE`() {
        assertEquals(PromptComplexity.SIMPLE, analyzer.analyze("O que é água?"))
    }

    @Test
    fun `single word returns SIMPLE`() {
        assertEquals(PromptComplexity.SIMPLE, analyzer.analyze("Fotossíntese"))
    }

    @Test
    fun `short phrase without keywords returns SIMPLE`() {
        assertEquals(PromptComplexity.SIMPLE, analyzer.analyze("Qual é a capital do Brasil?"))
    }

    // ── MODERATE ──────────────────────────────────────────────────────────

    @Test
    fun `medium length text returns MODERATE`() {
        val prompt = "Gostaria de saber mais sobre o sistema solar e " +
                "como os planetas se organizam ao redor do sol e quais são suas características"
        assertEquals(PromptComplexity.MODERATE, analyzer.analyze(prompt))
    }

    @Test
    fun `two questions returns MODERATE`() {
        val prompt = "O que é DNA? Como ele funciona?"
        assertEquals(PromptComplexity.MODERATE, analyzer.analyze(prompt))
    }

    // ── COMPLEX ───────────────────────────────────────────────────────────

    @Test
    fun `keyword explique triggers COMPLEX`() {
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze("Explique a teoria da relatividade"))
    }

    @Test
    fun `keyword compare triggers COMPLEX`() {
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze("Compare mitose e meiose"))
    }

    @Test
    fun `keyword calcule triggers COMPLEX`() {
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze("Calcule a integral de x²"))
    }

    @Test
    fun `keyword qual a diferença entre triggers COMPLEX`() {
        assertEquals(
            PromptComplexity.COMPLEX,
            analyzer.analyze("Qual a diferença entre célula animal e vegetal")
        )
    }

    @Test
    fun `three or more questions triggers COMPLEX`() {
        val prompt = "O que é DNA? Como funciona? Quem descobriu?"
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze(prompt))
    }

    @Test
    fun `very long text triggers COMPLEX`() {
        val words = (1..60).joinToString(" ") { "palavra" }
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze(words))
    }

    @Test
    fun `keyword is case insensitive`() {
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze("EXPLIQUE o ciclo da água"))
        assertEquals(PromptComplexity.COMPLEX, analyzer.analyze("Analise o poema"))
    }
}
