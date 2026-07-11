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
        val probs = listOf(TokenProb("A", emptyList()))
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
