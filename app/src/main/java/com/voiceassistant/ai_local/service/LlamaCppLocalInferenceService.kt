package com.voiceassistant.ai_local.service

import android.content.Context
import android.util.Log
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.core.model.InferenceTelemetry
import com.voiceassistant.llama.LlamaEngine
import com.voiceassistant.llama.LlamaGenerationException
import com.voiceassistant.llama.LlamaLoadResult
import com.voiceassistant.llama.LlamaModelInfo
import com.voiceassistant.llama.LlamaParams
import com.voiceassistant.llama.LlamaStats
import com.voiceassistant.llama.LlamaStopReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementação de [LocalInferenceService] sobre llama.cpp via JNI (módulo `:llama`).
 *
 * Substitui o [MediaPipeLocalInferenceService] no binding do Hilt. A troca é do runtime,
 * não da arquitetura: a interface, o [com.voiceassistant.ai_local.manager.LocalModelManager]
 * e o InferenceRouter continuam idênticos.
 *
 * Motivo da migração (doc 06 §0.2): usando o mesmo motor no device e no `llama-server`,
 * tokens/s, TTFT e a separação prefill/decode passam a ter a **mesma semântica** nos dois
 * tiers — condição para comparar local × servidor no estudo de elasticidade.
 *
 * Modelos: GGUF (`Q4_K_M`) em `assets/models/`, copiados para `filesDir` pelo manager.
 *
 * Esta classe NÃO decide quando carregar ou descarregar — isso é do `LocalModelManager`.
 */
