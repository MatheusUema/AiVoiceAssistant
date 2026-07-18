package com.voiceassistant.ai_server.service

import com.voiceassistant.ai_server.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testes unitários das funções puras do [ServerInferenceService] (sem rede):
 * cálculo de confiança a partir dos logprobs e normalização de URL.
 */
class ServerInferenceServiceTest {

    private val service = ServerInferenceService(ServerConfig())

    // ── calculateConfidence ───────────────────────────────────────────────

    @Test
    fun `null probabilities yields unavailable`() {
        assertEquals(
            ServerInferenceService.CONFIDENCE_UNAVAILABLE,
            service.calculateConfidence(null)
        )
    }

    @Test
    fun `empty probabilities yields unavailable`() {
        assertEquals(
            ServerInferenceService.CONFIDENCE_UNAVAILABLE,
            service.calculateConfidence(emptyList())
        )
    }

    @Test
    fun `uses chosen token prob by content match not top-1`() {
        // O token escolhido é "B" (content), com prob 0.1 — NÃO o mais provável ("A", 0.9).
        val probs = listOf(
            TokenProb(
                content = "B",
                probs = listOf(ProbEntry("A", 0.9f), ProbEntry("B", 0.1f))
            )
        )
        assertEquals(0.1f, service.calculateConfidence(probs), 1e-4f)
    }

    @Test
    fun `averages chosen-token probs across positions`() {
        val probs = listOf(
            TokenProb("A", listOf(ProbEntry("A", 0.8f))),
            TokenProb("B", listOf(ProbEntry("B", 0.6f)))
        )
        assertEquals(0.7f, service.calculateConfidence(probs), 1e-4f)
    }

    @Test
    fun `falls back to first candidate when content has no match`() {
        val probs = listOf(
            TokenProb("Z", listOf(ProbEntry("A", 0.5f), ProbEntry("C", 0.2f)))
        )
        assertEquals(0.5f, service.calculateConfidence(probs), 1e-4f)
    }

    @Test
    fun `tokens without candidates yield unavailable`() {
        val probs = listOf(TokenProb(content = "A", probs = emptyList()))
        assertEquals(
            ServerInferenceService.CONFIDENCE_UNAVAILABLE,
            service.calculateConfidence(probs)
        )
    }

    // ── calculateConfidence — schema NOVO (token/logprob/top_logprobs) ────

    @Test
    fun `new schema uses exp of chosen-token logprob (top-level entry)`() {
        // A entrada de topo já é o token escolhido; usa o logprob DELA (não do top_logprobs).
        val probs = listOf(
            TokenProb(
                token = "\n\n",
                logprob = -0.0397f,
                topLogprobs = listOf(
                    ProbEntry(token = "\n\n", logprob = -0.0397f),
                    ProbEntry(token = "\n", logprob = -3.38f)
                )
            )
        )
        // exp(-0.0397) ≈ 0.9611
        assertEquals(0.9611f, service.calculateConfidence(probs), 1e-3f)
    }

    @Test
    fun `new schema averages exp of logprobs across tokens`() {
        // Caso real: logprobs de 9 tokens -> confiança ~0.94.
        val logprobs = listOf(
            -0.0397f, -0.356f, -0.003f, -0.0148f, -0.0058f,
            -0.000136f, -0.00288f, -0.217f, -0.00436f
        )
        val probs = logprobs.map { TokenProb(token = "t", logprob = it) }
        assertEquals(0.94f, service.calculateConfidence(probs), 0.01f)
    }

    @Test
    fun `new schema entry without logprob degrades to unavailable`() {
        val probs = listOf(TokenProb(token = "t")) // sem logprob e sem probs
        assertEquals(
            ServerInferenceService.CONFIDENCE_UNAVAILABLE,
            service.calculateConfidence(probs)
        )
    }

    // ── normalizeBaseUrl ──────────────────────────────────────────────────

    @Test
    fun `appends trailing slash when missing`() {
        assertEquals("http://192.168.1.100:8080/", service.normalizeBaseUrl("http://192.168.1.100:8080"))
    }

    @Test
    fun `keeps existing trailing slash`() {
        assertEquals("http://192.168.1.100:8080/", service.normalizeBaseUrl("http://192.168.1.100:8080/"))
    }
}
