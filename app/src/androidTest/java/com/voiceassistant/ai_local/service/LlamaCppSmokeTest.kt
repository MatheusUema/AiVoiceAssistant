package com.voiceassistant.ai_local.service

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.model.LocalModelVariant
import com.voiceassistant.core.telemetry.ProcessRamSampler
import com.voiceassistant.llama.LlamaEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Smoke test on-device da migração para llama.cpp (doc 06 §1.4).
 *
 * Carrega um GGUF real, roda alguns prompts e confere que sai texto coerente com
 * timings plausíveis. Só roda se o arquivo estiver presente — caso contrário o teste
 * é *pulado* (assume), não falha, para não quebrar CI sem modelo.
 *
 * Como rodar:
 *   1. `.\scripts\push-model.ps1 -ModelPath <modelo>.gguf`
 *   2. `./gradlew :app:connectedDebugAndroidTest --tests "*LlamaCppSmokeTest*"`
 *
 * Os números impressos no Logcat (tag `LlamaCppSmokeTest`) são o primeiro sinal de
 * H2/H3: TTFT, prefill vs decode, tokens/s.
 */
class LlamaCppSmokeTest {

    private val variants = listOf(
        LocalModelConfig.SMOKE_TEST_TINY,
        LocalModelConfig.GEMMA_3_1B_Q4_K_M,
        LocalModelConfig.GEMMA_4_E2B_Q4_K_M
    )

    private val ramSampler = ProcessRamSampler()

    private val prompts = listOf(
        "Qual é a capital do Brasil? Responda em uma frase.",
        "Explique em duas frases o que é fotossíntese.",
        "Quanto é 17 x 4? Responda apenas com o número."
    )

    @Test
    fun carregaGgufEGeraResposta() = runBlocking {
        assumeTrue("libllama_bridge.so não disponível nesta ABI", LlamaEngine.isNativeAvailable)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val searchDirs = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(LocalModelManager.EXTERNAL_MODELS_DIR)
        )
        // Do menor para o maior: se houver mais de um GGUF no aparelho, o smoke test
        // usa o mais leve — validar a ponte não precisa dos 3,43 GB do E2B.
        val model = variants
            .flatMap { variant -> searchDirs.map { java.io.File(it, variant.fileName) } }
            .filter { it.exists() && it.length() > 0 }
            .minByOrNull { it.length() }

        assumeTrue(
            "Nenhum GGUF em filesDir nem em externalFilesDir/models — " +
                    "rode scripts/push-model.ps1 primeiro. Teste pulado.",
            model != null
        )

        val config = LocalModelConfig(
            primary = LocalModelVariant(
                assetPath = "models/${model!!.name}",
                sizeMb = model.length() / (1024 * 1024),
                minRamMb = 0
            ),
            fallback = null,
            maxTokens = 64
        )
        val service = LlamaCppLocalInferenceService(config)

