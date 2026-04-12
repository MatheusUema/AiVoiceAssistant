package com.voiceassistant.domain.repository

import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.InferenceResult

/**
 * Contrato da camada de domínio para inferência de linguagem.
 * Abstrai completamente se a resposta vem do modelo local ou da nuvem.
 * O InferenceRouter é a implementação concreta desta interface.
 */
interface InferenceRepository {

    /**
     * Envia uma [InferenceRequest] e retorna o resultado com metadados de origem.
     * Pode lançar exceção se ambas as fontes falharem.
     */
    suspend fun infer(request: InferenceRequest): InferenceResult
}
