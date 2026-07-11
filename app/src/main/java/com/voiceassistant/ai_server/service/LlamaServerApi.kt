package com.voiceassistant.ai_server.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Cliente Retrofit para o `llama-server` (servidor HTTP embutido do llama.cpp).
 *
 * Endpoints usados:
 *  - `GET /health`      — verifica se o servidor está no ar e com modelo carregado.
 *  - `POST /completion` — geração de texto; quando `n_probs > 0`, retorna
 *    `completion_probabilities` com os logprobs de cada token gerado.
 *
 * DTOs usam kotlinx.serialization (converter já configurado no projeto). Campos JSON
 * em snake_case são mapeados via [SerialName] para propriedades camelCase idiomáticas.
 * O [kotlinx.serialization.json.Json] do service usa `ignoreUnknownKeys = true`, então
 * campos extras do servidor (timings, model, etc.) não quebram a desserialização.
 */
interface LlamaServerApi {

    @GET("health")
    suspend fun health(): HealthResponse

    @POST("completion")
    suspend fun completion(@Body request: CompletionRequest): CompletionResponse
}

@Serializable
data class CompletionRequest(
    val prompt: String,
    @SerialName("n_predict") val nPredict: Int = 512,
    val temperature: Float = 0.7f,
    @SerialName("top_p") val topP: Float = 0.9f,
    @SerialName("top_k") val topK: Int = 40,
    /** > 0 ativa o retorno dos logprobs em `completion_probabilities`. */
    @SerialName("n_probs") val nProbs: Int = 5
)

@Serializable
data class CompletionResponse(
    val content: String = "",
    @SerialName("tokens_predicted") val tokensPredicted: Int = 0,
    @SerialName("completion_probabilities") val completionProbabilities: List<TokenProb>? = null
)

@Serializable
data class TokenProb(
    val content: String = "",
    val probs: List<ProbEntry> = emptyList()
)

@Serializable
data class ProbEntry(
    @SerialName("tok_str") val tokStr: String = "",
    val prob: Float = 0f
)

@Serializable
data class HealthResponse(
    /** `llama-server` retorna "ok" quando pronto para servir. */
    val status: String = ""
)
