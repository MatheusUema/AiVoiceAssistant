package com.voiceassistant.feature_tutor.policy

import android.util.Log
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.ai_cloud.model.CloudModelConfig
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_server.model.ServerConfig
import com.voiceassistant.ai_server.service.ServerInferenceService
import com.voiceassistant.ai_server.service.ServerResult
import com.voiceassistant.core.model.InferenceTelemetry
import com.voiceassistant.ai_server.service.ServerUnavailableException
import com.voiceassistant.core.device.DeviceProfileProvider
import com.voiceassistant.core.logging.RoutingLogger
import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.InferenceResult
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.network.NetworkMonitor
import com.voiceassistant.core.storage.UserSettingsDataStore
import com.voiceassistant.core.telemetry.ProcessRamSampler
import com.voiceassistant.domain.repository.InferenceRepository
import com.voiceassistant.feature_tutor.prompt.TutorPromptBuilder
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Roteador central de inferência — implementa [InferenceRepository].
 *
 * Arquitetura offline-first: sempre prefere o modelo local quando disponível.
 * O cloud é usado somente quando necessário (complexidade alta) ou como fallback.
 *
 * A lógica de decisão é separada da execução para facilitar testes unitários:
 *  - [resolveRoute] é uma função **pura** que retorna [RoutingDecision]
 *  - [infer] executa a decisão, mede latência e registra o resultado
 *
 * Três tiers, do mais privado/offline ao mais capaz: local (MediaPipe, on-device) →
 * servidor (llama.cpp na LAN, com logprobs/confiança) → cloud (Firebase/Gemini).
 *
 * Regras (em ordem de prioridade):
 *  1. PRIVACIDADE + local → local (dados nunca saem do device; servidor/cloud saem da rede)
 *  2. PRIVACIDADE + sem local → erro
 *  3. OFFLINE total (sem internet e sem servidor na LAN) + local → local
 *  4. OFFLINE total + sem local → erro
 *  5. ONLINE + complexa + cloud → cloud (modelo grande direto)
 *  6. SERVIDOR (LAN) + cloud → servidor com escalonamento p/ cloud se confiança baixa
 *  7. SERVIDOR (LAN) sem cloud → servidor
 *  8. Sem servidor: local + cloud → local com fallback cloud
 *  9. Sem servidor: só local → local
 * 10. Sem servidor/local: cloud → cloud
 * 11. Nada disponível → erro
 *
 * O tier servidor só é considerado quando `UserSettings.serverTierEnabled` e o
 * `llama-server` responde ao health-check; caso contrário o comportamento é idêntico
 * ao anterior (só local/cloud).
 */
