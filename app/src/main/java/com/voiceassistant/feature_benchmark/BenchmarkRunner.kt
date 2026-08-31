package com.voiceassistant.feature_benchmark

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.core.device.DeviceProfileProvider
import com.voiceassistant.core.logging.BlockEnergyEntry
import com.voiceassistant.core.logging.RoutingLogDao
import com.voiceassistant.core.logging.RoutingLogger
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
    private val energyMeter: EnergyMeter,
    private val routingLogDao: RoutingLogDao,
    private val routingLogger: RoutingLogger,
    private val deviceProfileProvider: DeviceProfileProvider,
    // Para registrar na `block_energy` qual modelo de fato respondeu — se o fallback
    // assumiu, é dele a energia medida.
    private val localService: LocalInferenceService
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

        // O warm-up acabou de gravar linhas — se elas não estão lá, nada do que vier
        // depois estará. Abortar aqui custa 30 segundos; descobrir no fim custou uma
        // coleta inteira: 242 inferências, 2 horas e metade da bateria do aparelho
        // rodaram gravando em nada, porque `logRouting` engole exceções de escrita (o
        // que é correto no app: uma falha de log não pode derrubar a resposta do aluno)
        // e o banco recusava toda operação por divergência de schema.
        verificarPersistencia()?.let { motivo ->
            _progress.value = BenchmarkProgress.Failed(motivo)
            Log.e(TAG, "Bateria abortada: $motivo")
            return BenchmarkReport(config, emptyList(), listOf(motivo))
        }

        val plan = removerJaColetadas(buildPlan(questions, config), config)
        if (plan.isEmpty()) {
            _progress.value = BenchmarkProgress.Done(0, 0, 0)
            Log.i(TAG, "Nada a coletar: o resume nao encontrou itens faltantes")
            return BenchmarkReport(config, emptyList(), emptyList())
        }
        if (config.planOnly) {
            // Ensaio: calcula o plano e para. Serve para conferir o conjunto faltante
            // ANTES de comprometer horas de aparelho e uma carga de bateria.
            Log.i(TAG, "planOnly: ${plan.size} itens seriam executados; encerrando sem inferir")
            _progress.value = BenchmarkProgress.Done(0, plan.size, 0)
            return BenchmarkReport(config, emptyList(), emptyList())
        }
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
            persistBlockEnergy(blockId, energy, config)
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

        val responses = tallyResponses(config.runLabel)
        _progress.value = BenchmarkProgress.Done(completed, plan.size, errors.size)
        Log.i(TAG, "Bateria concluída: $completed/${plan.size}, ${errors.size} falhas")
        Log.i(TAG, "Respostas: $responses")
        return BenchmarkReport(config, blocks, errors, responses)
    }

    /**
     * Remove do plano o que a sessão [BenchmarkConfig.resumeFromSession] já coletou.
     *
     * Existe porque uma coleta pode morrer no meio — no Device 3 a bateria acabou com 94
     * das 160 inferências feitas. Sem isto, retomar significaria repetir as 94 e gastar
     * outra carga inteira; com isto, roda só o que falta.
     *
     * A chave é `(questionId, questionYear, runIndex)`, a mesma da análise. O ano não é
     * opcional: no dataset são 540 linhas para 180 ids, cada um repetido nos três anos.
     *
     * O plano vem de [buildPlan], que depende apenas de `sampleSeed`, `questionsPerArea` e
     * `repetitions` — então, com a mesma config, as questões restantes são exatamente as
     * que faltam, na mesma ordem relativa.
     *
     * Se execuções distintas compartilharem o mesmo label (aconteceu no Device 3, com duas
     * tentativas mortas pela MIUI gravando sob `dev3-qwen`), o conjunto "já coletado"
     * inclui as linhas delas. Ali foi inofensivo — as chaves das tentativas mortas são
     * subconjunto das da execução boa — mas em geral vale um label novo por execução.
     */
    private suspend fun removerJaColetadas(
        plan: List<PlanItem>,
        config: BenchmarkConfig
    ): List<PlanItem> {
        val sessao = config.resumeFromSession ?: return plan
        val feitas = runCatching {
            routingLogDao.getBySession(sessao)
                .filter { (it.runIndex ?: -1) >= 0 }
                .map { Triple(it.questionId, it.questionYear, it.runIndex) }
                .toSet()
        }.getOrElse { erro ->
            // Falhar aqui e rodar tudo de novo seria pior que parar: a coleta duplicada
            // gastaria a carga inteira do aparelho sem que ninguém percebesse.
            Log.e(TAG, "Resume abortado: não foi possível ler '$sessao' (${erro.message})")
            return emptyList()
        }
        val restante = plan.filterNot {
            Triple(it.question.id, it.question.year, it.runIndex) in feitas
        }
        Log.i(
            TAG,
            "Resume de '$sessao': ${feitas.size} já coletadas, " +
                    "${restante.size} de ${plan.size} restantes"
        )
        return restante
    }

    /**
     * Grava a energia do bloco na tabela, e não só no relatório em memória.
     *
     * Antes disto o valor existia apenas no logcat: na coleta do Device 1 três dos doze
     * blocos se perderam quando o buffer rotacionou, e recuperá-los exigiria repetir
     * duas horas de medição. O `blockId` é a chave de join com a `routing_log`, que já
     * o registra em cada questão.
     */
    private suspend fun persistBlockEnergy(
        blockId: String,
        energy: BlockEnergy,
        config: BenchmarkConfig
    ) {
        routingLogger.logBlockEnergy(
            BlockEnergyEntry(
                blockId = blockId,
                deviceId = deviceProfileProvider.deviceId(),
                // O modelo que de fato respondeu, não o configurado — se o fallback
                // assumiu, é dele a energia medida.
                modelId = localService.loadedModelId.orEmpty(),
                scenario = config.scenario,
                questions = energy.questions,
                chargeStartUah = energy.startChargeUah,
                chargeEndUah = energy.endChargeUah,
                energyUahTotal = energy.consumedUah,
                energyUahPerQuestion = energy.perQuestionUah,
                capacityStartPercent = energy.startCapacityPercent,
                capacityEndPercent = energy.endCapacityPercent,
                tempStartCelsius = energy.startTemperatureCelsius,
                tempEndCelsius = energy.endTemperatureCelsius,
                deltaTempCelsius = energy.temperatureDeltaCelsius,
                timestampStart = energy.startMs,
                timestampEnd = energy.endMs,
                charging = energy.charging,
                valid = energy.isValid
            )
        )
    }

    /**
     * Confere que a `routing_log` está mesmo recebendo linhas.
     *
     * @return o motivo da falha, ou null se a gravação está funcionando.
     */
    private suspend fun verificarPersistencia(): String? =
        runCatching {
            val linhas = routingLogDao.count()
            if (linhas > 0) {
                Log.i(TAG, "Persistência verificada: $linhas linhas na routing_log")
                null
            } else {
                // O warm-up rodou e não gravou nada. Sem exceção visível, porque quem
                // grava captura os erros — então o sintoma é a tabela vazia.
                "o warm-up não gravou nenhuma linha na routing_log; a coleta produziria " +
                    "medições que não seriam salvas (ver avisos de InferenceRouter no logcat)"
            }
        }.getOrElse { erro ->
            // O caso real: divergência de schema faz o Room recusar toda operação.
            "não foi possível ler a routing_log (${erro.message}); " +
                "banco provavelmente com schema divergente — limpe os dados do app ou " +
                "corrija a migração antes de coletar"
        }

    /**
     * Apura o **estado** das respostas coletadas — não a acurácia.
     *
     * Qual alternativa o modelo escolheu é decidido lendo o texto, fora do app. O que
     * dá para verificar aqui, e é o que decide se a coleta presta, é se a resposta saiu
     * inteira: uma geração cortada no meio do raciocínio não tem alternativa nenhuma
     * para classificar, e uma bateria cheia delas é uma bateria perdida. Foi exatamente
     * o caso do Gemma 4 E2B — 4 de 4 truncadas, nenhuma classificável.
     *
     * Lê de volta da tabela em vez de acumular em memória, para que o número do
     * relatório seja exatamente o que está no CSV.
     */
    private suspend fun tallyResponses(sessionId: String): BenchmarkResponses =
        runCatching {
            val rows = routingLogDao.getBySession(sessionId)
            BenchmarkResponses(
                total = rows.size,
                complete = rows.count { !it.truncated && it.responseText.isNotBlank() },
                truncated = rows.count { it.truncated },
                failed = rows.count { it.responseText.startsWith(FAILURE_MARKER) }
            )
        }.getOrElse {
            Log.w(TAG, "Falha ao apurar respostas: ${it.message}")
            BenchmarkResponses()
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
                runIndex = item.runIndex,
                questionId = item.question.id,
                questionYear = item.question.year,
                questionArea = item.question.area,
                // O gabarito viaja junto para que a linha nasça graduada. Casar resposta
                // com gabarito depois exigiria reidentificar a questão pelo texto.
                expectedAnswer = item.question.label
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
        const val FAILURE_MARKER = "[FALHA]"
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
    val sampleSeed: Long = EnemDataset.DEFAULT_SEED,

    /**
     * Cenário declarado da coleta ("local-only", "lan", "internet").
     *
     * Vai para a `block_energy` porque a energia por questão só é comparável entre
     * execuções do mesmo cenário: escalar para a nuvem troca computação local por
     * rádio, e os dois custam de formas diferentes.
     */
    val scenario: String = "local-only",

    /**
     * Se preenchido, roda **apenas** o que esta sessao ainda nao coletou.
     * Ver [BenchmarkRunner.removerJaColetadas].
     */
    val resumeFromSession: String? = null,

    /** Ensaio: calcula o plano, registra o tamanho e encerra sem inferir. */
    val planOnly: Boolean = false
)

/** Resumo da bateria. As linhas por questão ficam na `routing_log`. */
data class BenchmarkReport(
    val config: BenchmarkConfig,
    val blocks: List<BlockEnergy>,
    val errors: List<String>,
    val responses: BenchmarkResponses = BenchmarkResponses()
)

/**
 * Estado das respostas coletadas — **não** é acurácia.
 *
 * A acurácia sai da classificação manual do `answers.csv`. O que este resumo responde é
 * se a coleta produziu material classificável: [complete] é o que dá para ler e decidir,
 * [truncated] é resposta cortada antes de concluir, [failed] é a que nem saiu (timeout).
 * Uma bateria com [complete] baixo é uma bateria a refazer com outro modelo ou outro
 * teto de tokens, e é melhor descobrir isso no fim da execução do que na análise.
 */
data class BenchmarkResponses(
    val total: Int = 0,
    val complete: Int = 0,
    val truncated: Int = 0,
    val failed: Int = 0
) {
    override fun toString(): String =
        if (total == 0) "sem linhas"
        else "%d/%d completas para classificar | %d truncadas | %d sem resposta"
            .format(complete, total, truncated, failed)
}

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
