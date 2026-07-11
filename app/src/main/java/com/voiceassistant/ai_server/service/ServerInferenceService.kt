package com.voiceassistant.ai_server.service

import android.util.Log
import com.voiceassistant.BuildConfig
import com.voiceassistant.ai_server.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier servidor: cliente HTTP para o `llama-server` (llama.cpp) na rede local.
 *
 * Diferente dos tiers local (MediaPipe) e cloud (Firebase), este tier retorna
 * **logprobs** por token, permitindo calcular um sinal de confiança contínuo em
 * [0, 1] usado pelo InferenceRouter para decidir entre entrega direta, modo
 * scaffolded ou escalonamento para cloud (integração na Fase 2).
 *
 * O cliente Retrofit é criado de forma lazy em [initialize] para não bloquear o
 * arranque do app. Se o servidor estiver indisponível, [generateWithConfidence]
 * lança [ServerUnavailableException] — o router trata como "servidor fora" e cai
 * para local/cloud.
 */
@Singleton
open class ServerInferenceService @Inject constructor(
    private val config: ServerConfig
) {
    @Volatile
    private var api: LlamaServerApi? = null

    @Volatile
    private var configuredBaseUrl: String? = null

    /**
     * True quando o cliente HTTP foi construído. Não garante que o servidor esteja
     * respondendo — para isso use [isServerReachable]. O InferenceRouter consulta
     * ambos antes de rotear para este tier.
     */
    val isAvailable: Boolean
        get() = api != null

    /** Constrói o cliente com a URL padrão do [ServerConfig]. */
    fun initialize() = configure(config.baseUrl)

    /**
     * Aponta o cliente para [baseUrl] (vindo de `UserSettings.serverBaseUrl`).
     * Idempotente: só reconstrói o cliente Retrofit quando a URL muda, então o
     * InferenceRouter pode chamar a cada requisição sem custo quando nada mudou.
     */
    @Synchronized
    open fun configure(baseUrl: String) {
        val normalized = normalizeBaseUrl(baseUrl)
        if (api == null || normalized != configuredBaseUrl) {
            api = buildApi(normalized)
            configuredBaseUrl = normalized
        }
    }

    /**
     * Retorna o cliente já configurado. Só constrói com a URL padrão se nada foi
     * configurado ainda — **não** sobrescreve uma URL definida via [configure]
     * (senão anularia a URL vinda das settings).
     */
    private fun ensureApi(): LlamaServerApi {
        api?.let { return it }
        configure(config.baseUrl)
        return api!!
    }

    private fun buildApi(normalizedBaseUrl: String): LlamaServerApi {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(client)
            .addConverterFactory(JSON.asConverterFactory(JSON_MEDIA_TYPE))
            .build()
            .create(LlamaServerApi::class.java)
    }

    /**
     * Verifica se o servidor está acessível e pronto (`GET /health` → "ok").
     * Retorna false em qualquer falha de rede/timeout, sem lançar.
     */
    open suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureApi().health().status.equals("ok", ignoreCase = true)
        } catch (e: Exception) {
            Log.d(TAG, "Health check falhou: ${e.message}")
            false
        }
    }

    /**
     * Gera resposta via `llama-server` e calcula a confiança a partir dos logprobs.
     *
     * @throws ServerUnavailableException se a requisição falhar (rede, timeout, erro
     *   do servidor).
     */
    open suspend fun generateWithConfidence(prompt: String): ServerResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()

            // ensureApi() (construção do cliente) fica dentro do try para garantir o
            // contrato: qualquer falha vira ServerUnavailableException, que o router trata.
            val response = try {
                ensureApi().completion(
                    CompletionRequest(
                        prompt = prompt,
                        nPredict = config.maxTokens,
                        temperature = config.temperature,
                        topP = config.topP,
                        topK = config.topK,
                        nProbs = config.nProbs
                    )
                )
            } catch (e: Exception) {
                throw ServerUnavailableException("Erro ao conectar ao servidor: ${e.message}", e)
            }

            val latency = System.currentTimeMillis() - start
            val confidence = calculateConfidence(response.completionProbabilities)

            Log.i(
                TAG,
                "SERVER: ${response.tokensPredicted} tokens, " +
                    "confidence=${"%.3f".format(confidence)}, ${latency}ms"
            )

            ServerResult(
                text = response.content,
                confidence = confidence,
                tokenCount = response.tokensPredicted,
                latencyMs = latency
            )
        }

    /**
     * Confiança = média da probabilidade do **token efetivamente escolhido** em cada
     * posição gerada (conforme doc 01). O token escolhido é `TokenProb.content`; sua
     * probabilidade é a entrada de `probs` cujo `tokStr` bate com ele. Com sampling
     * (temperatura > 0) o token escolhido pode não ser o mais provável (`probs[0]`),
     * por isso o casamento por `content` — com fallback para o primeiro candidato.
     * Retorna -1 quando não há logprobs disponíveis.
     */
    internal fun calculateConfidence(probs: List<TokenProb>?): Float {
        if (probs.isNullOrEmpty()) return CONFIDENCE_UNAVAILABLE

        val tokenConfidences = probs.mapNotNull { tp ->
            val chosen = tp.probs.firstOrNull { it.tokStr == tp.content }
                ?: tp.probs.firstOrNull()
            chosen?.prob
        }

        if (tokenConfidences.isEmpty()) return CONFIDENCE_UNAVAILABLE
        return tokenConfidences.average().toFloat()
    }

    internal fun normalizeBaseUrl(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    companion object {
        private const val TAG = "LlamaServer"
        const val CONFIDENCE_UNAVAILABLE = -1f

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/**
 * Resultado da geração no tier servidor.
 * @property confidence em [0, 1], ou [ServerInferenceService.CONFIDENCE_UNAVAILABLE] (-1).
 */
data class ServerResult(
    val text: String,
    val confidence: Float,
    val tokenCount: Int,
    val latencyMs: Long
)

/** Servidor não inicializado ou inacessível (rede, timeout, erro HTTP). */
class ServerUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
