package com.voiceassistant.ai_local.service

/**
 * Contrato de domínio para inferência LLM local (on-device).
 *
 * A camada de domínio (InferenceRouter, use cases) depende apenas desta interface.
 * A implementação concreta (MediaPipe, ONNX, etc.) é injetada via Hilt.
 *
 * Ciclo de vida típico:
 *  1. [loadModel] — carrega pesos em memória (operação custosa, ~5-30s)
 *  2. [warmup]    — inferência descartável para pré-aquecer caches (opcional)
 *  3. [generate]  — chamadas de inferência reais
 *  4. [unloadModel] — libera memória quando não precisar mais
 */
interface LocalInferenceService {

    /**
     * Gera uma resposta para o [prompt] usando o modelo carregado em memória.
     *
     * @throws LocalModelNotReadyException se [loadModel] não foi chamado ou falhou.
     * @throws LocalInferenceException se ocorrer erro durante a geração.
     */
    suspend fun generate(prompt: String): String

    /** True se o modelo está carregado e pronto para [generate]. */
    val isModelLoaded: Boolean

    /**
     * True se o modelo está carregado E o dispositivo suporta inferência local.
     * Consultado pelo InferenceRouter antes de rotear.
     */
    val isAvailable: Boolean

    /**
     * Carrega os pesos do modelo em memória.
     * Operação custosa (~5-30s dependendo do modelo e dispositivo).
     * Deve ser chamada em background thread.
     *
     * @param modelPath Caminho absoluto para o arquivo do modelo no filesystem.
     * @throws LocalInferenceException se o carregamento falhar.
     */
    suspend fun loadModel(modelPath: String)

    /**
     * Executa uma inferência curta descartável para pré-aquecer os caches
     * internos do runtime, reduzindo a latência da primeira pergunta real.
     *
     * @throws LocalInferenceException se o warmup falhar.
     */
    suspend fun warmup(prompt: String = "Olá")

    /** Libera os pesos do modelo da memória. Seguro chamar múltiplas vezes. */
    fun unloadModel()
}

/** O modelo não foi carregado ou o carregamento falhou. */
class LocalModelNotReadyException(
    message: String = "Modelo local não carregado"
) : Exception(message)

/** Erro durante carregamento ou geração no modelo local. */
class LocalInferenceException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