        try {
            service.loadModel(model.absolutePath)
            assertTrue("modelo deveria estar carregado", service.isModelLoaded)
            Log.i(TAG, "modelo: ${service.modelInfo}")

            service.warmup("Olá")

            prompts.forEach { prompt ->
                // H4: o pico tem que ser amostrado durante a geração — é assim que o
                // InferenceRouter mede em produção, então é assim que se testa.
                //
                // ⚠️ O valor medido AQUI não é uma medição limpa do modelo sob teste.
                // A instrumentação roda dentro do processo do app, e a Application carrega
                // o modelo dela (o configurado em -Plocal.model) em paralelo — então o PSS
                // soma os dois. Confirmado no Device 1: 3268 MiB do E2B da Application +
                // 468 MiB do Qwen deste teste = ~3,7 GB, contra os ~4,1 GB observados.
                // O número de H4 que vale para o estudo sai do caminho do app
                // (InferenceRouter, um modelo só) — lá o mesmo E2B deu 3214 MB.
                // Aqui a asserção é só sanidade: o amostrador leu algo plausível.
                val (answer, peakRamMb) = ramSampler.measurePeak { service.generate(prompt) }
                val stats = service.lastStats

                assertTrue(
                    "pico de RAM não medido ($peakRamMb MB) — Debug.getPss() falhou?",
                    peakRamMb > 0
                )
                assertTrue(
                    "pico de RAM ($peakRamMb MB) menor que o modelo em disco " +
                            "(${model.length() / (1024 * 1024)} MB): a amostragem perdeu o pico",
                    peakRamMb >= model.length() / (1024 * 1024) / 2
                )

                // Duas fontes de PSS lidas no mesmo instante. O plano (doc 06, H4) cita
                // as duas; se discordarem, uma delas está mentindo e H4 iria para o
                // artigo errado. Comparar aqui é mais barato que descobrir depois.
                val pssDebugMb = android.os.Debug.getPss() / 1024
                val amInfo = android.os.Debug.MemoryInfo()
                android.os.Debug.getMemoryInfo(amInfo)
                Log.i(
                    TAG,
                    "PSS agora: Debug.getPss()=${pssDebugMb}MB | " +
                            "MemoryInfo.totalPss=${amInfo.totalPss / 1024}MB " +
                            "(dalvik=${amInfo.dalvikPss / 1024} nativo=${amInfo.nativePss / 1024} " +
                            "outros=${amInfo.otherPss / 1024})"
                )

                val telemetry = service.lastTelemetry
                assertNotNull("telemetria ausente", telemetry)
                assertEquals(
                    "modelId da telemetria deve ser o modelo carregado",
                    service.loadedModelId, telemetry!!.modelId
                )

                assertTrue("resposta vazia para: $prompt", answer.isNotBlank())
                assertTrue("nenhum token gerado", (stats?.generatedTokens ?: 0) > 0)
                assertTrue("nenhum token de prompt contado", (stats?.promptTokens ?: 0) > 0)
                assertTrue("TTFT inválido", (stats?.ttftMs ?: -1.0) > 0.0)
                // H2/H3 dependem dos timings do llama_perf: se `no_perf` voltar ao
                // default (true), estes campos zeram silenciosamente. Falhar aqui.
                assertTrue("prefillMs zerado — llama_perf desligado?", stats!!.prefillMs > 0.0)
                assertTrue("decodeMs zerado — llama_perf desligado?", stats.decodeMs > 0.0)
                assertTrue("tokens/s de geração inválido", stats.generatedTokensPerSec > 0.0)

                // As janelas do llama.cpp têm que caber dentro da nossa. O llama.cpp
                // contabiliza preguiçosamente (no synchronize), então uma janela deixada
                // aberta pela chamada anterior vaza para o prefill desta — foi exatamente
                // isso que produziu prefill > TTFT e soma > total nas primeiras medições.
                // Sem estas invariantes, H2 e H3 mentem sem dar erro.
                assertTrue(
                    "prefill (${stats.prefillMs}ms) > TTFT (${stats.ttftMs}ms): " +
                            "a janela do llama.cpp abriu antes da nossa",
                    stats.prefillMs <= stats.ttftMs * TIMING_TOLERANCE
                )
                assertTrue(
                    "prefill+decode (${stats.prefillMs + stats.decodeMs}ms) > " +
                            "total (${stats.totalMs}ms): dupla contagem nos timings",
                    stats.prefillMs + stats.decodeMs <= stats.totalMs * TIMING_TOLERANCE
                )

                Log.i(
                    TAG,
                    ("prompt=%s | ttft=%.0fms prefill=%.0fms decode=%.0fms | " +
                            "%d→%d tok | %.2f tok/s | pico RAM %d MB")
                        .format(
                            prompt.take(30), stats!!.ttftMs, stats.prefillMs, stats.decodeMs,
                            stats.promptTokens, stats.generatedTokens,
                            stats.generatedTokensPerSec, peakRamMb
                        )
                )
                Log.i(TAG, "resposta: ${answer.take(200)}")
            }
        } finally {
            service.unloadModel()
        }
    }

    companion object {
        private const val TAG = "LlamaCppSmokeTest"

        /**
         * Folga de 1 % para granularidade de relógio — bem abaixo dos ~3 % de erro
         * sistemático que a dupla contagem produzia, para que a regressão volte a falhar.
         */
        private const val TIMING_TOLERANCE = 1.01
    }
}
