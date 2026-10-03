package com.voiceassistant.feature_chat.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.feature_benchmark.BenchmarkEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Liga e desliga o **cenário Unplugged** no aparelho, para a captura dos prints do Bloco D
 * parte 1.
 *
 * ## Por que isto existe
 *
 * O cenário que o D1 demonstra é "só o aparelho": sem servidor e sem nuvem. O jeito óbvio
 * de obtê-lo seria desligar o Wi-Fi — mas **a depuração é por Wi-Fi**, e desligá-lo derruba
 * o `adb` junto com a captura. O modo privacidade resolve: o [InferenceRouter] passa a
 * rotear sempre para LOCAL (regras 1–2) com o aparelho ainda alcançável.
 *
 * Não é truque de bancada: é o mesmo mecanismo que a bateria de medição já usa
 * (`BenchmarkBatteryTest`, que liga `localOnly` por padrão pela mesma razão), pelo mesmo
 * `BenchmarkEntryPoint`, sobre o grafo de produção.
 *
 * Há uma segunda razão, menos óbvia e mais importante para a honestidade do print: com o
 * aparelho online o roteador encurta o orçamento de tempo do local
 * (`generationTimeoutWithFallbackMs`, 30 s) **porque existe nuvem para onde cair**. Uma
 * resposta cortada por esse orçamento mediria a política de fallback, não o aparelho — e a
 * faixa que a tela mostrasse sairia de uma geração truncada.
 *
 * ## Uso
 *
 * ```
 * adb shell am instrument -w -e class \
 *   com.voiceassistant.feature_chat.ui.CenarioUnpluggedSetup#ligaCenarioUnplugged \
 *   com.voiceassistant.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * E `desligaCenarioUnplugged` ao final, para devolver o aparelho ao estado anterior.
 */
@RunWith(AndroidJUnit4::class)
class CenarioUnpluggedSetup {

    private val settings by lazy {
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            BenchmarkEntryPoint::class.java
        ).userSettings()
    }

    @Test
    fun ligaCenarioUnplugged() { runBlocking {
        settings.setPrivacyMode(true)
        // Desliga também o tier servidor: em modo privacidade o roteador já não o consulta,
        // mas deixá-lo ligado tornaria o estado do aparelho ambíguo para quem reproduzir.
        settings.setServerTierEnabled(false)

        val s = settings.settings.first()
        assertEquals("modo privacidade não ficou ligado", true, s.privacyModeEnabled)
        assertEquals("tier servidor não ficou desligado", false, s.serverTierEnabled)
        Log.i(TAG, "cenário Unplugged LIGADO (privacidade=on, servidor=off)")
    } }

    @Test
    fun desligaCenarioUnplugged() { runBlocking {
        settings.setPrivacyMode(false)
        val s = settings.settings.first()
        assertEquals("modo privacidade não ficou desligado", false, s.privacyModeEnabled)
        Log.i(TAG, "cenário Unplugged DESLIGADO — aparelho devolvido ao estado anterior")
    } }

    private companion object {
        const val TAG = "CenarioUnplugged"
    }
}
