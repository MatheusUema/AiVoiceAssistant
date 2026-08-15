package com.voiceassistant.llama

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

/**
 * Fachada Kotlin sobre a ponte JNI.
 *
 * Garantias:
 *  - **Confinamento de thread**: toda chamada nativa roda num único thread dedicado
 *    (`llama-inference`). O contexto do llama.cpp não é thread-safe.
 *  - **Sem crash por falha esperada**: [load] devolve [LlamaLoadResult.Failure] em vez
 *    de lançar quando o modelo não cabe ou o arquivo é inválido.
 *  - **Telemetria**: [generate] devolve texto + [LlamaStats] na mesma chamada.
 */
class LlamaEngine {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama-inference").apply { priority = Thread.NORM_PRIORITY }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var backendReady: Boolean = false

    val isLoaded: Boolean get() = handle != 0L

    /** Info do modelo atualmente carregado, ou null. */
    @Volatile
    var modelInfo: LlamaModelInfo? = null
        private set

    /**
     * Threads de inferência realmente em uso (a heurística resolvida, não o pedido).
     * Faz parte das condições de execução que o estudo precisa registrar.
     */
    @Volatile
    var threads: Int = -1
        private set

    /**
     * Carrega o GGUF em [modelPath]. Nunca lança por falha de carga — o motivo volta
     * em [LlamaLoadResult.Failure.reason] para ser registrado como resultado.
     */
    suspend fun load(modelPath: String, params: LlamaParams): LlamaLoadResult =
        withContext(dispatcher) {
            LlamaBridge.loadError?.let {
                return@withContext LlamaLoadResult.Failure(
                    "libllama_bridge.so não carregou: ${it.message}", 0L
                )
            }

            val file = File(modelPath)
            if (!file.exists() || file.length() == 0L) {
                return@withContext LlamaLoadResult.Failure(
                    "arquivo do modelo ausente ou vazio: $modelPath", 0L
                )
            }

            if (handle != 0L) {
                Log.d(TAG, "Sessão anterior ainda aberta; liberando antes de recarregar")
                freeCurrent()
            }

            ensureBackend()

            val resolvedThreads = resolveThreads(params.threads)
            var newHandle = 0L
            val elapsed = measureTimeMillis {
                newHandle = LlamaBridge.nativeLoadModel(
                    modelPath,
                    params.contextSize,
                    resolvedThreads,
                    params.batchSize,
                    params.flashAttention
                )
            }

            if (newHandle == 0L) {
                val reason = LlamaBridge.nativeLastError().ifBlank { "causa desconhecida" }
                Log.e(TAG, "Falha ao carregar $modelPath em ${elapsed}ms: $reason")
                return@withContext LlamaLoadResult.Failure(reason, elapsed)
            }

            handle = newHandle
            threads = resolvedThreads
            val info = LlamaModelInfo(
                description = LlamaBridge.nativeModelDescription(newHandle),
                sizeBytes = LlamaBridge.nativeModelSizeBytes(newHandle),
                contextSize = LlamaBridge.nativeContextSize(newHandle),
                backends = LlamaBridge.nativeBackends()
            )
            modelInfo = info
            Log.i(TAG, "Modelo carregado em ${elapsed}ms: $info")
            LlamaLoadResult.Success(info, elapsed)
        }

    /**
     * Gera a resposta completa para [prompt]. Cada chamada é independente: o KV-cache
     * é limpo antes do prefill, então não há vazamento de contexto entre questões.
     *
     * @throws IllegalStateException se não houver modelo carregado.
     * @throws LlamaGenerationException se a geração falhar no lado nativo.
     */
    suspend fun generate(prompt: String, params: LlamaParams): LlamaGeneration =
        coroutineScope {
            val current = handle
            check(current != 0L) { "Nenhum modelo carregado" }

            // Watchdog fora do dispatcher de inferência: aquele thread fica bloqueado
            // dentro do JNI durante toda a geração e não poderia se auto-interromper.
            // O cancelamento é cooperativo — o laço nativo checa a flag por token.
            val watchdog = if (params.generationTimeoutMs > 0) {
                launch(Dispatchers.Default) {
                    delay(params.generationTimeoutMs)
                    Log.w(TAG, "Timeout de ${params.generationTimeoutMs}ms; cancelando a geração")
                    LlamaBridge.nativeRequestCancel(current)
                }
            } else null

            try {
                generateBlocking(current, prompt, params)
            } finally {
                watchdog?.cancel()
            }
        }

    private suspend fun generateBlocking(
        current: Long,
        prompt: String,
        params: LlamaParams
    ): LlamaGeneration =
        withContext(dispatcher) {
            val text = LlamaBridge.nativeGenerate(
                current,
                prompt,
                params.maxTokens,
                params.temperature,
                params.topK,
                params.topP,
                params.seed,
                params.enableThinking
            ) ?: throw LlamaGenerationException(
                LlamaBridge.nativeLastError().ifBlank { "geração falhou sem mensagem" }
            )

            LlamaGeneration(
                text = text,
                stats = LlamaStats.fromArray(LlamaBridge.nativeLastStats(current)),
                reasoning = LlamaBridge.nativeLastReasoning(current)
            )
        }

    /** Número de tokens de [text] segundo o tokenizador do modelo carregado, ou -1. */
    suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        val current = handle
        if (current == 0L) -1 else LlamaBridge.nativeCountTokens(current, text)
    }

    /** Libera o modelo. Seguro chamar múltiplas vezes e sem modelo carregado. */
    fun unload() {
        // Enfileira no mesmo thread das chamadas nativas para não liberar sob uso.
        executor.execute { freeCurrent() }
    }

    private fun freeCurrent() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            modelInfo = null
            threads = -1
            LlamaBridge.nativeFreeSession(current)
            Log.i(TAG, "Sessão liberada")
        }
    }

    private fun ensureBackend() {
        if (!backendReady) {
            LlamaBridge.nativeInitBackend()
            backendReady = true
            Log.i(TAG, "Backends: ${LlamaBridge.nativeBackends()}")
            Log.d(TAG, LlamaBridge.nativeSystemInfo())
        }
    }

    /** Heurística do exemplo oficial: núcleos - 2, saturado em [2, 4]. */
    private fun resolveThreads(requested: Int): Int =
        if (requested > 0) requested
        else (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

    companion object {
        private const val TAG = "LlamaEngine"

        /** True se a `.so` nativa foi carregada com sucesso neste dispositivo/ABI. */
        val isNativeAvailable: Boolean get() = LlamaBridge.isNativeAvailable

        val nativeLoadError: Throwable? get() = LlamaBridge.loadError
    }
}

/** Falha durante a geração no lado nativo (decode, sampler, contexto). */
class LlamaGenerationException(message: String) : Exception(message)
