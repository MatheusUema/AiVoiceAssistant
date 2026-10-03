package com.voiceassistant.feature_tutor.policy

import android.util.Log
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.ai_cloud.model.CloudModelConfig
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_server.model.ServerConfig
import com.voiceassistant.ai_server.service.ServerInferenceService
import com.voiceassistant.ai_cloud.service.CloudResult
import com.voiceassistant.ai_server.service.ServerResult
import com.voiceassistant.core.model.InferenceTelemetry
import com.voiceassistant.ai_server.service.ServerUnavailableException
import com.voiceassistant.core.device.DeviceProfileProvider
import com.voiceassistant.core.logging.RoutingLogEntry
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
    private val ramSampler: ProcessRamSampler = ProcessRamSampler(),
    // As políticas aprendidas. Opcionais nos parâmetros para que os testes que constroem
    // o roteador à mão continuem compilando sem alteração — e porque um app sem os assets
    // é um app funcional, só sem roteamento aprendido.
    private val stringFeatures: StringFeatureExtractor = StringFeatureExtractor(),
    private val logprobFeatures: LogprobFeatureExtractor = LogprobFeatureExtractor(),
    private val letterExtractor: AnswerLetterExtractor = AnswerLetterExtractor(),
    private val policies: PolicyCoefficients? = null,
    // O eixo vertical (Bloco D parte 1). Com default, como os extratores acima, para que
    // os testes que montam o roteador à mão sigam compilando sem alteração.
    private val responseModeResolver: ResponseModeResolver = ResponseModeResolver()
) : InferenceRepository {

    /**
     * Política em vigor. Trocada pela bateria de medição (B.4) e pelas settings; o default
     * é o comportamento de hoje.
     *
     * `@Volatile` e não parâmetro do [infer]: a política é uma condição da EXECUÇÃO
     * inteira, não de uma pergunta. Passá-la por requisição convidaria a misturar
     * políticas dentro de uma mesma sessão, e a análise por sessão deixaria de fazer
     * sentido.
     */
    @Volatile
    var policyConfig: RoutingPolicyConfig = RoutingPolicyConfig.DEFAULT

    /**
     * Score do roteador aprendido para a última requisição, e o corte aplicado.
     * Existe para a instrumentação da B.4 — a `routing_log` vai registrar os dois.
     */
    @Volatile
    var lastPreScore: Float = PRESCORE_UNAVAILABLE
        private set

    /** Score da cascata para a última requisição. */
    @Volatile
    var lastCascadeScore: Float = PRESCORE_UNAVAILABLE
        private set

    /** True se a última requisição escalou por decisão de política (não por falha). */
    @Volatile
    var lastEscalated: Boolean = false
        private set

    /** Custo da própria decisão, em ms. É o número que a Fase 1 não pode dar. */
    @Volatile
    var lastPolicyDecisionMs: Long = 0L
        private set

    /** Latência do tier para onde escalou. -1 quando não escalou. */
    @Volatile
    var lastEscalationLatencyMs: Long = RoutingLogEntry.UNAVAILABLE_LONG
        private set

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

        // O custo da decisão é cronometrado à parte da inferência: é ele que responde se
        // o pré-filtro se paga. Um roteador que gasta 200 ms para evitar 30 s de nuvem é
        // um bom negócio; um que gasta 2 s não é, e sem medir não há como saber.
        val policy = policyConfig.policy
        val decisaoInicio = System.nanoTime()
        val preScore = calculaPreScore(request, policy)
        val corte = cortePre(policy)
        lastPolicyDecisionMs = (System.nanoTime() - decisaoInicio) / 1_000_000
        lastPreScore = preScore
        lastCascadeScore = PRESCORE_UNAVAILABLE
        lastEscalated = false
        lastEscalationLatencyMs = RoutingLogEntry.UNAVAILABLE_LONG

        val decision = InferenceRouter.resolveRoute(
            isOnline = isOnline,
            isLocalAvailable = isLocalAvailable,
            isServerAvailable = isServerAvailable,
            isCloudAvailable = isCloudAvailable,
            complexity = request.complexity,
            privacyMode = settings.privacyModeEnabled,
            policy = policy,
            preScore = preScore,
            preScoreThreshold = corte
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

        // O eixo vertical entra DEPOIS da execução e ANTES do log: a faixa descreve a
        // resposta que já saiu, não a rota que a produziu.
        val comModo = aplicaModoResposta(request, result)

        logRouting(request, decision, comModo, isOnline, isServerAvailable, effectiveServerBaseUrl)

        return comModo
    }

    /**
     * Deriva o [com.voiceassistant.core.model.ResponseMode] da resposta — o eixo
     * vertical que a interface do Bloco D parte 1 exibe.
     *
     * ## O que esta função deliberadamente NÃO faz
     *
     * Não toca em [lastCascadeScore], [lastEscalated] nem em nenhuma coluna da
     * `routing_log`, e não consulta [policyConfig]. Isso é contenção, não descuido: aquelas
     * são a telemetria do **eixo experimental** do Bloco A, e populá-las a partir do chat
     * mudaria o significado da coluna para as linhas de pesquisa. A bateria de medição
     * segue byte a byte como era — ela não liga
     * [InferenceRequest.deriveResponseMode], então nem entra aqui.
     *
     * O **score vai para o logcat**, e é de propósito: a tela não mostra número ao aluno
     * (uma cifra ali convidaria a interpretá-la, e o Bloco D parte 1 é demonstração, não
     * medida), mas a legenda de cada print precisa poder citar o score que produziu aquela
     * faixa. O logcat é onde ele fica recuperável durante a captura.
     *
     * ## Por que só o tier local
     *
     * O score sai de [localService]`.lastTokenProbs`, que é preenchido pela geração local.
     * Se a rota foi servidor ou nuvem, aquele campo pode conter a distribuição de uma
     * geração local **anterior** — pontuar com ela daria um número plausível sobre a
     * resposta errada. A guarda por [InferenceSource.LOCAL] é o que impede isso.
     */
    private fun aplicaModoResposta(
        request: InferenceRequest,
        result: InferenceResult
    ): InferenceResult {
        if (!request.deriveResponseMode) return result
        if (result.source != InferenceSource.LOCAL) return result

        val modelo = policies?.load(POLICY_CASCATA) ?: return result
        val inicio = System.nanoTime()
        val score = calculaCascadeScore(result.text)
        val modo = responseModeResolver.resolve(score, modelo)
        val custoMs = (System.nanoTime() - inicio) / 1_000_000

        if (modo == null) {
            // Sem faixa não há bandeira — o comportamento anterior. Registrado em nível
            // de info porque, na captura, "por que esta resposta não tem modo?" é a
            // primeira pergunta que aparece.
            Log.i(TAG, "modo pedagógico indisponível (score=$score, ${custoMs}ms)")
            return result
        }

        Log.i(TAG, "modo pedagógico: $modo | score=${"%.4f".format(score)} " +
            "| cortes ${"%.4f".format(modelo.corteParaOrcamento(ResponseModeResolver.FRACAO_MEDIAR))}" +
            "/${"%.4f".format(modelo.corteParaOrcamento(ResponseModeResolver.FRACAO_DIRETO))} " +
            "| ${custoMs}ms")

        return result.copy(responseMode = modo)
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
                expectedAnswer = request.expectedAnswer,
                // A política e os scores que a decidiram. Sem isto a linha registra QUE
                // rota foi tomada, mas não POR QUE — e refazer a decisão com outro corte
                // exigiria recoletar.
                policyName = policyConfig.policy.name,
                preScore = lastPreScore,
                cascadeScore = lastCascadeScore,
                escalated = lastEscalated,
                policyDecisionMs = lastPolicyDecisionMs,
                escalationLatencyMs = lastEscalationLatencyMs
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

        /** Assets das políticas treinadas na Fase 1. */
        private const val POLICY_ROTEADOR = "roteador-v1"
        private const val POLICY_CASCATA = "cascata-v1"
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
        /**
         * Score indisponível — a política cai na heurística.
         *
         * Mesma convenção do `confidence == -1` que `runServer` já usa para não escalar:
         * um sinal ausente devolve o comportamento anterior, e nunca uma decisão tomada
         * sobre um número inventado.
         */
        const val PRESCORE_UNAVAILABLE = -1f

        fun resolveRoute(
            isOnline: Boolean,
            isLocalAvailable: Boolean,
            isServerAvailable: Boolean,
            isCloudAvailable: Boolean,
            complexity: PromptComplexity,
            privacyMode: Boolean,
            /**
             * Qual política decide. O default é [RoutingPolicy.HEURISTIC], o
             * comportamento de hoje — é o que permite que toda a matriz de testes já
             * existente continue valendo sem uma linha alterada.
             */
            policy: RoutingPolicy = RoutingPolicy.HEURISTIC,
            /** Score do roteador aprendido; [PRESCORE_UNAVAILABLE] quando não há. */
            preScore: Float = PRESCORE_UNAVAILABLE,
            /** Corte de orçamento: escala quem fica ABAIXO. */
            preScoreThreshold: Float = 0.5f
        ): RoutingDecision = when {
        // Regras 1-2: Modo privacidade — dados nunca saem do dispositivo.
        // Servidor (LAN) e cloud enviam dados para fora → só local é permitido.
        privacyMode && isLocalAvailable -> RoutingDecision.LOCAL
        privacyMode -> RoutingDecision.ERROR_PRIVACY

        // Regras 3-4: Offline total — sem internet E sem servidor na LAN.
        // (O servidor pode estar acessível na LAN mesmo sem internet.)
        !isOnline && !isServerAvailable && isLocalAvailable -> RoutingDecision.LOCAL
        !isOnline && !isServerAvailable -> RoutingDecision.ERROR_OFFLINE

        // Baselines: os pisos e tetos do Pareto. Vêm DEPOIS das regras de privacidade e
        // offline de propósito — "sempre nuvem" não pode furar o modo privacidade, senão
        // a política deixaria de ser uma escolha de custo e passaria a ser uma violação.
        policy == RoutingPolicy.ALWAYS_LOCAL && isLocalAvailable -> RoutingDecision.LOCAL
        policy == RoutingPolicy.ALWAYS_CLOUD && isCloudAvailable -> RoutingDecision.CLOUD

        // Regra 5, versão APRENDIDA: escala quem o modelo julga que o local vai errar.
        // O score aponta para cima (alto = local provavelmente acerta), então o corte é
        // por BAIXO — inverter aqui produziria uma política que escala exatamente o que o
        // local acertaria, pior que não rotear.
        //
        // `preScore` indisponível cai na heurística abaixo, sem ramo próprio: é a mesma
        // degradação graciosa do `confidence == -1` em `runServer`.
        policy.needsPreScore && isCloudAvailable &&
            preScore != PRESCORE_UNAVAILABLE && preScore < preScoreThreshold ->
            RoutingDecision.CLOUD

        // Regra 5 original (heurística). Continua valendo para HEURISTIC, para
        // ORACLE_IRT (que não é implantável e é tratada como heurística se chegar aqui),
        // e como fallback de LEARNED quando o score não veio.
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
        // A cascata só entra onde o local de fato roda. Nos demais ramos não há
        // distribuicao de logprobs do local para recalibrar.
        RoutingDecision.LOCAL -> aplicaCascata(runLocal(prompt), prompt)
        RoutingDecision.CLOUD -> runCloud(prompt)
        RoutingDecision.SERVER -> runServer(prompt, allowCloudEscalation = false)
        RoutingDecision.SERVER_WITH_CLOUD_ESCALATION -> runServer(prompt, allowCloudEscalation = true)
        RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK ->
            aplicaCascata(runLocalWithCloudFallback(prompt), prompt)
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

    // ── Políticas ─────────────────────────────────────────────────────────────

    /**
     * Score do roteador aprendido, ANTES de inferir.
     *
     * Devolve [PRESCORE_UNAVAILABLE] quando a política não o pede, quando os coeficientes
     * não estão nos assets, ou quando o cálculo falha. Nunca lança: um roteador que
     * derruba a resposta do aluno porque um asset está malformado seria pior que um
     * roteador que não roteia.
     *
     * A ÁREA só existe na bateria de medição, onde vem do metadata do dataset — em uso
     * real não há classificador de área, e as quatro features one-hot ficam zeradas. Isso
     * NÃO é equivalente ao que o modelo viu no treino, então a medição on-device com área
     * é um teto que um sistema implantado não alcança. A comparação com/sem área é
     * justamente uma das medidas do Bloco A.
     */
    private fun calculaPreScore(request: InferenceRequest, policy: RoutingPolicy): Float {
        if (!policy.needsPreScore) return PRESCORE_UNAVAILABLE
        val modelo = policies?.load(POLICY_ROTEADOR) ?: return PRESCORE_UNAVAILABLE
        return runCatching {
            // No caminho da bateria o prompt já vem montado (`rawPrompt`), então o
            // invólucro precisa sair; no chat o texto do aluno já é o cru, e
            // `enunciadoCru` não encontra nada para remover.
            val cru = stringFeatures.enunciadoCru(request.prompt)
            val f = stringFeatures.extract(cru, request.alternatives, request.questionArea)
            modelo.score(f).toFloat()
        }.getOrElse {
            Log.w(TAG, "pré-score indisponível (${it.message}); caindo na heurística")
            PRESCORE_UNAVAILABLE
        }
    }

    /** Corte de orçamento para o roteador aprendido. */
    private fun cortePre(policy: RoutingPolicy): Float {
        if (!policy.needsPreScore) return 0.5f
        val modelo = policies?.load(POLICY_ROTEADOR) ?: return 0.5f
        val fracao = policyConfig.budgetFraction
        return (fracao?.let { modelo.corteParaOrcamento(it) }
            ?: modelo.limiar?.referencia_mediana_treino
            ?: 0.5).toFloat()
    }

    /**
     * Score da cascata, DEPOIS de inferir no local.
     *
     * Usa o modelo recalibrado sobre 17 estatísticas da distribuição de logprobs — e
     * **não** a confiança crua, que é apenas uma delas e a que a Fase 1 mostrou ser a mais
     * fraca. Devolve [PRESCORE_UNAVAILABLE] quando o tier não expôs a distribuição, ou
     * quando a resposta é curta demais para sustentar estatística (o extrator devolve null,
     * o mesmo `continue` do Python).
     *
     * A LETRA vem do [AnswerLetterExtractor] sobre o texto gerado: é a alternativa que o
     * MODELO cravou, a mesma grandeza que o treino usou (lá pela leitura humana, aqui pelo
     * extrator automático, que concordam em ~99%). Quando o modelo não fecha no formato
     * pedido, o extrator devolve null e `conf_letra_b1` cai no fallback — o MESMO caminho
     * que o treino já percorria quando não havia letra. A ressalva está documentada em
     * [AnswerLetterExtractor].
     */
    private fun calculaCascadeScore(respostaLocal: String): Float {
        val modelo = policies?.load(POLICY_CASCATA) ?: return PRESCORE_UNAVAILABLE
        val amostras = localService.lastTokenProbs
        if (amostras.isEmpty()) return PRESCORE_UNAVAILABLE
        return runCatching {
            // A letra que o MODELO cravou, lida do texto — não o gabarito, que em runtime
            // não existe e que, se usado, vazaria o rótulo para dentro da feature.
            val letra = letterExtractor.extract(respostaLocal)
            val f = logprobFeatures.extract(amostras.toTokenDistributions(), letra = letra)
                ?: return PRESCORE_UNAVAILABLE
            modelo.score(f).toFloat()
        }.getOrElse {
            Log.w(TAG, "score da cascata indisponível (${it.message})")
            PRESCORE_UNAVAILABLE
        }
    }

    /** Corte de orçamento para a cascata. */
    private fun corteCascata(): Float {
        val modelo = policies?.load(POLICY_CASCATA) ?: return 0.5f
        val fracao = policyConfig.budgetFraction
        return (fracao?.let { modelo.corteParaOrcamento(it) }
            ?: modelo.limiar?.referencia_mediana_treino
            ?: 0.5).toFloat()
    }

    /**
     * A cascata: depois da resposta local, decide se vale reperguntar à nuvem.
     *
     * Isto LIGA o escalonamento no caminho local, que estava desativado por decisão
     * explícita ("a política de escalonamento por confiança é do tier servidor... mudar a
     * política é decisão à parte, com dados"). Os dados são a Fase 1, e a mudança é
     * governada por [RoutingPolicyConfig] — não é um novo default.
     *
     * O custo do local **já foi pago** quando esta função roda. É a diferença essencial
     * para o roteador aprendido, e é o que o Pareto tem que mostrar: a cascata decide
     * melhor e gasta mais.
     */
    private suspend fun aplicaCascata(local: InferenceResult, prompt: String): InferenceResult {
        if (!policyConfig.policy.needsCascade) return local

        // O score é calculado SEMPRE que a política o pede, mesmo sem nuvem para onde
        // escalar. Ele é telemetria: é o número que a `routing_log` registra e que permite
        // refazer a decisão com outro corte sem recoletar. Condicioná-lo à nuvem faria a
        // validação em modo privacidade — a única que roda sem rede — devolver linhas sem
        // score, e a validação mediria a ausência de nuvem em vez do extrator.
        val score = calculaCascadeScore(local.text)
        lastCascadeScore = score
        if (score == PRESCORE_UNAVAILABLE) return local

        val corte = corteCascata()
        if (score >= corte) return local

        // Daqui para baixo a política QUER escalar. Só agora a nuvem importa.
        if (!cloudService.isAvailable) {
            Log.i(TAG, "cascata: score %.3f < %.3f, mas sem nuvem — fica o local"
                .format(score, corte))
            return local
        }

        Log.i(TAG, "cascata: score %.3f < %.3f — escalando para a nuvem".format(score, corte))
        return try {
            lastEscalated = true
            val t0 = System.currentTimeMillis()
            // Preserva o score medido no local, como `runServer` preserva a confiança:
            // sem isso, a linha da nuvem no log não diz POR QUE escalou.
            val r = runCloud(prompt).copy(confidence = local.confidence)
            // A latência do escalonamento é medida À PARTE da latência total: o Pareto
            // precisa saber quanto do tempo foi o local (já gasto) e quanto foi a nuvem.
            lastEscalationLatencyMs = System.currentTimeMillis() - t0
            r
        } catch (e: Exception) {
            // A nuvem falhar não pode custar a resposta que o local JÁ produziu.
            Log.w(TAG, "escalonamento da cascata falhou (${e.message}); fica o local")
            lastEscalated = false
            lastEscalationLatencyMs = RoutingLogEntry.UNAVAILABLE_LONG
            local
        }
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
        val r = cloudService.generate(prompt)
        Log.i(TAG, "CLOUD concluído em ${r.latencyMs}ms")
        return cloudResult(r, InferenceSource.CLOUD)
    }

    private suspend fun runLocalWithCloudFallback(prompt: String): InferenceResult {
        return try {
            runLocal(prompt, hasFallback = true)
        } catch (localError: Exception) {
            Log.w(TAG, "FALLBACK: local falhou (${localError.message}), tentando cloud")
            try {
                val r = cloudService.generate(prompt)
                Log.i(TAG, "FALLBACK→CLOUD concluído em ${r.latencyMs}ms")
                cloudResult(r, InferenceSource.FALLBACK)
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
            val r = cloudService.generate(prompt)
            Log.i(TAG, "SERVER→FALLBACK cloud concluído em ${r.latencyMs}ms")
            return cloudResult(r, InferenceSource.FALLBACK)
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
    /**
     * Converte o resultado do tier cloud, COM TELEMETRIA.
     *
     * Ate 2026-09-05 o tier cloud devolvia so texto e latencia, e a `routing_log` saia com
     * tokens e tempos em -1. As contagens vem do `usageMetadata` da API e sao o unico
     * sinal de custo que ela oferece -- e o que permite estimar custo por questao.
     *
     * `ingestionMs`/`generationMs` ficam indisponiveis de proposito: a API nao separa
     * prefill de decode, e inventar a divisao a partir da latencia total seria fabricar
     * um numero. A CONFIANCA tambem fica sem sinal (o SDK nao expoe logprobs), e o
     * `confidenceMethod` grava "none" -- nao um -1 que pareceria medicao falha.
     */
    private fun cloudResult(r: CloudResult, fonte: InferenceSource): InferenceResult =
        InferenceResult(
            text = cleanResponse(r.text),
            source = fonte,
            latencyMs = r.latencyMs,
            telemetry = InferenceTelemetry(
                modelId = r.modelId ?: cloudModelConfig.modelName,
                runtime = "firebase-ai",
                promptTokens = r.promptTokens,
                generatedTokens = r.generatedTokens,
                reasoningTokens = r.reasoningTokens,
                truncated = r.truncated
            )
        )

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
