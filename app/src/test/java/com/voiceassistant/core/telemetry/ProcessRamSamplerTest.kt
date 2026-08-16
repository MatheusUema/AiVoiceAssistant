package com.voiceassistant.core.telemetry

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Na JVM o `Debug.getPss()` devolve o default do Android stub (0), então aqui não se
 * testa o valor do pico — testa-se o que realmente pode quebrar a coleta: o sampler não
 * pode engolir o resultado, mascarar exceção nem deixar corrotina viva depois do bloco.
 */
class ProcessRamSamplerTest {

    private val sampler = ProcessRamSampler()

    @Test
    fun `devolve o resultado do bloco`() = runTest {
        val (value, _) = sampler.measurePeak { "resposta do modelo" }
        assertEquals("resposta do modelo", value)
    }

    @Test
    fun `sem leitura de PSS o pico sai como indisponivel`() = runTest {
        val (_, peak) = sampler.measurePeak { 42 }
        // -1 = indisponível, a convenção do projeto. O que não pode é vir 0 e ser lido
        // como "o processo não usou memória".
        assertEquals(ProcessRamSampler.RAM_UNAVAILABLE, peak)
    }

    @Test
    fun `excecao do bloco nao e mascarada`() = runTest {
        try {
            sampler.measurePeak<Unit> { throw IllegalStateException("falha na geração") }
            error("deveria ter propagado a exceção")
        } catch (expected: IllegalStateException) {
            assertEquals("falha na geração", expected.message)
        }
    }

    @Test
    fun `bloco demorado nao trava o sampler`() = runTest {
        val (value, _) = sampler.measurePeak(intervalMs = 5) {
            delay(60)
            "ok"
        }
        assertEquals("ok", value)
        assertTrue(true)
    }
}
