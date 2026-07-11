package com.voiceassistant.ai_server.service

import com.voiceassistant.ai_server.model.ServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Teste de fumaça **opt-in** do [ServerInferenceService] contra um `llama-server` real.
 *
 * Exercita a classe de produção de ponta a ponta: health-check + geração + cálculo de
 * confiança a partir dos logprobs. Requer um servidor rodando na rede (ver
 * `docs/05-tier-servidor-setup.md`).
 *
 * Desligado por padrão: sem a variável de ambiente `LLAMA_SERVER_URL`, os testes são
 * **pulados** (não quebram a suíte / o CI).
 *
 * Uso:
 * ```
 * # Linux/macOS
 * LLAMA_SERVER_URL=http://192.168.1.100:8080 \
 *   ./gradlew :app:testDebugUnitTest --tests "*ServerInferenceServiceSmokeTest"
 *
 * # Windows (PowerShell)
 * $env:LLAMA_SERVER_URL="http://192.168.1.100:8080"
 * ./gradlew :app:testDebugUnitTest --tests "*ServerInferenceServiceSmokeTest"
 * ```
 */
class ServerInferenceServiceSmokeTest {

    private val serverUrl: String? = System.getenv(ENV_URL)

    private fun service(): ServerInferenceService =
        ServerInferenceService(ServerConfig(baseUrl = serverUrl!!))

    @Test
    fun `health check succeeds against real server`() = runTest {
        assumeTrue("Defina $ENV_URL para rodar este smoke test", serverUrl != null)

        val reachable = service().isServerReachable()
        println("[smoke] isServerReachable($serverUrl) = $reachable")
        assertTrue("Servidor não respondeu ao /health (status != ok)", reachable)
    }

    @Test
    fun `completion returns text and confidence in valid range`() = runTest {
        assumeTrue("Defina $ENV_URL para rodar este smoke test", serverUrl != null)

        val result = service().generateWithConfidence("O que é fotossíntese?")
        println("[smoke] tokens=${result.tokenCount} confidence=${result.confidence} " +
                "latency=${result.latencyMs}ms")
        println("[smoke] texto: ${result.text.take(120)}")

        assertTrue("Resposta veio vazia", result.text.isNotBlank())
        // Confiança válida em [0,1]; -1 significa "sem logprobs" (schema novo do servidor
        // — ver seção 5 do docs/05-tier-servidor-setup.md).
        assertTrue(
            "Confiança fora de [-1,1]: ${result.confidence}",
            result.confidence in -1f..1f
        )
        if (result.confidence < 0f) {
            println("[smoke] AVISO: confidence=-1 → provável schema novo de " +
                    "completion_probabilities. Rode scripts/server_smoke_test.py --raw " +
                    "para confirmar a variante.")
        }
    }

    companion object {
        private const val ENV_URL = "LLAMA_SERVER_URL"
    }
}
