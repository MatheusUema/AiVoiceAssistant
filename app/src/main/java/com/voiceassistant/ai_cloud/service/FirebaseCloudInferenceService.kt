package com.voiceassistant.ai_cloud.service

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.HarmBlockThreshold
import com.google.firebase.ai.type.HarmCategory
import com.google.firebase.ai.type.SafetySetting
import com.google.firebase.ai.type.generationConfig
import com.voiceassistant.ai_cloud.model.CloudBackend
import com.voiceassistant.ai_cloud.model.CloudModelConfig
import com.voiceassistant.ai_cloud.model.SafetyBlockThreshold
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementação de [CloudInferenceService] usando Firebase AI Logic (Gemini).
 *
 * O [GenerativeModel] é criado de forma lazy na primeira chamada a [generate]
 * para evitar crash se o Firebase não estiver configurado.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │  PRÉ-REQUISITOS (sem eles, [isAvailable] = false):        │
 * │                                                             │
 * │  1. Criar projeto no Firebase Console                       │
 * │     https://console.firebase.google.com                     │
 * │                                                             │
 * │  2. Abrir Firebase AI Logic no console (Build → AI Logic)   │
 * │     e ativar o Gemini Developer API                         │
 * │                                                             │
 * │  3. Baixar google-services.json e colocar em app/           │
 * │                                                             │
 * │  4. Registrar o applicationId do app no Firebase:           │
 * │     - Debug:   com.voiceassistant.debug                     │
 * │     - Release: com.voiceassistant                           │
 * │                                                             │
 * │  Após isso, esta classe funciona sem mais alterações.       │
 * └─────────────────────────────────────────────────────────────┘
 */
@Singleton
class FirebaseCloudInferenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: CloudModelConfig
) : CloudInferenceService {

    /**
     * True quando o Firebase foi inicializado (ex.: `google-services.json` aplicado).
     * O [GenerativeModel] continua sendo criado de forma lazy na primeira [generate];
     * o [InferenceRouter] precisa disto **antes** da primeira chamada, senão `isAvailable`
     * ficaria sempre false e nenhuma rota cloud seria escolhida.
     */
    override val isAvailable: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    private var model: GenerativeModel? = null

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val generativeModel = getOrCreateModel()

        try {
            val response = generativeModel.generateContent(prompt)

            val text = response.text
            if (text.isNullOrBlank()) {
                throw CloudInferenceException(
                    "Modelo retornou resposta vazia",
                    CloudErrorReason.EMPTY_RESPONSE
                )
            }

            text
        } catch (e: CloudInferenceException) {
            throw e
        } catch (e: IOException) {
            throw CloudInferenceException(
                "Erro de rede ao acessar o Gemini: ${e.message}",
                CloudErrorReason.NETWORK,
                e
            )
        } catch (e: FirebaseException) {
            throw mapFirebaseException(e)
        } catch (e: Exception) {
            throw classifyGenericException(e)
        }
    }

    /**
     * Inicializa o [GenerativeModel] via Firebase AI Logic (Gemini Developer API).
     *
     * O backend [GenerativeBackend.googleAI] usa o Gemini Developer API (plano Spark,
     * sem billing obrigatório). Para usar o Vertex AI Gemini API (com billing),
     * troque para [GenerativeBackend.vertexAI].
     */
    private fun getOrCreateModel(): GenerativeModel {
        model?.let { return it }

        return try {
            val safetySettings = listOf(
                SafetySetting(HarmCategory.HARASSMENT, mapThreshold(config.safetyBlockThreshold)),
                SafetySetting(HarmCategory.HATE_SPEECH, mapThreshold(config.safetyBlockThreshold)),
                SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, mapThreshold(config.safetyBlockThreshold)),
                SafetySetting(HarmCategory.DANGEROUS_CONTENT, mapThreshold(config.safetyBlockThreshold))
            )

            val genConfig = generationConfig {
                temperature = config.temperature
                topP = config.topP
                maxOutputTokens = config.maxOutputTokens
            }

            val backend = when (config.backend) {
                CloudBackend.GOOGLE_AI -> GenerativeBackend.googleAI()
                CloudBackend.VERTEX_AI -> GenerativeBackend.vertexAI()
            }

            Firebase.ai(backend = backend)
                .generativeModel(
                    modelName = config.modelName,
                    generationConfig = genConfig,
                    safetySettings = safetySettings
                ).also {
                    model = it
                    Log.i(TAG, "GenerativeModel criado: ${config.modelName}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao criar GenerativeModel: ${e.message}")
            throw CloudInferenceException(
                buildString {
                    append("Firebase AI Logic não inicializado. ")
                    append("Verifique se o google-services.json está em app/ ")
                    append("e se o Firebase AI Logic está habilitado no console.")
                },
                CloudErrorReason.AUTH_ERROR,
                e
            )
        }
    }

    private fun mapThreshold(threshold: SafetyBlockThreshold): HarmBlockThreshold =
        when (threshold) {
            SafetyBlockThreshold.NONE -> HarmBlockThreshold.NONE
            SafetyBlockThreshold.ONLY_HIGH -> HarmBlockThreshold.ONLY_HIGH
            SafetyBlockThreshold.MEDIUM_AND_ABOVE -> HarmBlockThreshold.MEDIUM_AND_ABOVE
            SafetyBlockThreshold.LOW_AND_ABOVE -> HarmBlockThreshold.LOW_AND_ABOVE
        }

    private fun mapFirebaseException(e: FirebaseException): CloudInferenceException {
        val message = e.message ?: "Erro Firebase desconhecido"
        return CloudInferenceException(message, classifyByMessage(message), e)
    }

    /**
     * Quota/rate errors from the Gemini API are often thrown as generic
     * [Exception] or [ServerException] rather than [FirebaseException].
     * This catches those cases using keyword detection on the message.
     */
    private fun classifyGenericException(e: Exception): CloudInferenceException {
        val message = e.message ?: "Erro desconhecido na inferência cloud"
        val reason = classifyByMessage(message)
        return CloudInferenceException(message, reason, e)
    }

    private fun classifyByMessage(message: String): CloudErrorReason {
        val lower = message.lowercase()
        return when {
            "quota" in lower || "rate" in lower || "limit" in lower ||
                "resource_exhausted" in lower || "429" in lower ->
                CloudErrorReason.QUOTA_EXCEEDED
            "permission" in lower || "auth" in lower || "api_key" in lower ||
                "403" in lower || "unauthenticated" in lower ->
                CloudErrorReason.AUTH_ERROR
            "safety" in lower || "blocked" in lower ->
                CloudErrorReason.SAFETY_BLOCKED
            else ->
                CloudErrorReason.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "FirebaseCloud"
    }
}
