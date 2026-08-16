package com.voiceassistant.feature_benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.ai_local.manager.ModelState
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Dispara a bateria de medição no aparelho, sem UI.
 *
 * É o entrypoint de coleta do estudo. Roda pelo grafo real do app, então roteamento,
 * telemetria e gravação nas três tabelas acontecem como no uso normal.
 *
 * ```bash
 * adb shell am instrument -w \
 *   -e class com.voiceassistant.feature_benchmark.BenchmarkBatteryTest \
 *   -e questionsPerArea 20 -e repetitions 3 -e label dev1-gemma4-offline \
 *   com.voiceassistant.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * O cenário (offline / LAN / internet) é definido pelo **estado do aparelho** antes de
 * rodar — modo avião, servidor na rede, ou internet. O `routing_log` grava a
 * conectividade observada em cada linha, então o cenário fica registrado no dado e não
 * depende de anotação externa.
 *
 * Os defaults são pequenos de propósito: rodar sem parâmetros faz uma passagem curta de
 * verificação, não uma coleta de horas por engano.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkBatteryTest {

    @Test
    fun rodaBateria() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext

        assumeTrue(
            "Nenhum GGUF no aparelho — envie um com scripts/push-model.ps1",
            hasModel(context)
        )

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BenchmarkEntryPoint::class.java
        )

        // Cenário "só tier local". Ligado por padrão: com o aparelho online — e ele está,
        // porque a depuração é por Wi-Fi — o roteador escalaria para a nuvem e, pior,
        // encurtaria o orçamento de tempo do local para 30 s por existir alternativa.
        // Medir o aparelho exige que ele seja a única opção.
        val localOnly = args.getString("localOnly")?.toBooleanStrictOrNull() ?: true
        entryPoint.userSettings().setPrivacyMode(localOnly)
        Log.i(TAG, "modo privacidade (só tier local) = $localOnly")

        // O modelo é carregado pela Application; esperar é obrigatório, senão as
        // primeiras questões iriam para a nuvem e mediriam o tier errado.
        val manager = entryPoint.localModelManager()
        manager.initializeAsync()
        val ready = withTimeoutOrNull(MODEL_LOAD_TIMEOUT_MS) {
            manager.modelState.first { state ->
                if (state is ModelState.Error) {
                    throw AssertionError("carga do modelo falhou: ${state.message}")
                }
                state is ModelState.Ready
            }
        }
        assertTrue(
            "modelo local não ficou pronto em ${MODEL_LOAD_TIMEOUT_MS / 1000}s: " +
                    "${manager.modelState.value}",
            ready != null
        )
        Log.i(TAG, "modelo pronto: ${manager.activeVariant?.label}")

        val config = BenchmarkConfig(
            runLabel = args.getString("label") ?: DEFAULT_LABEL,
            questionsPerArea = args.getString("questionsPerArea")?.toIntOrNull() ?: 1,
            repetitions = args.getString("repetitions")?.toIntOrNull() ?: 1,
            blockSize = args.getString("blockSize")?.toIntOrNull() ?: 20,
            warmupRuns = args.getString("warmupRuns")?.toIntOrNull() ?: 1,
            cooldownBetweenBlocksMs =
                args.getString("cooldownBlockMs")?.toLongOrNull() ?: 60_000L
        )
        Log.i(TAG, "config: $config")

        val report = entryPoint.benchmarkRunner().run(config)

        report.blocks.forEach { block ->
            Log.i(
                TAG,
                "bloco: %d questões em %ds | %d µAh (%.1f/questão) | ΔT %.1f°C | válido=%s"
                    .format(
                        block.questions, block.durationMs / 1000, block.consumedUah,
                        block.perQuestionUah, block.temperatureDeltaCelsius, block.isValid
                    )
            )
        }
        report.errors.forEach { Log.w(TAG, "falha: $it") }
        // Estado das respostas, não acurácia: a acurácia sai da classificação manual
        // do `answers.csv`. O que importa aqui é se sobrou material classificável.
        Log.i(TAG, "respostas: ${report.responses}")

        // Exporta os CSVs para o diretório externo do app, de onde saem por adb pull.
        val exportDir = File(context.getExternalFilesDir(null), EXPORT_DIR).apply { mkdirs() }
        entryPoint.routingLogger().exportAll().forEach { (name, content) ->
            File(exportDir, "${config.runLabel}-$name").writeText(content)
        }
        Log.i(TAG, "CSVs exportados em ${exportDir.absolutePath}")

        assertTrue("nenhum bloco executado", report.blocks.isNotEmpty())
    }

    private fun hasModel(context: android.content.Context): Boolean {
        val dirs = listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir("models")
        )
        return dirs.any { dir ->
            dir.listFiles()?.any { it.isFile && it.name.endsWith(".gguf") && it.length() > 0 } == true
        }
    }

    private companion object {
        const val TAG = "BenchmarkBattery"
        const val DEFAULT_LABEL = "verificacao"
        const val EXPORT_DIR = "exports"
        const val MODEL_LOAD_TIMEOUT_MS = 180_000L
    }
}
