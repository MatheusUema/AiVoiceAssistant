package com.example.voice_assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sanidade da instrumentação: confirma que os testes rodam contra o app certo.
 *
 * O teste de template gerado pelo Android Studio afirmava o pacote
 * `com.example.voice_assistant`, que nunca foi o applicationId deste projeto — falhava
 * desde sempre. Um teste cronicamente vermelho é pior que nenhum: durante a coleta,
 * esconde a falha de verdade no meio do ruído.
 *
 * O build debug tem o sufixo `.debug`, então a asserção é por prefixo.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun rodaContraOAppCerto() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "pacote inesperado: ${appContext.packageName}",
            appContext.packageName.startsWith("com.voiceassistant")
        )
    }
}
