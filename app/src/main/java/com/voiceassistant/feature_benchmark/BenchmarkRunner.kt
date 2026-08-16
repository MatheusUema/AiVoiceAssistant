package com.voiceassistant.feature_benchmark

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.telemetry.BlockEnergy
import com.voiceassistant.core.telemetry.EnergyMeter
import com.voiceassistant.domain.repository.InferenceRepository
import com.voiceassistant.feature_benchmark.data.EnemDataset
import com.voiceassistant.feature_benchmark.data.EnemPromptBuilder
import com.voiceassistant.feature_benchmark.data.EnemQuestion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Executa a bateria de medição (Fase 4 do plano).
 *
 * Chama `InferenceRepository.infer()` em laço, o que reusa **todo** o caminho de
 * inferência e de log: roteamento, telemetria de hardware e gravação na `routing_log`
 * acontecem exatamente como no uso normal. Duplicar esse caminho aqui produziria números
 * que não correspondem ao que o app faz de verdade.
 *
 * O protocolo (doc 04 §8) exige: warm-up descartado, k repetições por questão, blocos de
 * N questões para a energia, e intervalo entre execuções para controle térmico.
 */
@Singleton
class BenchmarkRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataset: EnemDataset,
    private val promptBuilder: EnemPromptBuilder,
    private val inferenceRepository: InferenceRepository,
    private val energyMeter: EnergyMeter
) {
    private val _progress = MutableStateFlow<BenchmarkProgress>(BenchmarkProgress.Idle)
    val progress: StateFlow<BenchmarkProgress> = _progress.asStateFlow()

    /**
     * Roda a bateria. Suspende até terminar; cancelar a corrotina interrompe entre
     * questões, preservando o que já foi gravado.
     *
     * @return o resumo por bloco. As linhas por questão já estão na `routing_log`.
     */
    suspend fun run(config: BenchmarkConfig): BenchmarkReport = withWakeLock {
        runBattery(config)
    }

    /**
     * Mantém a CPU acordada durante toda a bateria.
     *
     * Sem isto o aparelho entra em Doze com a tela apagada e **suspende a coleta no meio**
     * — observado no Device 1: processo vivo, 0% de CPU, cinco minutos sem avançar. Numa
     * coleta de horas isso invalidaria a execução inteira, e em silêncio.
     *
     * É um wake lock **parcial** de propósito: manter a tela ligada resolveria o problema
     * mas somaria o consumo do display a H5, que é justamente o que se quer medir do
     * modelo. Aqui a CPU fica acordada e a tela apagada.
     */
    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        return try {
            // Sem timeout: a bateria pode durar horas, e um limite curto reintroduziria
            // o problema no meio da coleta. O release no finally é a garantia.
            wakeLock.acquire()
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun runBattery(config: BenchmarkConfig): BenchmarkReport {
        val questions = dataset.balancedSample(config.questionsPerArea, config.sampleSeed)
        if (questions.isEmpty()) {
            _progress.value = BenchmarkProgress.Failed("Nenhuma questão carregada do dataset")
            return BenchmarkReport(config, emptyList(), emptyList())
        }

        Log.i(TAG, "Bateria iniciada: ${questions.size} questões × k=${config.repetitions}")

        // Warm-up descartado: a primeira inferência paga alocação de buffers e caches
        // frios, e entraria na média como se fosse custo normal.
        runWarmup(config, questions.first())

        val plan = buildPlan(questions, config)
        val blocks = mutableListOf<BlockEnergy>()
        val errors = mutableListOf<String>()
        var completed = 0

        plan.chunked(config.blockSize).forEachIndexed { blockIndex, block ->
            if (!coroutineContext.isActive) return@forEachIndexed

            val blockId = "${config.runLabel}-b${blockIndex.toString().padStart(3, '0')}"
            val energyStart = energyMeter.snapshot()

            for (item in block) {
                if (!coroutineContext.isActive) break

                _progress.value = BenchmarkProgress.Running(
                    completed = completed,
                    total = plan.size,
                    blockId = blockId,
                    questionId = item.question.id
                )

                runCatching { infer(item, blockId, config) }
                    .onFailure { error ->
                        // Uma questão que falha não pode derrubar a bateria: o erro é
                        // registrado e a coleta segue. Perder 4 horas de coleta por uma
                        // exceção numa questão seria muito pior que perder a questão.
                        val message = "${item.question.id}#${item.runIndex}: ${error.message}"
                        Log.w(TAG, "Falha na inferência — $message")
                        errors += message
                    }

                completed++
                delay(config.cooldownBetweenRunsMs)
            }

            val energy = energyMeter.between(energyStart, energyMeter.snapshot(), block.size)
            blocks += energy
            Log.i(
                TAG,
                "Bloco $blockId: ${block.size} questões, ${energy.consumedUah}µAh " +
                        "(%.1f µAh/questão), ΔT %.1f°C, válido=%s".format(
                            energy.perQuestionUah, energy.temperatureDeltaCelsius, energy.isValid
                        )
            )

            // Pausa entre blocos para o aparelho esfriar: sem isso, os últimos blocos
            // medem throttling térmico em vez de desempenho.
            if (coroutineContext.isActive) delay(config.cooldownBetweenBlocksMs)
        }

        _progress.value = BenchmarkProgress.Done(completed, plan.size, errors.size)
        Log.i(TAG, "Bateria concluída: $completed/${plan.size}, ${errors.size} falhas")
        return BenchmarkReport(config, blocks, errors)
    }

    private suspend fun infer(item: PlanItem, blockId: String, config: BenchmarkConfig) {
        inferenceRepository.infer(
            InferenceRequest(
                prompt = promptBuilder.build(item.question),
                sessionId = config.runLabel,
                // Prompt cru: a questão já vem no formato do artigo 1.
                rawPrompt = true,
                // SIMPLE mantém a rota previsível; a complexidade real do item está no
                // `difficulty_score` do dataset, não nesta heurística de texto.
                complexity = PromptComplexity.SIMPLE,
                blockId = blockId,
                runIndex = item.runIndex
            )
        )
    }

    private suspend fun runWarmup(config: BenchmarkConfig, sample: EnemQuestion) {
        repeat(config.warmupRuns) { index ->
            runCatching {
                inferenceRepository.infer(
                    InferenceRequest(
                        prompt = promptBuilder.build(sample),
                        sessionId = "${config.runLabel}-warmup",
                        rawPrompt = true,
                        complexity = PromptComplexity.SIMPLE,
                        // blockId nulo marca a linha como descartável na análise.
                        runIndex = -1
                    )
                )
            }.onFailure { Log.w(TAG, "Warm-up $index falhou (não-fatal): ${it.message}") }
        }
    }

    /**
     * Ordem de execução: as k repetições de uma questão ficam **separadas**, não seguidas.
     *
     * Repetir a mesma questão três vezes em sequência mediria o cache quente do KV e do
     * alocador, não três amostras independentes. Intercalar por rodada aproxima as
     * repetições de execuções realmente distintas.
     */
    private fun buildPlan(questions: List<EnemQuestion>, config: BenchmarkConfig): List<PlanItem> =
        (0 until config.repetitions).flatMap { round ->
            val ordered = if (config.shuffleBetweenRounds) {
                questions.shuffled(kotlin.random.Random(config.sampleSeed + round))
            } else {
                questions
            }
            ordered.map { PlanItem(it, round) }
        }

    private data class PlanItem(val question: EnemQuestion, val runIndex: Int)

    private companion object {
        const val TAG = "BenchmarkRunner"
        const val WAKE_LOCK_TAG = "VoiceAssistant::Benchmark"
    }
}

/** Parâmetros da bateria — todos precisam ser declarados no protocolo. */
data class BenchmarkConfig(
    /** Identifica esta execução no `routing_log` (vira `sessionId`). */
    val runLabel: String,

    /** Questões por área (4 áreas). 20 → 80 questões. */
    val questionsPerArea: Int = 20,

    /** k: repetições de cada questão. Mediana e dispersão saem daqui. */
    val repetitions: Int = 3,

    /** N: questões por bloco de energia. Ver [EnergyMeter] para o porquê de agrupar. */
    val blockSize: Int = 20,

    /** Inferências descartadas no início, para não medir caches frios. */
    val warmupRuns: Int = 2,

    /** Pausa entre questões. */
    val cooldownBetweenRunsMs: Long = 500,

    /** Pausa entre blocos, para o aparelho esfriar antes do próximo. */
    val cooldownBetweenBlocksMs: Long = 60_000,

    /** Embaralha a ordem a cada rodada, para a posição não virar variável oculta. */
    val shuffleBetweenRounds: Boolean = true,

    /** Seed da amostra: a mesma seleção em todos os aparelhos e modelos. */
    val sampleSeed: Long = EnemDataset.DEFAULT_SEED
)

/** Resumo da bateria. As linhas por questão ficam na `routing_log`. */
data class BenchmarkReport(
    val config: BenchmarkConfig,
    val blocks: List<BlockEnergy>,
    val errors: List<String>
)

sealed interface BenchmarkProgress {
    data object Idle : BenchmarkProgress
    data class Running(
        val completed: Int,
        val total: Int,
        val blockId: String,
        val questionId: String
    ) : BenchmarkProgress

    data class Done(val completed: Int, val total: Int, val failures: Int) : BenchmarkProgress
    data class Failed(val reason: String) : BenchmarkProgress
}
