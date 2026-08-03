package com.voiceassistant.ai_local.service

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.model.LocalModelVariant
import com.voiceassistant.llama.LlamaEngine
import kotlinx.coroutines.runBlocking
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
 *   1. `adb push <modelo>.gguf /data/local/tmp/` **ou** ponha o GGUF em
 *      `app/src/main/assets/models/` e deixe o app copiar na primeira execução
 *   2. `./gradlew :app:connectedDebugAndroidTest --tests "*LlamaCppSmokeTest*"`
 *
 * Os números impressos no Logcat (tag `LlamaCppSmokeTest`) são o primeiro sinal de
 * H2/H3: TTFT, prefill vs decode, tokens/s.
 */
class LlamaCppSmokeTest {

    private val variants = listOf(
        LocalModelConfig.SMOKE_TEST_TINY,
        LocalModelConfig.GEMMA_1B_Q4_K_M,
        LocalModelConfig.GEMMA_2B_Q4_K_M
    )

    private val prompts = listOf(
        "Qual é a capital do Brasil? Responda em uma frase.",
        "Explique em duas frases o que é fotossíntese.",
        "Quanto é 17 x 4? Responda apenas com o número."
    )

    @Test
    fun carregaGgufEGeraResposta() = runBlocking {
        assumeTrue("libllama_bridge.so não disponível nesta ABI", LlamaEngine.isNativeAvailable)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = variants
            .map { java.io.File(context.filesDir, it.fileName) }
            .firstOrNull { it.exists() && it.length() > 0 }
            ?: firstPushedModel()

        assumeTrue(
            "Nenhum GGUF encontrado em filesDir nem em /data/local/tmp — teste pulado",
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
                val answer = service.generate(prompt)
                val stats = service.lastStats

                assertTrue("resposta vazia para: $prompt", answer.isNotBlank())
                assertTrue("nenhum token gerado", (stats?.generatedTokens ?: 0) > 0)
                assertTrue("TTFT inválido", (stats?.ttftMs ?: -1.0) > 0.0)

                Log.i(
                    TAG,
                    "prompt=%s | ttft=%.0fms prefill=%.0fms decode=%.0fms | %d→%d tok | %.2f tok/s"
                        .format(
                            prompt.take(30), stats!!.ttftMs, stats.prefillMs, stats.decodeMs,
                            stats.promptTokens, stats.generatedTokens, stats.generatedTokensPerSec
                        )
                )
                Log.i(TAG, "resposta: ${answer.take(200)}")
            }
        } finally {
            service.unloadModel()
        }
    }

    /** GGUF empurrado por `adb push ... /data/local/tmp/`. */
    private fun firstPushedModel(): java.io.File? =
        java.io.File("/data/local/tmp")
            .listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.length() > 0 }
            ?.minByOrNull { it.length() }

    companion object {
        private const val TAG = "LlamaCppSmokeTest"
    }
}
