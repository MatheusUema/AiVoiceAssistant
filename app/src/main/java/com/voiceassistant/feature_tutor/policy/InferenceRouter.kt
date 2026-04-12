package com.voiceassistant.feature_tutor.policy

import android.util.Log
import com.voiceassistant.ai_cloud.service.CloudInferenceService
import com.voiceassistant.ai_local.manager.LocalModelManager
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.InferenceResult
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.core.network.NetworkMonitor
import com.voiceassistant.core.storage.UserSettingsDataStore
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
 * Regras (em ordem de prioridade):
 *  1. PRIVACIDADE + local → local (nunca envia dados para nuvem)
 *  2. PRIVACIDADE + sem local → erro
 *  3. OFFLINE + local → local
 *  4. OFFLINE + sem local → erro
 *  5. ONLINE + complexa + cloud → cloud
 *  6. ONLINE + local → local com fallback cloud
 *  7. ONLINE + cloud (sem local) → cloud
 *  8. Nada disponível → erro
 */
@Singleton
class InferenceRouter @Inject constructor(
    private val localService: LocalInferenceService,
    private val cloudService: CloudInferenceService,
    private val localModelManager: LocalModelManager,
    private val networkMonitor: NetworkMonitor,
    private val userSettingsDataStore: UserSettingsDataStore,
    private val promptBuilder: TutorPromptBuilder
) : InferenceRepository {

    override suspend fun infer(request: InferenceRequest): InferenceResult {
        val settings = userSettingsDataStore.settings.first()
        val isOnline = networkMonitor.isCurrentlyOnline()
        val isLocalAvailable = localModelManager.isAvailable
        val isCloudAvailable = isOnline && cloudService.isAvailable

        val decision = InferenceRouter.resolveRoute(
            isOnline = isOnline,
            isLocalAvailable = isLocalAvailable,
            isCloudAvailable = isCloudAvailable,
            complexity = request.complexity,
            privacyMode = settings.privacyModeEnabled
        )

        val isCompact = decision.targetsLocal
        val builtPrompt = promptBuilder.build(
            userInput = request.prompt,
            history = request.conversationHistory,
            mode = request.tutorMode,
            compact = isCompact
        )

        Log.d(TAG, "Rota: $decision | mode=${request.tutorMode} compact=$isCompact " +
                "online=$isOnline local=$isLocalAvailable " +
                "cloud=$isCloudAvailable privacy=${settings.privacyModeEnabled} " +
                "complexity=${request.complexity}")

        return executeDecision(decision, builtPrompt)
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
            isCloudAvailable: Boolean,
            complexity: PromptComplexity,
            privacyMode: Boolean
        ): RoutingDecision = when {
        // Regras 1-2: Modo privacidade — dados nunca saem do dispositivo
        privacyMode && isLocalAvailable -> RoutingDecision.LOCAL
        privacyMode -> RoutingDecision.ERROR_PRIVACY

        // Regras 3-4: Offline
        !isOnline && isLocalAvailable -> RoutingDecision.LOCAL
        !isOnline -> RoutingDecision.ERROR_OFFLINE

        // Regra 5: Online + pergunta complexa → cloud diretamente
        complexity == PromptComplexity.COMPLEX && isCloudAvailable -> RoutingDecision.CLOUD

        // Regra 6: Online + local disponível → local (fallback cloud se falhar)
        isLocalAvailable -> RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK

        // Regra 7: Online + sem local, mas cloud disponível
        isCloudAvailable -> RoutingDecision.CLOUD

        // Regra 8: Nada
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
        RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK -> runLocalWithCloudFallback(prompt)

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

    private suspend fun runLocal(prompt: String): InferenceResult {
        val start = System.currentTimeMillis()
        val raw = localService.generate(prompt)
        val latency = System.currentTimeMillis() - start
        Log.i(TAG, "LOCAL concluído em ${latency}ms")
        return InferenceResult(text = cleanResponse(raw), source = InferenceSource.LOCAL, latencyMs = latency)
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
            runLocal(prompt)
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
    /** Usar modelo local (on-device) */
    LOCAL,
    /** Usar API cloud (Firebase AI Logic / Gemini) */
    CLOUD,
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
     * Usado pelo [TutorPromptBuilder] para gerar prompts compactos.
     */
    val targetsLocal: Boolean
        get() = this == LOCAL || this == LOCAL_WITH_CLOUD_FALLBACK
}

// ── Exceções tipadas ──────────────────────────────────────────────────────

class InferenceUnavailableException(message: String) : Exception(message)
class PrivacyModeException(message: String) : Exception(message)
