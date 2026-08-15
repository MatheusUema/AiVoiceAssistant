package com.voiceassistant.ai_local.service

import com.voiceassistant.ai_local.model.LocalModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato de [LocalInferenceService] cumprido pela implementação llama.cpp.
 *
 * Roda na JVM, onde `libllama_bridge.so` não existe: o objetivo é justamente garantir
 * que a ausência do runtime nativo (ou de um modelo) vire **erro tratado** — nunca um
 * `UnsatisfiedLinkError` vazando para a camada de domínio. É o mesmo caminho de código
 * que protege o Device 2 quando o modelo não cabe.
 *
 * A validação de geração real é instrumentada: ver `LlamaCppSmokeTest` em androidTest.
 */
class LlamaCppLocalInferenceServiceContractTest {

    private fun service() =
        LlamaCppLocalInferenceService(android.app.Application(), LocalModelConfig())

    @Test
    fun `sem modelo carregado, nao esta disponivel`() {
        val service = service()
        assertFalse(service.isModelLoaded)
        assertFalse(service.isAvailable)
    }

    @Test
    fun `generate sem modelo lanca LocalModelNotReadyException`() = runTest {
        val service = service()
        try {
            service.generate("Qual a capital do Brasil?")
            error("deveria ter lançado LocalModelNotReadyException")
        } catch (expected: LocalModelNotReadyException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `warmup sem modelo lanca LocalModelNotReadyException`() = runTest {
        val service = service()
        try {
            service.warmup("Olá")
            error("deveria ter lançado LocalModelNotReadyException")
        } catch (expected: LocalModelNotReadyException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `loadModel com caminho inexistente vira LocalInferenceException com motivo`() = runTest {
        val service = service()
        try {
            service.loadModel("/caminho/que/nao/existe/modelo.gguf")
            error("deveria ter lançado LocalInferenceException")
        } catch (expected: LocalInferenceException) {
            assertTrue(
                "mensagem deve explicar a causa: ${expected.message}",
                !expected.message.isNullOrBlank()
            )
        }
        // A falha precisa ficar registrada para virar linha de `model_load_log`.
        assertNotNull(service.lastLoadResult)
        assertFalse(service.isModelLoaded)
    }

    @Test
    fun `unloadModel e idempotente`() {
        val service = service()
        service.unloadModel()
        service.unloadModel()
        assertFalse(service.isModelLoaded)
    }
}
