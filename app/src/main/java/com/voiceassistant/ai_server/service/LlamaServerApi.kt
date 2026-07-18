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
 * campos extras do servidor (timings, model, id, bytes, etc.) não quebram a
 * desserialização.
 *
 * O formato de `completion_probabilities` mudou entre versões do `llama-server`. Os DTOs
 * suportam **ambos** (todos os campos nullable, distinguidos por presença):
 *  - **Antigo:** `[{ "content": "A", "probs": [{ "tok_str": "A", "prob": 0.92 }] }]`
 *  - **Novo:**   `[{ "token": "A", "logprob": -0.08, "top_logprobs": [...] }]` — a
 *    própria entrada é o token escolhido; probabilidade = `exp(logprob)`.
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

/**
 * Uma posição gerada. Campos do schema **antigo** ([content]/[probs]) e do **novo**
 * ([token]/[logprob]/[topLogprobs]) coexistem como nullable; o parsing usa o que
 * estiver presente. No schema novo a própria entrada é o token escolhido.
 */
@Serializable
data class TokenProb(
    // Schema antigo: token escolhido em `content`, candidatos em `probs` (com `prob`).
    val content: String? = null,
    val probs: List<ProbEntry>? = null,
    // Schema novo: entrada = token escolhido, com `logprob` (log natural). Candidatos
    // em `top_logprobs`. Probabilidade do token = exp(logprob).
    val token: String? = null,
    val logprob: Float? = null,
    @SerialName("top_logprobs") val topLogprobs: List<ProbEntry>? = null
)

/**
 * Um candidato de token. Antigo: [tokStr]/[prob]. Novo: [token]/[logprob].
 */
@Serializable
data class ProbEntry(
    @SerialName("tok_str") val tokStr: String? = null,
    val prob: Float? = null,
    val token: String? = null,
    val logprob: Float? = null
)

@Serializable
data class HealthResponse(
    /** `llama-server` retorna "ok" quando pronto para servir. */
    val status: String = ""
)
