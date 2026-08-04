package com.voiceassistant.ai_local.service

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.ai_local.manager.LocalModelManager
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
                val answer = service.generate(prompt)
                val stats = service.lastStats

                assertTrue("resposta vazia para: $prompt", answer.isNotBlank())
                assertTrue("nenhum token gerado", (stats?.generatedTokens ?: 0) > 0)
                assertTrue("nenhum token de prompt contado", (stats?.promptTokens ?: 0) > 0)
                assertTrue("TTFT inválido", (stats?.ttftMs ?: -1.0) > 0.0)
                // H2/H3 dependem dos timings do llama_perf: se `no_perf` voltar ao
                // default (true), estes campos zeram silenciosamente. Falhar aqui.
                assertTrue("prefillMs zerado — llama_perf desligado?", stats!!.prefillMs > 0.0)
                assertTrue("decodeMs zerado — llama_perf desligado?", stats.decodeMs > 0.0)
                assertTrue("tokens/s de geração inválido", stats.generatedTokensPerSec > 0.0)

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

    companion object {
        private const val TAG = "LlamaCppSmokeTest"
    }
}
