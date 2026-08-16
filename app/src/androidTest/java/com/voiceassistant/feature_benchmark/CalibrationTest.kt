package com.voiceassistant.feature_benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.model.LocalModelVariant
import com.voiceassistant.ai_local.service.LlamaCppLocalInferenceService
import com.voiceassistant.core.telemetry.ProcessRamSampler
import com.voiceassistant.feature_benchmark.data.EnemDataset
import com.voiceassistant.feature_benchmark.data.EnemPromptBuilder
import com.voiceassistant.feature_benchmark.data.EnemQuestion
import com.voiceassistant.llama.LlamaEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Rodada de calibração com **questões reais do ENEM**, antes de fixar o protocolo.
 *
 * O que precisa ser respondido aqui, e que nenhum prompt curto de validação responde:
 *
 *  1. Quanto custa uma questão de verdade? Os enunciados são longos, então o custo passa
 *     a ser dominado pelo **prefill**, não pelo decode — e é o prefill que define se a
 *     bateria leva 2 h ou 8 h por aparelho.
 *  2. Quanto o raciocínio muda esse custo? O artigo 1 rodou com `n_predict=4` (só a
 *     letra), o que impede o Gemma 4 de raciocinar. Medir as duas condições nas mesmas
 *     questões quantifica o que se ganha e o que se paga ao permitir raciocínio.
 *
 * Não é um teste de regressão: é um instrumento de medida. As asserções são frouxas de
 * propósito — o valor está nos números que ele imprime no logcat.
 */
@RunWith(AndroidJUnit4::class)
class CalibrationTest {

    private val promptBuilder = EnemPromptBuilder()
    private val ramSampler = ProcessRamSampler()

    @Test
    fun calibraCustoDeQuestoesReaisDoEnem() = runBlocking {
        assumeTrue("libllama_bridge.so indisponível", LlamaEngine.isNativeAvailable)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataset = EnemDataset(context)
        val questions = dataset.balancedSample(perArea = QUESTIONS_PER_AREA)
        assertTrue("nenhuma questão carregada do CSV", questions.isNotEmpty())

        val model = findModel(context)
        assumeTrue("Nenhum GGUF no aparelho — teste pulado", model != null)

        Log.i(TAG, "═══ calibração: ${questions.size} questões, modelo ${model!!.name} ═══")
        logPromptSizes(questions)

        for (condition in CONDITIONS) {
            runCondition(model, questions, condition)
        }
    }

    private suspend fun runCondition(
        model: File,
        questions: List<EnemQuestion>,
        condition: Condition
    ) {
        val service = LlamaCppLocalInferenceService(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LocalModelConfig(
                primary = LocalModelVariant(
                    assetPath = "models/${model.name}",
                    sizeMb = model.length() / (1024 * 1024),
                    minRamMb = 0
                ),
                fallback = null,
                threads = condition.threads,
                maxTokens = condition.maxTokens,
                enableThinking = condition.thinking,
                // Sem timeout: aqui queremos justamente descobrir quanto demora.
                generationTimeoutMs = 0
            )
        )

        try {
            service.loadModel(model.absolutePath)
            service.warmup("Olá")

            var completas = 0
            val latencies = mutableListOf<Double>()
            val prefills = mutableListOf<Double>()

            Log.i(TAG, "── condição: ${condition.name} (n_predict=${condition.maxTokens}, " +
                    "thinking=${condition.thinking}) ──")

            for (question in questions) {
                val prompt = promptBuilder.build(question)
                val (raw, peakRamMb) = ramSampler.measurePeak { service.generate(prompt) }
                val stats = service.lastStats ?: continue

                // Esta calibração mede **custo**, não acurácia. Qual alternativa o modelo
                // escolheu se decide lendo a resposta, fora daqui. O que dá para verificar
                // é se a geração terminou sozinha: cortada no meio, não há resposta para
                // classificar — e uma condição que trunca tudo é inviável, independente
                // do que o modelo saberia responder.
                if (!stats.truncated) completas++
                latencies += stats.totalMs
                prefills += stats.prefillMs

                Log.i(
                    TAG,
                    ("%s %s | %d→%d tok (raciocínio %d) | prefill %.0fms decode %.0fms " +
                            "total %.0fms | RAM %d MB | %d chars | %s")
                        .format(
                            condition.name, question.id,
                            stats.promptTokens, stats.generatedTokens, stats.reasoningTokens,
                            stats.prefillMs, stats.decodeMs, stats.totalMs,
                            peakRamMb, raw.length, stats.stopReason
                        )
                )
            }

            Log.i(
                TAG,
                ("RESUMO %s | %d questões | %d completas (classificáveis) | " +
                        "latência mediana %.0fms | prefill mediano %.0fms | estimado por bloco " +
                        "de 20 com k=3: %.1f min")
                    .format(
                        condition.name, latencies.size, completas,
                        latencies.median(), prefills.median(),
                        latencies.median() * 20 * 3 / 60_000.0
                    )
            )
        } finally {
            service.unloadModel()
        }
    }

    /** O comprimento do prompt é o que domina o custo — vale medir antes de inferir. */
    private fun logPromptSizes(questions: List<EnemQuestion>) {
        val sizes = questions.map { promptBuilder.build(it).length }
        Log.i(
            TAG,
            "tamanho do prompt (chars): min=%d mediana=%d max=%d".format(
                sizes.min(), sizes.sorted()[sizes.size / 2], sizes.max()
            )
        )
        questions.groupBy { it.area }.forEach { (area, qs) ->
            val areaSizes = qs.map { promptBuilder.build(it).length }
            Log.i(TAG, "  $area: mediana ${areaSizes.sorted()[areaSizes.size / 2]} chars")
        }
    }

    private fun findModel(context: android.content.Context): File? {
        val dirs = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir(LocalModelManager.EXTERNAL_MODELS_DIR)
        )
        return dirs.flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.name.endsWith(".gguf") && it.length() > 0 }
            // O maior é o modelo do estudo; os menores são de smoke test.
            .maxByOrNull { it.length() }
    }

    private fun List<Double>.median(): Double =
        if (isEmpty()) 0.0 else sorted()[size / 2]

    private data class Condition(
        val name: String,
        val maxTokens: Int,
        val thinking: Boolean,
        /** 0 = heurística do LlamaEngine (núcleos - 2, saturado em 4). */
        val threads: Int = 0
    )

    private companion object {
        const val TAG = "CalibrationTest"
        const val QUESTIONS_PER_AREA = 2

        /**
         * Varredura de threads no protocolo do artigo 1 (só a letra).
         *
         * O prefill de uma questão do ENEM é **limitado por computação**, não por banda:
         * ~340 tokens × bilhões de parâmetros é da ordem de TFLOPs, contra alguns MB/s de
         * leitura de pesos. Isso significa que mais núcleos devem ajudar — e a heurística
         * herdada do exemplo oficial satura em 4, num aparelho de 8 núcleos.
         *
         * O número de threads é uma **condição do protocolo**: precisa ser fixado e
         * declarado, senão a comparação entre aparelhos mistura hardware com configuração.
         */
        val CONDITIONS = listOf(
            Condition("letra-t4", maxTokens = 4, thinking = false, threads = 4),
            Condition("letra-t6", maxTokens = 4, thinking = false, threads = 6),
            Condition("letra-t8", maxTokens = 4, thinking = false, threads = 8)
        )
    }
}
