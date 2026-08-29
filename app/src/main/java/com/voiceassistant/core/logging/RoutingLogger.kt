package com.voiceassistant.core.logging

import android.util.Log
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.InferenceTelemetry
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.model.TutorMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grava os logs de pesquisa no Room e os exporta em CSV.
 *
 * São **três** arquivos, casáveis por `device_id` (e por janela de `timestamp` com o log
 * de GPU do servidor): `routing_log` (uma linha por pergunta), `model_load_log` (uma por
 * carregamento) e `device_profile` (uma por aparelho). Separá-los evita repetir a ficha
 * do aparelho em cada linha e mantém as falhas de carga visíveis mesmo quando não houve
 * pergunta nenhuma — que é o caso do aparelho onde o modelo não coube.
 *
 * A `routeDecision` é recebida como String (e não como o enum `RoutingDecision`) para
 * manter a camada `core` desacoplada de `feature_tutor`.
 */
@Singleton
class RoutingLogger @Inject constructor(
    private val dao: RoutingLogDao,
    private val modelLoadDao: ModelLoadLogDao,
    private val deviceProfileDao: DeviceProfileDao,
    private val blockEnergyDao: BlockEnergyDao
) {
    suspend fun log(
        sessionId: String,
        questionText: String,
        complexity: PromptComplexity,
        routeDecision: String,
        confidence: Float,
        confidenceMethod: String,
        finalSource: InferenceSource,
        mode: TutorMode,
        latencyMs: Long,
        modelId: String,
        connectivity: String,
        deviceId: String = "",
        telemetry: InferenceTelemetry? = null,
        blockId: String? = null,
        runIndex: Int? = null,
        questionId: String? = null,
        questionYear: Int? = null,
        questionArea: String? = null,
        responseText: String = "",
        expectedAnswer: String? = null,
        predictedAnswer: String? = null,
        answerMethod: String? = null,
        isCorrect: Int = RoutingLogEntry.UNAVAILABLE_INT
    ) {
        dao.insert(
            RoutingLogEntry(
                sessionId = sessionId,
                questionText = questionText,
                complexityPreFilter = complexity.name,
                routeDecision = routeDecision,
                confidenceScore = confidence,
                confidenceMethod = confidenceMethod,
                finalTier = finalSource.name,
                pedagogicalMode = mode.name,
                latencyMs = latencyMs,
                modelId = modelId,
                connectivity = connectivity,
                deviceId = deviceId,
                runtime = telemetry?.runtime,
                promptTokens = telemetry?.promptTokens ?: RoutingLogEntry.UNAVAILABLE_INT,
                generatedTokens = telemetry?.generatedTokens ?: RoutingLogEntry.UNAVAILABLE_INT,
                reasoningTokens = telemetry?.reasoningTokens ?: RoutingLogEntry.UNAVAILABLE_INT,
                ttftMs = telemetry?.ttftMs ?: RoutingLogEntry.UNAVAILABLE_DOUBLE,
                ingestionMs = telemetry?.ingestionMs ?: RoutingLogEntry.UNAVAILABLE_DOUBLE,
                generationMs = telemetry?.generationMs ?: RoutingLogEntry.UNAVAILABLE_DOUBLE,
                tokensPerSec = telemetry?.generatedTokensPerSec
                    ?: RoutingLogEntry.UNAVAILABLE_DOUBLE,
                peakProcessRamMb = telemetry?.peakProcessRamMb ?: RoutingLogEntry.UNAVAILABLE_LONG,
                threads = telemetry?.threads ?: RoutingLogEntry.UNAVAILABLE_INT,
                backends = telemetry?.backends,
                stopReason = telemetry?.stopReason,
                truncated = telemetry?.truncated ?: false,
                blockId = blockId,
                runIndex = runIndex,
                questionId = questionId,
                questionYear = questionYear,
                questionArea = questionArea,
                responseText = responseText,
                expectedAnswer = expectedAnswer,
                predictedAnswer = predictedAnswer,
                answerMethod = answerMethod,
                isCorrect = isCorrect
            )
        )
    }

    /**
     * Registra a energia de um bloco (H5).
     *
     * Falha de gravação é avisada e não propaga: perder a linha de energia é ruim, mas
     * derrubar a bateria de medição no meio por causa dela é pior — as questões do bloco
     * já estão na `routing_log`.
     */
    suspend fun logBlockEnergy(entry: BlockEnergyEntry) {
        runCatching { blockEnergyDao.insert(entry) }
            .onFailure { Log.w(TAG, "Falha ao registrar energia do bloco: ${it.message}") }
    }

    /** Registra uma tentativa de carga — inclusive as que falharam (H6/H7). */
    suspend fun logModelLoad(entry: ModelLoadLogEntry) {
        runCatching { modelLoadDao.insert(entry) }
            .onFailure { Log.w(TAG, "Falha ao registrar carga de modelo: ${it.message}") }
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /** CSV da `routing_log` — uma linha por pergunta. */
    suspend fun exportCsv(): String {
        val entries = dao.getAll()
        return buildString {
            append(ROUTING_HEADER)
            for (e in entries) {
                append('\n')
                append(e.timestamp).append(',')
                append(csv(e.sessionId)).append(',')
                append(csv(e.deviceId)).append(',')
                append(csv(e.questionText)).append(',')
                append(e.complexityPreFilter).append(',')
                append(e.routeDecision).append(',')
                append(e.confidenceScore).append(',')
                append(e.confidenceMethod).append(',')
                append(e.finalTier).append(',')
                append(e.pedagogicalMode).append(',')
                append(e.latencyMs).append(',')
                append(csv(e.modelId)).append(',')
                append(e.connectivity).append(',')
                append(csv(e.runtime.orEmpty())).append(',')
                append(e.promptTokens).append(',')
                append(e.generatedTokens).append(',')
                append(e.reasoningTokens).append(',')
                append(e.ttftMs).append(',')
                append(e.ingestionMs).append(',')
                append(e.generationMs).append(',')
                append(e.tokensPerSec).append(',')
                append(e.peakProcessRamMb).append(',')
                append(e.threads).append(',')
                append(csv(e.backends.orEmpty())).append(',')
                append(csv(e.stopReason.orEmpty())).append(',')
                append(if (e.truncated) 1 else 0).append(',')
                append(csv(e.blockId.orEmpty())).append(',')
                append(e.runIndex?.toString().orEmpty()).append(',')
                append(csv(e.questionId.orEmpty())).append(',')
                append(e.questionYear?.toString().orEmpty()).append(',')
                append(csv(e.questionArea.orEmpty())).append(',')
                append(csv(e.expectedAnswer.orEmpty())).append(',')
                append(csv(e.predictedAnswer.orEmpty())).append(',')
                append(csv(e.answerMethod.orEmpty())).append(',')
                append(e.isCorrect).append(',')
                append(csv(e.responseText))
            }
        }
    }

    /**
     * CSV de trabalho para a **classificação manual** das respostas.
     *
     * Separado do `routing_log` porque tem uso diferente: aquele é para análise
     * estatística e tem mais de trinta colunas; este é para uma pessoa ler a pergunta,
     * ler a resposta e decidir qual alternativa o modelo escolheu.
     *
     * Não traz palpite de extração automática, de propósito. Uma sugestão gerada por
     * regex ao lado da coluna de preenchimento ancora quem classifica e, pior, tende a
     * ser usada como resultado quando ninguém preenche. Medido no Qwen: 2 de 4 sugestões
     * apontavam alternativas que o modelo tinha acabado de descartar.
     *
     * `id` é a chave de volta para o `routing_log`, e `stop_reason` avisa quais respostas
     * estão truncadas — essas não têm conclusão para classificar.
     */
    suspend fun exportAnswersCsv(): String {
        val entries = dao.getAll()
        return buildString {
            append(ANSWERS_HEADER)
            for (e in entries) {
                append('\n')
                append(e.id).append(',')
                append(csv(e.deviceId)).append(',')
                append(csv(e.sessionId)).append(',')
                append(csv(e.blockId.orEmpty())).append(',')
                append(csv(e.questionId.orEmpty())).append(',')
                append(e.questionYear?.toString().orEmpty()).append(',')
                append(csv(e.questionArea.orEmpty())).append(',')
                append(e.runIndex?.toString().orEmpty()).append(',')
                append(e.finalTier).append(',')
                append(csv(e.modelId)).append(',')
                append(csv(e.stopReason.orEmpty())).append(',')
                append(csv(e.expectedAnswer.orEmpty())).append(',')
                // Coluna vazia: é a que você preenche com a alternativa que leu.
                append(',')
                append(csv(e.questionText)).append(',')
                append(csv(e.responseText))
            }
        }
    }

    /** CSV da `model_load_log` — uma linha por carregamento, sucesso ou falha. */
    suspend fun exportModelLoadCsv(): String {
        val entries = modelLoadDao.getAll()
        return buildString {
            append(MODEL_LOAD_HEADER)
            for (e in entries) {
                append('\n')
                append(e.timestamp).append(',')
                append(csv(e.deviceId)).append(',')
                append(csv(e.modelId)).append(',')
                append(e.modelSizeBytes).append(',')
                append(e.loadMs).append(',')
                append(e.warmupMs).append(',')
                append(e.outcome).append(',')
                append(csv(e.reason.orEmpty())).append(',')
                append(csv(e.runtime)).append(',')
                append(csv(e.abi)).append(',')
                append(e.threads).append(',')
                append(e.contextSize).append(',')
                append(csv(e.backends.orEmpty())).append(',')
                append(if (e.vulkanEnabled) 1 else 0).append(',')
                append(e.totalRamMb).append(',')
                append(e.availableRamMb)
            }
        }
    }

    /** CSV da `device_profile` — uma linha por aparelho (Table 1 do artigo). */
    suspend fun exportDeviceProfileCsv(): String {
        val entries = deviceProfileDao.getAll()
        return buildString {
            append(DEVICE_PROFILE_HEADER)
            for (e in entries) {
                append('\n')
                append(csv(e.deviceId)).append(',')
                append(csv(e.label)).append(',')
                append(csv(e.manufacturer)).append(',')
                append(csv(e.model)).append(',')
                append(csv(e.deviceName)).append(',')
                append(csv(e.androidVersion)).append(',')
                append(e.apiLevel).append(',')
                append(csv(e.soc)).append(',')
                append(csv(e.abi)).append(',')
                append(e.totalRamMb).append(',')
                append(e.availableRamMb).append(',')
                append(e.nominalRamGb).append(',')
                append(e.extendedRamGb).append(',')
                append(e.cpuCores).append(',')
                append(e.cpuMaxGhz).append(',')
                append(e.availableStorageMb).append(',')
                append(e.capturedAt).append(',')
                append(csv(e.notes.orEmpty()))
            }
        }
    }

    /** CSV da `block_energy` — uma linha por bloco, casável por `block_id`. */
    suspend fun exportBlockEnergyCsv(): String {
        val entries = blockEnergyDao.getAll()
        return buildString {
            append(BLOCK_ENERGY_HEADER)
            for (e in entries) {
                append('\n')
                append(csv(e.blockId)).append(',')
                append(csv(e.deviceId)).append(',')
                append(csv(e.modelId)).append(',')
                append(csv(e.scenario)).append(',')
                append(e.questions).append(',')
                append(e.chargeStartUah).append(',')
                append(e.chargeEndUah).append(',')
                append(e.energyUahTotal).append(',')
                append(e.energyUahPerQuestion).append(',')
                append(e.capacityStartPercent).append(',')
                append(e.capacityEndPercent).append(',')
                append(e.tempStartCelsius).append(',')
                append(e.tempEndCelsius).append(',')
                append(e.deltaTempCelsius).append(',')
                append(e.timestampStart).append(',')
                append(e.timestampEnd).append(',')
                append(e.durationMs).append(',')
                append(if (e.charging) 1 else 0).append(',')
                append(if (e.valid) 1 else 0)
            }
        }
    }

    /** Os CSVs de uma vez, prontos para gravar em arquivo. */
    suspend fun exportAll(): Map<String, String> = mapOf(
        "routing_log.csv" to exportCsv(),
        "model_load_log.csv" to exportModelLoadCsv(),
        "device_profile.csv" to exportDeviceProfileCsv(),
        "answers.csv" to exportAnswersCsv(),
        "block_energy.csv" to exportBlockEnergyCsv()
    )

    /** Envolve em aspas e escapa aspas internas, neutralizando vírgulas e quebras de linha. */
    private fun csv(value: String): String {
        val sanitized = value.replace("\"", "\"\"")
        return "\"$sanitized\""
    }

    companion object {
        private const val TAG = "RoutingLogger"

        private const val ROUTING_HEADER =
            "timestamp,session_id,device_id,question,complexity,route,confidence,method," +
                "tier,mode,latency_ms,model,connectivity,runtime,prompt_tokens," +
                "generated_tokens,reasoning_tokens,ttft_ms,ingestion_ms,generation_ms," +
                "tokens_per_sec,peak_ram_mb,threads,backends,stop_reason,truncated," +
                "block_id,run_index,question_id,question_year,question_area,expected_answer," +
                "predicted_answer,answer_method,is_correct,response"

        /** `manual_answer` sai vazia de propósito: é a coluna que a pessoa preenche. */
        private const val ANSWERS_HEADER =
            "id,device_id,session_id,block_id,question_id,question_year,question_area," +
                "run_index,tier,model,stop_reason,expected_answer,manual_answer," +
                "question,response"

        private const val MODEL_LOAD_HEADER =
            "timestamp,device_id,model,model_size_bytes,load_ms,warmup_ms,outcome,reason," +
                "runtime,abi,threads,context_size,backends,vulkan,total_ram_mb,available_ram_mb"

        private const val BLOCK_ENERGY_HEADER =
            "block_id,device_id,model,scenario,n_questions,charge_start_uah," +
                "charge_end_uah,energy_uah_total,energy_uah_per_question," +
                "capacity_start_pct,capacity_end_pct,temp_start_c,temp_end_c," +
                "delta_temp_c,timestamp_start,timestamp_end,duration_ms,charging,valid"

        private const val DEVICE_PROFILE_HEADER =
            "device_id,label,manufacturer,model,device_name,android_version,api_level,soc," +
                "abi,total_ram_mb,available_ram_mb,nominal_ram_gb,extended_ram_gb,cpu_cores," +
                "cpu_max_ghz,available_storage_mb,captured_at,notes"
    }
}
