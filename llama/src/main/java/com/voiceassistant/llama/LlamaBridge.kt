package com.voiceassistant.llama

/**
 * Declarações JNI cruas — espelham 1:1 as funções de `src/main/cpp/llama_bridge.cpp`.
 *
 * NÃO usar diretamente fora do módulo: a sessão nativa não é thread-safe e os handles
 * são ponteiros crus. Use [LlamaEngine], que serializa as chamadas num único thread
 * e cuida do ciclo de vida do handle.
 */
internal object LlamaBridge {

    /** Erro de carregamento da própria `libllama_bridge.so`, se houver. */
    val loadError: Throwable? = try {
        System.loadLibrary("llama_bridge")
        null
    } catch (e: Throwable) {
        e
    }

    val isNativeAvailable: Boolean get() = loadError == null

    external fun nativeInitBackend()
    external fun nativeShutdownBackend()

    external fun nativeSystemInfo(): String
    external fun nativeBackends(): String
    external fun nativeLastError(): String

    /** @return handle da sessão, ou 0 em falha (motivo em [nativeLastError]). */
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int): Long
    external fun nativeFreeSession(handle: Long)

    external fun nativeModelDescription(handle: Long): String
    external fun nativeModelSizeBytes(handle: Long): Long
    external fun nativeContextSize(handle: Long): Int
    external fun nativeCountTokens(handle: Long, text: String): Int

    /** @return texto gerado, ou null em falha (motivo em [nativeLastError]). */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int
    ): String?

    external fun nativeLastStats(handle: Long): DoubleArray?
}
