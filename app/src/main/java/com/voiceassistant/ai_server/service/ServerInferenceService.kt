package com.voiceassistant.ai_server.service

import android.os.SystemClock
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
import kotlin.math.exp

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
            // SystemClock.elapsedRealtime() e nao System.currentTimeMillis().
            //
            // Este numero e o minuendo de H11 (latencia total menos o compute do
            // servidor), entao qualquer patologia do relogio vira um H11 errado -- e as
            // duas alternativas foram medidas falhando neste mesmo aparelho:
            //   - CLOCK_MONOTONIC para de contar durante a suspensao, e o aparelho
            //     suspende esperando a resposta: 4.556 ms medidos contra 6.851 ms reais.
            //   - CLOCK_REALTIME (currentTimeMillis) salta numa sincronizacao NTP: uma
            //     requisicao chegou a devolver duracao NEGATIVA.
            // elapsedRealtime e CLOCK_BOOTTIME: monotonico E conta durante a suspensao.
            val start = SystemClock.elapsedRealtime()

            // ensureApi() (construção do cliente) fica dentro do try para garantir o
            // contrato: qualquer falha vira ServerUnavailableException, que o router trata.
            val response = try {
                val api = ensureApi()
                // O chat template vem do PROPRIO modelo, pelo endpoint do servidor. O
                // `/completion` recebe texto cru: sem esta etapa o modelo recebe a
                // questao sem o wrapper que espera e responde noutro regime, o que
                // tornaria o tier servidor incomparavel com o local (que aplica o
                // template na ponte JNI) e com as rodadas de referencia.
                val formatado = try {
                    api.applyTemplate(
                        ApplyTemplateRequest(listOf(ChatMessage("user", prompt)))
                    ).prompt.ifBlank { prompt }
                } catch (e: Exception) {
                    // Servidor antigo sem /apply-template: degrada para o prompt cru em
                    // vez de derrubar a inferencia, mas avisa — a comparabilidade cai.
                    Log.w(TAG, "apply-template indisponivel (${e.message}); usando prompt cru")
                    prompt
                }
                api.completion(
                    CompletionRequest(
                        prompt = formatado,
                        nPredict = config.maxTokens,
                        temperature = config.temperature,
                        topP = config.topP,
                        topK = config.topK,
                        nProbs = config.nProbs,
                        seed = config.randomSeed
                    )
                )
            } catch (e: Exception) {
                throw ServerUnavailableException("Erro ao conectar ao servidor: ${e.message}", e)
            }

            val latency = SystemClock.elapsedRealtime() - start
            val confidence = calculateConfidence(response.completionProbabilities)
            val t = response.timings

            Log.i(
                TAG,
                "SERVER: ${response.tokensPredicted} tokens, " +
                    "confidence=${"%.3f".format(confidence)}, ${latency}ms" +
                    (t?.let {
                        " | servidor: prefill ${"%.0f".format(it.promptMs)}ms " +
                            "decode ${"%.0f".format(it.predictedMs)}ms " +
                            "-> rede ~${latency - (it.promptMs + it.predictedMs).toLong()}ms"
                    } ?: " | sem timings")
            )

            ServerResult(
                text = response.content,
                confidence = confidence,
                tokenCount = response.tokensPredicted,
                latencyMs = latency,
                promptTokens = t?.promptN ?: response.tokensEvaluated,
                generatedTokens = t?.predictedN ?: response.tokensPredicted,
                ingestionMs = t?.promptMs ?: ServerResult.UNAVAILABLE,
                generationMs = t?.predictedMs ?: ServerResult.UNAVAILABLE,
                truncated = response.stopType == "limit",
                baseUrl = configuredBaseUrl
            )
        }

    /**
     * Confiança = média da probabilidade do **token efetivamente escolhido** em cada
     * posição gerada (conforme doc 01). Suporta os dois formatos de
     * `completion_probabilities` (ver [TokenProb]):
     *  - **novo:** a entrada de topo já é o token escolhido; prob = `exp(logprob)`;
     *  - **antigo:** prob do candidato de `probs` cujo `tokStr` bate com `content`
     *    (com fallback para o primeiro candidato — importante com sampling, em que o
     *    token escolhido pode não ser o mais provável).
     * Degradação graciosa: retorna -1 quando não há dados de logprob utilizáveis.
     */
    internal fun calculateConfidence(probs: List<TokenProb>?): Float {
        if (probs.isNullOrEmpty()) return CONFIDENCE_UNAVAILABLE

        val tokenConfidences = probs.mapNotNull { chosenTokenProb(it) }

        if (tokenConfidences.isEmpty()) return CONFIDENCE_UNAVAILABLE
        return tokenConfidences.average().toFloat()
    }

    /** Probabilidade [0,1] do token escolhido nesta posição, ou null se indisponível. */
    private fun chosenTokenProb(tp: TokenProb): Float? {
        // Schema novo: a própria entrada é o token escolhido, com seu logprob.
        tp.logprob?.let { return exp(it.toDouble()).toFloat() }

        // Schema antigo: candidato cujo tok_str == content; senão o primeiro candidato.
        val candidates = tp.probs ?: return null
        val chosen = candidates.firstOrNull { it.tokStr != null && it.tokStr == tp.content }
            ?: candidates.firstOrNull()
        return chosen?.prob
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

            // encodeDefaults = true E OBRIGATORIO AQUI, e a ausencia dele era um bug
            // silencioso. O kotlinx.serialization OMITE do JSON todo campo cujo valor
            // seja igual ao default declarado. Como `CompletionRequest` declara
            // `nProbs = 5`, `seed = 42` e `cachePrompt = false`, os tres sumiam do corpo
            // sempre que o valor coincidia com o default -- que e o caso normal.
            //
            // As consequencias eram todas invisiveis no codigo e visiveis so nos dados:
            //   - sem `n_probs`, o servidor nao devolve `completion_probabilities`, e a
            //     confianca do tier servidor saia -1 SEMPRE. O tier existe justamente
            //     por expor logprobs, e nunca os recebia.
            //   - sem `seed`, o servidor sorteia uma por requisicao: a mesma questao
            //     dava textos diferentes a cada chamada.
            //   - sem `cache_prompt`, vale o default do servidor (true), e uma questao
            //     herdava KV-cache da anterior -- a ordem virava variavel oculta.
            encodeDefaults = true
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
    val latencyMs: Long,
    /**
     * Telemetria vinda do bloco `timings` do servidor. Antes de 2026-09-05 nada disto
     * existia e o tier servidor gravava -1 em todas as colunas de custo da
     * `routing_log`: respondia, mas nao era mensuravel.
     */
    val promptTokens: Int = UNAVAILABLE_INT,
    val generatedTokens: Int = UNAVAILABLE_INT,
    val ingestionMs: Double = UNAVAILABLE,
    val generationMs: Double = UNAVAILABLE,
    val truncated: Boolean = false,
    val baseUrl: String? = null
) {
    /** Compute que o servidor reporta: prefill + decode, em ms. -1 se indisponivel. */
    val computeMs: Double
        get() = if (ingestionMs >= 0 && generationMs >= 0) ingestionMs + generationMs
                else UNAVAILABLE

    /**
     * H11 — o que sobrou depois de descontar o compute: rede, HTTP e serializacao.
     * Duracao menos duracao, ambas do lado do cliente (a segunda vem no corpo da
     * resposta como duracao, nao como timestamp). Nenhum relogio e cruzado.
     */
    val networkMs: Double
        get() = if (computeMs >= 0) latencyMs - computeMs else UNAVAILABLE

    companion object {
        const val UNAVAILABLE = -1.0
        const val UNAVAILABLE_INT = -1
    }
}

/** Servidor não inicializado ou inacessível (rede, timeout, erro HTTP). */
class ServerUnavailableException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
