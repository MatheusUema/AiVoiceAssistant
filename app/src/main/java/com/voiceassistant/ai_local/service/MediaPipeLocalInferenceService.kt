package com.voiceassistant.ai_local.service

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.voiceassistant.ai_local.model.LocalModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementação de [LocalInferenceService] usando MediaPipe LLM Inference Task.
 *
 * ⚠️ **Não é mais o binding padrão** — o tier local roda em [LlamaCppLocalInferenceService]
 * (llama.cpp/JNI), que dá TTFT e tokens/s reais e compartilha o motor com o `llama-server`.
 * Esta classe fica no repositório apenas para a comparação de paridade de saída
 * MediaPipe × llama.cpp descrita no plano (doc 06 §1.4). Requer modelos `.task`.
 *
 * Responsabilidades:
 *  - Criar [LlmInference] a partir de um caminho de modelo no filesystem
 *  - Executar inferência síncrona em background thread
 *  - Gerenciar o ciclo de vida do engine (load / generate / unload)
 *
 * Esta classe NÃO decide quando carregar ou descarregar o modelo —
 * isso é responsabilidade do [LocalModelManager].
 *
 * Modelos suportados (.task / .litertlm / .bin):
 *  - Gemma 3 1B IT (recomendado): https://huggingface.co/litert-community/Gemma3-1B-IT
 *  - Gemma 3n E2B/E4B, Gemma 2 2B, Phi-2, Falcon 1B
 *
 * O LocalModelManager copia o modelo de assets/ para filesDir
 * e chama loadModel(path) com o caminho absoluto.
 */
@Singleton
class MediaPipeLocalInferenceService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: LocalModelConfig
) : LocalInferenceService {

    private var llmInference: LlmInference? = null

    @Volatile
    override var isModelLoaded: Boolean = false
        private set

    override val isAvailable: Boolean
        get() = isModelLoaded

    override suspend fun loadModel(modelPath: String) = withContext(Dispatchers.IO) {
        if (isModelLoaded) {
            Log.d(TAG, "Modelo já carregado, ignorando loadModel()")
            return@withContext
        }

        Log.i(TAG, "Carregando modelo de: $modelPath")
        val startTime = System.currentTimeMillis()

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                // No MediaPipe `maxTokens` é o KV-cache total (prompt + resposta).
                .setMaxTokens(config.contextSize)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isModelLoaded = true

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Modelo carregado em ${elapsed}ms")
        } catch (e: Exception) {
            isModelLoaded = false
            llmInference = null
            Log.e(TAG, "Falha ao carregar modelo: ${e.message}", e)
            throw LocalInferenceException(
                "Falha ao carregar modelo local: ${e.message}",
                e
            )
        }
    }

    /**
     * Cria uma sessão efêmera com os parâmetros de geração configurados.
     * A sessão é usada para uma única interação e fechada em seguida.
     */
    private fun createSession(): LlmInferenceSession {
        val inference = llmInference
            ?: throw LocalModelNotReadyException()

        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(config.temperature)
            .setTopK(config.topK)
            .setTopP(config.topP)
            .build()

        return LlmInferenceSession.createFromOptions(inference, sessionOptions)
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        if (llmInference == null) throw LocalModelNotReadyException()

        try {
            val startTime = System.currentTimeMillis()

            val session = createSession()
            session.addQueryChunk(prompt)
            val response = session.generateResponse()
            session.close()

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inferência local: ${response.length} chars em ${elapsed}ms")

            if (response.isBlank()) {
                throw LocalInferenceException("Modelo retornou resposta vazia")
            }

            response
        } catch (e: LocalInferenceException) {
            throw e
        } catch (e: LocalModelNotReadyException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro na geração local: ${e.message}", e)
            throw LocalInferenceException(
                "Erro durante inferência local: ${e.message}",
                e
            )
        }
    }

    override suspend fun warmup(prompt: String) {
        if (!isModelLoaded) {
            throw LocalModelNotReadyException("Não é possível fazer warmup sem modelo carregado")
        }

        Log.d(TAG, "Iniciando warmup...")
        val startTime = System.currentTimeMillis()

        try {
            withContext(Dispatchers.IO) {
                val session = createSession()
                session.addQueryChunk(prompt)
                session.generateResponse()
                session.close()
            }
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Warmup concluído em ${elapsed}ms")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Warmup falhou em ${elapsed}ms (não-fatal): ${e.message}")
        }
    }

    override fun unloadModel() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao fechar LlmInference: ${e.message}")
        } finally {
            llmInference = null
            isModelLoaded = false
            Log.i(TAG, "Modelo descarregado")
        }
    }

    companion object {
        private const val TAG = "MediaPipeLLM"
    }
}