@Singleton
class InferenceRouter @Inject constructor(
    private val localService: LocalInferenceService,
    private val cloudService: CloudInferenceService,
    private val serverService: ServerInferenceService,
    private val serverConfig: ServerConfig,
    private val localModelConfig: LocalModelConfig,
    private val cloudModelConfig: CloudModelConfig,
    private val localModelManager: LocalModelManager,
    private val networkMonitor: NetworkMonitor,
    private val userSettingsDataStore: UserSettingsDataStore,
    private val promptBuilder: TutorPromptBuilder,
    private val routingLogger: RoutingLogger,
    private val deviceProfileProvider: DeviceProfileProvider,
    private val ramSampler: ProcessRamSampler = ProcessRamSampler()
) : InferenceRepository {

    override suspend fun infer(request: InferenceRequest): InferenceResult {
        val settings = userSettingsDataStore.settings.first()
        val isOnline = networkMonitor.isCurrentlyOnline()
        val isLocalAvailable = localModelManager.isAvailable
        val isCloudAvailable = isOnline && cloudService.isAvailable
        // O tier servidor é ligado/desligado via settings (fonte de verdade em runtime).
        // Só paga o health-check quando habilitado (evita penalidade de conexão em quem
        // não tem servidor na rede). A URL efetiva vem das settings, com fallback na
        // URL padrão do ServerConfig.
        val effectiveServerBaseUrl = settings.serverBaseUrl.ifBlank { serverConfig.baseUrl }
        // Em modo privacidade a rota é sempre LOCAL — não faz sentido (nem é desejável)
        // pingar o servidor da LAN. Isso também evita latência extra no caminho privado.
        val isServerAvailable = if (settings.serverTierEnabled && !settings.privacyModeEnabled) {
            serverService.configure(effectiveServerBaseUrl)
            serverService.isServerReachable()
        } else {
            false
        }

        val decision = InferenceRouter.resolveRoute(
            isOnline = isOnline,
            isLocalAvailable = isLocalAvailable,
            isServerAvailable = isServerAvailable,
            isCloudAvailable = isCloudAvailable,
            complexity = request.complexity,
            privacyMode = settings.privacyModeEnabled
        )

        val isCompact = decision.usesCompactPrompt
        // O modo teste manda o prompt cru: as questões do ENEM já vêm formatadas, e
        // embrulhá-las em instrução pedagógica mudaria acurácia e custo de prefill.
        val builtPrompt = if (request.rawPrompt) {
            request.prompt
        } else {
            promptBuilder.build(
                userInput = request.prompt,
                history = request.conversationHistory,
                mode = request.tutorMode,
                compact = isCompact
            )
        }

        Log.d(TAG, "Rota: $decision | mode=${request.tutorMode} compact=$isCompact " +
                "online=$isOnline local=$isLocalAvailable server=$isServerAvailable " +
                "cloud=$isCloudAvailable privacy=${settings.privacyModeEnabled} " +
                "complexity=${request.complexity}")

        // A falha é registrada **antes** de propagar. Sem isto, toda inferência que
        // estoura o tempo desaparece do `routing_log`: o log só acontecia depois da
        // execução, então os timeouts existiam apenas no logcat. É justamente o dado
        // mais importante dos aparelhos fracos — "este aparelho não sustenta este
        // modelo" — e a coluna `stopReason` nunca chegava a receber `TIMEOUT`, porque a
        // linha não nascia.
        val started = System.currentTimeMillis()
        val result = try {
            executeDecision(decision, builtPrompt)
        } catch (e: Exception) {
            logFailure(request, decision, e, System.currentTimeMillis() - started,
                isOnline, isServerAvailable, effectiveServerBaseUrl)
            throw e
        }

        logRouting(request, decision, result, isOnline, isServerAvailable, effectiveServerBaseUrl)

        return result
    }

    /**
     * Registra uma inferência que **falhou**, com a telemetria que o tier local tiver
     * conseguido produzir até ser interrompido.
     *
     * Uma geração cortada por timeout já mediu prefill, TTFT e quantos tokens saíram —
     * descartar isso jogaria fora a evidência de quão longe o aparelho chegou. A linha
     * nasce sem resposta e sem graduação (`isCorrect = -1`), que é o correto: não houve
     * resposta para graduar, e isso não é o mesmo que errar a alternativa.
     */
    private suspend fun logFailure(
        request: InferenceRequest,
        decision: RoutingDecision,
        error: Exception,
        latencyMs: Long,
        isOnline: Boolean,
        isServerAvailable: Boolean,
        serverBaseUrl: String
    ) {
        val telemetry = if (decision.targetsLocal) localService.lastTelemetry else null
        logRouting(
            request = request,
            decision = decision,
            result = InferenceResult(
                // O texto guarda o motivo da falha: no CSV, a coluna `response` explica
                // por que aquela questão não tem resposta.
                text = "",
                source = InferenceSource.LOCAL,
                latencyMs = latencyMs,
                telemetry = telemetry
            ),
            isOnline = isOnline,
            isServerAvailable = isServerAvailable,
            serverBaseUrl = serverBaseUrl,
            failureReason = error.message ?: error::class.simpleName.orEmpty()
        )
    }

    /**
     * Registra a interação no log de pesquisa. Falhas de gravação não devem
     * derrubar a inferência — são apenas avisadas.
     */
    private suspend fun logRouting(
        request: InferenceRequest,
        decision: RoutingDecision,
        result: InferenceResult,
        isOnline: Boolean,
        isServerAvailable: Boolean,
        serverBaseUrl: String,
        /** Não-nulo quando a inferência falhou: vai para a coluna `response` do CSV. */
        failureReason: String? = null
    ) {
        val connectivity = when {
            isOnline -> "internet"
            isServerAvailable -> "lan"
            else -> "offline"
        }
        val confidenceMethod =
            if (result.confidence >= 0f) "logprobs_mean" else "none"

        try {
            routingLogger.log(
                sessionId = request.sessionId,
                questionText = request.prompt,
                complexity = request.complexity,
                routeDecision = decision.name,
                confidence = result.confidence,
                confidenceMethod = confidenceMethod,
                finalSource = result.source,
                mode = request.tutorMode,
                latencyMs = result.latencyMs,
                modelId = modelIdFor(result.source, serverBaseUrl),
                connectivity = connectivity,
                deviceId = deviceProfileProvider.deviceId(),
                telemetry = result.telemetry,
                blockId = request.blockId,
                runIndex = request.runIndex,
                questionId = request.questionId,
                questionYear = request.questionYear,
                questionArea = request.questionArea,
                // A resposta vai inteira; a alternativa escolhida **não** é inferida aqui.
                // Determinar qual alternativa o modelo escolheu exige ler o texto: ele
                // percorre e descarta alternativas antes de concluir, às vezes conclui
                // sem citar letra nenhuma ("afetou a membrana plasmática"), e às vezes
                // não conclui. Uma regex sobre isso produz um número plausível e errado
                // — medido: 2 de 4 respostas do Qwen foram atribuídas a alternativas que
                // o modelo tinha acabado de descartar. A classificação é manual.
                responseText = failureReason?.let { "[FALHA] $it" } ?: result.text,
                expectedAnswer = request.expectedAnswer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao registrar log de roteamento: ${e.message}")
        }
    }

    /**
     * Identificador do modelo por tier. Para o servidor, usa a URL **efetiva**
     * (settings, com fallback no default) — não a URL padrão do ServerConfig. O nome
     * do modelo servido não é exposto pela app; registre-o à parte (ver docs/05).
     */
    private fun modelIdFor(source: InferenceSource, serverBaseUrl: String): String = when (source) {
        // O modelo **carregado**, não o configurado: quando o primário não cabe e o
        // fallback assume (Device 2), registrar o configurado seria mentir sobre quem
        // respondeu — bem no caso que o estudo quer medir. Cai na config só se o
        // runtime não souber dizer.
        InferenceSource.LOCAL -> localService.loadedModelId ?: localModelConfig.modelFileName
        InferenceSource.SERVER -> "llama-server@$serverBaseUrl"
        InferenceSource.CLOUD -> cloudModelConfig.modelName
        // FALLBACK é ambíguo (servidor ou cloud, após falha do tier primário).
        InferenceSource.FALLBACK -> "fallback:${cloudModelConfig.modelName}"
    }

    companion object {
        private const val TAG = "InferenceRouter"
        private val TUTOR_PREFIX = Regex("""^Tutor\s*:\s*""", RegexOption.IGNORE_CASE)
        private val ECHO_WITH_TUTOR = Regex(
            """^.{1,500}?\n\s*Tutor\s*:\s*""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        /**
         * Resolve qual rota seguir baseado no estado atual.
         *
         * Esta função é **pura** — não acessa IO, rede, banco ou Android APIs.
         * Todos os inputs são parâmetros, facilitando testes unitários exaustivos.
         * Pode ser chamada via `InferenceRouter.resolveRoute(...)` sem instância.
         */
        fun resolveRoute(
            isOnline: Boolean,
            isLocalAvailable: Boolean,
            isServerAvailable: Boolean,
            isCloudAvailable: Boolean,
            complexity: PromptComplexity,
            privacyMode: Boolean
        ): RoutingDecision = when {
        // Regras 1-2: Modo privacidade — dados nunca saem do dispositivo.
        // Servidor (LAN) e cloud enviam dados para fora → só local é permitido.
        privacyMode && isLocalAvailable -> RoutingDecision.LOCAL
        privacyMode -> RoutingDecision.ERROR_PRIVACY

        // Regras 3-4: Offline total — sem internet E sem servidor na LAN.
        // (O servidor pode estar acessível na LAN mesmo sem internet.)
        !isOnline && !isServerAvailable && isLocalAvailable -> RoutingDecision.LOCAL
        !isOnline && !isServerAvailable -> RoutingDecision.ERROR_OFFLINE

        // Regra 5: Pergunta complexa + cloud → cloud diretamente (modelo grande)
        complexity == PromptComplexity.COMPLEX && isCloudAvailable -> RoutingDecision.CLOUD

        // Regras 6-7: Servidor na LAN disponível → usa servidor (com logprobs).
        // Se houver cloud, escala para cloud quando a confiança for baixa.
        isServerAvailable && isCloudAvailable -> RoutingDecision.SERVER_WITH_CLOUD_ESCALATION
        isServerAvailable -> RoutingDecision.SERVER

        // Regra 8: Sem servidor, local + cloud → local (fallback cloud se falhar)
        isLocalAvailable && isCloudAvailable -> RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK

        // Regra 9: Sem servidor, só local
        isLocalAvailable -> RoutingDecision.LOCAL

        // Regra 10: Sem servidor/local, mas cloud disponível
        isCloudAvailable -> RoutingDecision.CLOUD

        // Regra 11: Nada
        else -> RoutingDecision.ERROR_UNAVAILABLE
        }
    }

    // ── Execução (side-effects reais) ─────────────────────────────────────

    private suspend fun executeDecision(
        decision: RoutingDecision,
        prompt: String
    ): InferenceResult = when (decision) {
        RoutingDecision.LOCAL -> runLocal(prompt)
        RoutingDecision.CLOUD -> runCloud(prompt)
        RoutingDecision.SERVER -> runServer(prompt, allowCloudEscalation = false)
        RoutingDecision.SERVER_WITH_CLOUD_ESCALATION -> runServer(prompt, allowCloudEscalation = true)
        RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK -> runLocalWithCloudFallback(prompt)
        RoutingDecision.LOCAL_WITH_SERVER_FALLBACK -> runLocalWithServerFallback(prompt)

        RoutingDecision.ERROR_PRIVACY -> throw PrivacyModeException(
            "Modo privacidade ativo e modelo local indisponível. " +
                    "Desative o modo privacidade ou configure o modelo offline."
        )
        RoutingDecision.ERROR_OFFLINE -> throw InferenceUnavailableException(
            "Sem conexão com a internet e modelo local indisponível. " +
                    "Conecte-se à internet ou configure o modelo offline."
        )
        RoutingDecision.ERROR_UNAVAILABLE -> throw InferenceUnavailableException(
            "Nenhum serviço de IA configurado. " +
                    "Configure o Firebase AI Logic ou o modelo local offline."
        )
    }

    /**
     * @param hasFallback true quando existe outro tier para onde escalar. Encurta o
     *   orçamento de tempo do local: com alternativa disponível, esperar o timeout
     *   longo e só então chamar a nuvem soma as latências e piora a resposta.
     */
    private suspend fun runLocal(prompt: String, hasFallback: Boolean = false): InferenceResult {
        val start = System.currentTimeMillis()
        val budgetMs = if (hasFallback) {
            localModelConfig.generationTimeoutWithFallbackMs
        } else {
            localModelConfig.generationTimeoutMs
        }
        // O pico de RAM (H4) tem que ser amostrado *durante* a geração: os buffers de
        // compute nascem e morrem ao longo do decode, então ler no fim perderia o pico.
        val (raw, peakRamMb) = ramSampler.measurePeak { localService.generate(prompt, budgetMs) }
        val latency = System.currentTimeMillis() - start

        val telemetry = localService.lastTelemetry?.copy(peakProcessRamMb = peakRamMb)

        Log.i(
            TAG,
            "LOCAL concluído em ${latency}ms" + (telemetry?.let {
                " | ${it.promptTokens}→${it.generatedTokens} tok, " +
                        "TTFT ${it.ttftMs.toInt()}ms, " +
                        "${"%.1f".format(it.generatedTokensPerSec)} tok/s, " +
                        "pico RAM ${it.peakProcessRamMb}MB"
            } ?: "")
        )

        return InferenceResult(
            text = cleanResponse(raw),
            source = InferenceSource.LOCAL,
            latencyMs = latency,
            telemetry = telemetry,
            // O tier local passou a expor logprobs (mesma fórmula do servidor), então a
            // confiança deixa de ser -1 aqui. Isso **não** liga escalonamento por
            // confiança no caminho local: `resolveRoute` decide a rota antes de gerar,
            // e a política de escalonamento por confiança é do tier servidor. Aqui o
            // valor é para o log — mudar a política é decisão à parte, com dados.
            confidence = telemetry?.confidence ?: InferenceResult.CONFIDENCE_UNAVAILABLE
        )
    }

    private suspend fun runCloud(prompt: String): InferenceResult {
        val start = System.currentTimeMillis()
        val raw = cloudService.generate(prompt)
        val latency = System.currentTimeMillis() - start
        Log.i(TAG, "CLOUD concluído em ${latency}ms")
        return InferenceResult(text = cleanResponse(raw), source = InferenceSource.CLOUD, latencyMs = latency)
    }

    private suspend fun runLocalWithCloudFallback(prompt: String): InferenceResult {
        return try {
            runLocal(prompt, hasFallback = true)
        } catch (localError: Exception) {
            Log.w(TAG, "FALLBACK: local falhou (${localError.message}), tentando cloud")
            try {
                val start = System.currentTimeMillis()
                val raw = cloudService.generate(prompt)
                val latency = System.currentTimeMillis() - start
                Log.i(TAG, "FALLBACK→CLOUD concluído em ${latency}ms")
                InferenceResult(
                    text = cleanResponse(raw),
                    source = InferenceSource.FALLBACK,
                    latencyMs = latency
                )
            } catch (cloudError: Exception) {
                throw InferenceUnavailableException(
                    "Local: ${localError.message} | Cloud: ${cloudError.message}"
                )
            }
        }
    }

    // ── Tier servidor (llama.cpp) — geração com confiança e escalonamento ────

    /**
     * Gera no tier servidor e decide o destino final pela confiança (logprobs):
     *  - confiança ≥ [ServerConfig.confidenceThresholdHigh] → entrega direta (SERVER)
     *  - confiança < [ServerConfig.confidenceThresholdLow] e cloud disponível
     *    (e [allowCloudEscalation]) → escala para cloud, preservando a confiança medida
     *  - caso contrário (confiança média, ou baixa sem cloud) → entrega SERVER
     *
     * Confiança == [InferenceResult.CONFIDENCE_UNAVAILABLE] (-1, sem logprobs) **não**
     * escalona: entrega SERVER. Isso evita esvaziar o tier servidor caso o formato de
     * `completion_probabilities` do `llama-server` mude e a confiança fique indisponível.
     *
     * Se o servidor cair durante o uso ([ServerUnavailableException]), cai para
     * local/cloud (risco "servidor cai durante uso").
     */
    private suspend fun runServer(
        prompt: String,
        allowCloudEscalation: Boolean
    ): InferenceResult {
        val result = try {
            serverService.generateWithConfidence(prompt)
        } catch (e: ServerUnavailableException) {
            Log.w(TAG, "SERVER indisponível (${e.message}); fallback para local/cloud")
            return runServerFallback(prompt)
        }

        val shouldEscalate = allowCloudEscalation &&
                result.confidence >= 0f &&
                result.confidence < serverConfig.confidenceThresholdLow &&
                cloudService.isAvailable

        return when {
            result.confidence >= serverConfig.confidenceThresholdHigh -> serverResult(result)

            shouldEscalate -> {
                Log.i(TAG, "Confiança baixa (${"%.3f".format(result.confidence)}); " +
                        "escalonando para cloud")
                // Preserva a confiança original medida no servidor para fins de log/pesquisa.
                runCloud(prompt).copy(confidence = result.confidence)
            }

            else -> serverResult(result)
        }
    }

    /** Servidor caiu no meio do uso: tenta local, depois cloud; senão, erro. */
    private suspend fun runServerFallback(prompt: String): InferenceResult {
        if (localModelManager.isAvailable) {
            // Marca como FALLBACK (não LOCAL): o tier primário (servidor) falhou.
            runCatching { return runLocal(prompt, hasFallback = cloudService.isAvailable)
                .copy(source = InferenceSource.FALLBACK) }
                .onFailure { Log.w(TAG, "Fallback local falhou: ${it.message}") }
        }
        if (cloudService.isAvailable) {
            val start = System.currentTimeMillis()
            val raw = cloudService.generate(prompt)
            val latency = System.currentTimeMillis() - start
            Log.i(TAG, "SERVER→FALLBACK cloud concluído em ${latency}ms")
            return InferenceResult(cleanResponse(raw), InferenceSource.FALLBACK, latency)
        }
        throw InferenceUnavailableException(
            "Servidor indisponível e nenhum fallback (local/cloud) disponível."
        )
    }

    private suspend fun runLocalWithServerFallback(prompt: String): InferenceResult {
        return try {
            runLocal(prompt, hasFallback = true)
        } catch (localError: Exception) {
            Log.w(TAG, "FALLBACK: local falhou (${localError.message}), tentando servidor")
            try {
                val result = serverService.generateWithConfidence(prompt)
                InferenceResult(
                    text = cleanResponse(result.text),
                    source = InferenceSource.FALLBACK,
                    latencyMs = result.latencyMs,
                    confidence = result.confidence
                )
            } catch (serverError: Exception) {
                throw InferenceUnavailableException(
                    "Local: ${localError.message} | Server: ${serverError.message}"
                )
            }
        }
    }

    /**
     * Converte o resultado do tier servidor, AGORA COM TELEMETRIA.
     *
     * Ate 2026-09-05 esta funcao devolvia so texto, latencia e confianca, e o
     * `InferenceResult.telemetry` ficava null -- o que fazia toda linha do tier servidor
     * na `routing_log` sair com ingestao, geracao, tokens e tokens/s em -1. O tier
     * respondia, mas era invisivel para a analise de custo, e H11 (latencia de rede)
     * era impossivel de calcular: falta o compute do servidor para subtrair.
     */
    private fun serverResult(result: ServerResult): InferenceResult = InferenceResult(
        text = cleanResponse(result.text),
        source = InferenceSource.SERVER,
        latencyMs = result.latencyMs,
        confidence = result.confidence,
        telemetry = InferenceTelemetry(
            modelId = result.baseUrl?.let { "llama-server@$it" },
            runtime = "llama-server",
            promptTokens = result.promptTokens,
            generatedTokens = result.generatedTokens,
            // O `/completion` nao-streaming nao expoe TTFT: o corpo so chega inteiro. O
            // prefill e o melhor limite inferior disponivel, e e o que o tier local
            // chama de ingestao — deixa-lo como TTFT seria inventar precisao.
            ingestionMs = result.ingestionMs,
            generationMs = result.generationMs,
            truncated = result.truncated
        )
    )

    /**
     * Strips prompt template artifacts that models sometimes echo in responses.
     *
     * The prompt ends with "Aluno: <input>\nTutor:" — some models (especially
     * smaller local ones) echo parts of this conversation format in the output.
     * Common patterns:
     *  - "Tutor: actual response"
     *  - "Echoed question?\n\nTutor: actual response"
     *  - "Aluno: X\nTutor: actual response"
     */
    private fun cleanResponse(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text

        // Case 1: starts directly with "Tutor:" prefix
        TUTOR_PREFIX.find(text)?.let { match ->
            val content = text.substring(match.range.last + 1).trim()
            if (content.isNotBlank()) return content
        }

        // Case 2: model echoed conversation history then "Tutor:" before actual answer
        // Only search in the first 500 chars to avoid stripping legitimate "Tutor:" in content
        ECHO_WITH_TUTOR.find(text)?.let { match ->
            if (match.range.last < text.length - 1) {
                val content = text.substring(match.range.last + 1).trim()
                if (content.isNotBlank()) return content
            }
        }

        return text
    }

}

// ── Tipos de decisão ──────────────────────────────────────────────────────

/**
 * Resultado da lógica de roteamento — descreve a intenção sem executá-la.
 * Testes unitários verificam que [InferenceRouter.resolveRoute] retorna
 * a decisão correta para cada combinação de inputs.
 */
enum class RoutingDecision {
    /** Usar modelo local (on-device, MediaPipe) */
    LOCAL,
    /** Usar tier servidor (llama.cpp na LAN, com logprobs) */
    SERVER,
    /** Servidor primeiro; se a confiança for baixa, escalar para cloud */
    SERVER_WITH_CLOUD_ESCALATION,
    /** Usar API cloud (Firebase AI Logic / Gemini) */
    CLOUD,
    /**
     * Tentar local primeiro; se falhar, usar servidor.
     * Reservado — a lógica atual de [InferenceRouter.resolveRoute] não emite esta
     * decisão (prioriza servidor sobre local quando disponível), mas o executor a
     * suporta para futuras políticas de roteamento.
     */
    LOCAL_WITH_SERVER_FALLBACK,
    /** Tentar local primeiro; se falhar e houver internet, usar cloud */
    LOCAL_WITH_CLOUD_FALLBACK,
    /** Erro: modo privacidade ativo e sem modelo local */
    ERROR_PRIVACY,
    /** Erro: offline e sem modelo local */
    ERROR_OFFLINE,
    /** Erro: nenhum serviço configurado */
    ERROR_UNAVAILABLE;

    /**
     * True se a primeira tentativa de inferência será no modelo local.
     */
    val targetsLocal: Boolean
        get() = this == LOCAL ||
                this == LOCAL_WITH_SERVER_FALLBACK ||
                this == LOCAL_WITH_CLOUD_FALLBACK

    /**
     * True quando a primeira geração roda num modelo pequeno (local ou servidor),
     * que se beneficia de prompts compactos. Usado pelo [TutorPromptBuilder].
     * Cloud recebe prompts ricos.
     */
    val usesCompactPrompt: Boolean
        get() = targetsLocal ||
                this == SERVER ||
                this == SERVER_WITH_CLOUD_ESCALATION
}

// ── Exceções tipadas ──────────────────────────────────────────────────────

class InferenceUnavailableException(message: String) : Exception(message)
class PrivacyModeException(message: String) : Exception(message)
