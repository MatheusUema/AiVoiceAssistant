package com.voiceassistant.core.logging

import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.model.TutorMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grava o log de pesquisa de roteamento no Room e o exporta em CSV.
 *
 * A `routeDecision` é recebida como String (e não como o enum `RoutingDecision`) para
 * manter a camada `core` desacoplada de `feature_tutor` — o InferenceRouter passa
 * `decision.name`.
 */
@Singleton
class RoutingLogger @Inject constructor(
    private val dao: RoutingLogDao
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
        connectivity: String
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
                connectivity = connectivity
            )
        )
    }

    /** Exporta todos os registros como CSV (RFC 4180: aspas duplas escapadas). */
    suspend fun exportCsv(): String {
        val entries = dao.getAll()
        return buildString {
            append(CSV_HEADER)
            for (e in entries) {
                append('\n')
                append(e.timestamp).append(',')
                append(csv(e.sessionId)).append(',')
                append(csv(e.questionText)).append(',')
                append(e.complexityPreFilter).append(',')
                append(e.routeDecision).append(',')
                append(e.confidenceScore).append(',')
                append(e.confidenceMethod).append(',')
                append(e.finalTier).append(',')
                append(e.pedagogicalMode).append(',')
                append(e.latencyMs).append(',')
                append(csv(e.modelId)).append(',')
                append(e.connectivity)
            }
        }
    }

    /** Envolve em aspas e escapa aspas internas, neutralizando vírgulas e quebras de linha. */
    private fun csv(value: String): String {
        val sanitized = value.replace("\"", "\"\"")
        return "\"$sanitized\""
    }

    companion object {
        private const val CSV_HEADER =
            "timestamp,session_id,question,complexity,route,confidence,method," +
                "tier,mode,latency_ms,model,connectivity"
    }
}