@Singleton
class LlamaCppLocalInferenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: LocalModelConfig
) : LocalInferenceService {

    // O diretório de libs nativas é de onde o ggml carrega as variantes de CPU e escolhe
    // a melhor suportada pelo aparelho.
    // `?.` porque em teste de unidade na JVM o applicationInfo vem nulo; ali o runtime
    // nativo não existe mesmo, e o caminho vazio leva ao fallback de carga de backends.
    private val engine = LlamaEngine(context.applicationInfo?.nativeLibraryDir.orEmpty())

    private val params: LlamaParams
        get() = LlamaParams(
            contextSize = config.contextSize,
            threads = config.threads,
            batchSize = config.batchSize,
            maxTokens = config.maxTokens,
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            seed = config.randomSeed,
            enableThinking = config.enableThinking,
            generationTimeoutMs = config.generationTimeoutMs
        )

    override val isModelLoaded: Boolean
        get() = engine.isLoaded

    override val isAvailable: Boolean
        get() = engine.isLoaded

    /**
     * Resultado do último [loadModel] — inclusive quando falhou.
     * O manager persiste isto como linha de `model_load_log` (Fase 3): um modelo que
     * não cabe no aparelho é resultado do estudo, não erro a esconder.
     */
    @Volatile
    var lastLoadResult: LlamaLoadResult? = null
        private set

    /** Métricas cruas da ponte JNI para a última geração. */
    @Volatile
    var lastStats: LlamaStats? = null
        private set

    /**
     * Modelo efetivamente carregado, derivado do arquivo que o manager entregou —
     * e não da config. É o que o `routing_log` deve registrar quando o fallback assume.
     */
    @Volatile
    override var loadedModelId: String? = null
        private set

    /** Telemetria da última geração, no formato que o InferenceRouter consome (H2/H3). */
    override val lastTelemetry: InferenceTelemetry?
        get() = lastStats?.let { stats ->
            InferenceTelemetry(
                modelId = loadedModelId,
                runtime = RUNTIME,
                promptTokens = stats.promptTokens,
                generatedTokens = stats.generatedTokens,
                reasoningTokens = stats.reasoningTokens,
                ttftMs = stats.ttftMs,
                ingestionMs = stats.prefillMs,
                generationMs = stats.decodeMs,
                threads = engine.threads,
                backends = engine.modelInfo?.backends,
                truncated = stats.truncated,
                stopReason = stats.stopReason.name,
                confidence = stats.confidence.toFloat()
            )
        }

    /** Ficha do modelo carregado (descrição, tamanho, n_ctx efetivo, backends ggml). */
    val modelInfo: LlamaModelInfo?
        get() = engine.modelInfo

    /** Features de CPU escolhidas em runtime — vai para o `model_load_log`. */
    val systemInfo: String?
        get() = engine.systemInfo

    /**
     * Threads de inferência **resolvidas** (a heurística já aplicada), não o valor pedido
     * na config. Registrar o pedido (`0` = "decida por mim") em vez do efetivo tornaria o
     * `model_load_log` inútil para explicar diferenças de desempenho entre aparelhos.
     */
    val threads: Int
        get() = engine.threads

    override suspend fun loadModel(modelPath: String) {
        if (engine.isLoaded) {
            Log.d(TAG, "Modelo já carregado, ignorando loadModel()")
            return
        }

        Log.i(TAG, "Carregando modelo de: $modelPath")
        val result = engine.load(modelPath, params)
        lastLoadResult = result

        when (result) {
            is LlamaLoadResult.Success -> {
                // Do nome do arquivo, não da config: se o fallback assumiu, é este o modelo.
                // Em minúsculas para casar com `LocalModelVariant.label`, que é o que a
                // `model_load_log` grava — sem isso as duas tabelas não fazem join por
                // modelo (`gemma-4-E2B-it-Q4_K_M` vs `gemma-4-e2b-it-q4_k_m`).
                loadedModelId = modelPath.substringAfterLast('/')
                    .substringBeforeLast('.')
                    .lowercase()
                Log.i(
                    TAG,
                    "Modelo carregado em ${result.loadMs}ms — ${result.info.description}, " +
                            "n_ctx=${result.info.contextSize}, backends=${result.info.backends}"
                )
            }
            is LlamaLoadResult.Failure -> {
                loadedModelId = null
                Log.e(TAG, "Falha ao carregar modelo em ${result.loadMs}ms: ${result.reason}")
                throw LocalInferenceException("Falha ao carregar modelo local: ${result.reason}")
            }
        }
    }

    override suspend fun generate(prompt: String): String =
        generate(prompt, config.generationTimeoutMs)

    override suspend fun generate(prompt: String, timeoutMs: Long): String {
        if (!engine.isLoaded) throw LocalModelNotReadyException()

        try {
            val generation = engine.generate(prompt, params.copy(generationTimeoutMs = timeoutMs))
            lastStats = generation.stats

            val stats = generation.stats
            Log.d(
                TAG,
                "Inferência local: ${stats.generatedTokens} tok em ${stats.totalMs.toInt()}ms " +
                        "(TTFT ${stats.ttftMs.toInt()}ms, " +
                        "gen ${"%.1f".format(stats.generatedTokensPerSec)} tok/s" +
                        if (stats.reasoningTokens > 0) {
                            ", ${stats.reasoningTokens} de raciocínio + " +
                                    "${stats.answerTokens} de resposta)"
                        } else ")"
            )

            // Timeout vira erro para o aluno, mas a telemetria fica em `lastStats`:
            // no estudo, "não respondeu a tempo" é um dado sobre o aparelho, não uma
            // falha a descartar.
            if (stats.stopReason == LlamaStopReason.TIMEOUT) {
                Log.w(
                    TAG,
                    "Geração cancelada por timeout após ${stats.totalMs.toInt()}ms " +
                            "com ${stats.generatedTokens} tokens"
                )
                throw LocalInferenceTimeoutException(
                    elapsedMs = stats.totalMs.toLong(),
                    generatedTokens = stats.generatedTokens
                )
            }

            if (generation.text.isBlank()) {
                throw LocalInferenceException("Modelo retornou resposta vazia")
            }
            return generation.text
        } catch (e: LocalInferenceException) {
            throw e
        } catch (e: LlamaGenerationException) {
            Log.e(TAG, "Erro na geração local: ${e.message}", e)
            throw LocalInferenceException("Erro durante inferência local: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw LocalModelNotReadyException(e.message ?: "Modelo local não carregado")
        }
    }

    override suspend fun warmup(prompt: String) {
        if (!engine.isLoaded) {
            throw LocalModelNotReadyException("Não é possível fazer warmup sem modelo carregado")
        }

        Log.d(TAG, "Iniciando warmup...")
        val startTime = System.currentTimeMillis()
        try {
            // Poucos tokens: o objetivo é só materializar os buffers de compute e o KV-cache.
            engine.generate(prompt, params.copy(maxTokens = WARMUP_MAX_TOKENS))
            Log.i(TAG, "Warmup concluído em ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Warmup falhou em ${elapsed}ms (não-fatal): ${e.message}")
        }
    }

    override fun unloadModel() {
        engine.unload()
        lastStats = null
        loadedModelId = null
        Log.i(TAG, "Modelo descarregado")
    }

    companion object {
        private const val TAG = "LlamaCppLLM"
        private const val WARMUP_MAX_TOKENS = 8
        private const val RUNTIME = "llamacpp"
    }
}
