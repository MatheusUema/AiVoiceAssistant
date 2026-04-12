package com.voiceassistant.ai_local.manager

import android.content.Context
import android.util.Log
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.service.LocalInferenceService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestra o ciclo de vida completo do modelo LLM local:
 *
 *   ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌───────┐
 *   │ NotLoaded│───▶│ Checking │───▶│ Loading  │───▶│WarmingUp │───▶│ Ready │
 *   └──────────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘    └───────┘
 *                        │               │               │
 *                        ▼               ▼               ▼
 *                   ┌──────────────────────────────────────────┐
 *                   │              Error(message)              │
 *                   └──────────────────────────────────────────┘
 *
 * Responsabilidades:
 *  1. Verificar capacidades do dispositivo (RAM, armazenamento)
 *  2. Copiar modelo de assets para filesDir (se necessário)
 *  3. Delegar o carregamento ao [LocalInferenceService]
 *  4. Executar warmup opcional para reduzir latência da 1ª pergunta
 *  5. Expor [modelState] para UI e [isAvailable] para o InferenceRouter
 *
 * Esta classe NÃO conhece MediaPipe — depende apenas da interface [LocalInferenceService].
 */
@Singleton
open class LocalModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localInferenceService: LocalInferenceService,
    private val deviceCapabilityChecker: DeviceCapabilityChecker,
    private val config: LocalModelConfig
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /**
     * Consultado pelo InferenceRouter para decidir se pode rotear para local.
     * Retorna true apenas quando o estado é [ModelState.Ready].
     */
    open val isAvailable: Boolean
        get() = _modelState.value is ModelState.Ready

    /**
     * Inicia o pipeline completo de inicialização em background.
     * Seguro chamar múltiplas vezes — ignora se já está carregando/pronto.
     */
    fun initializeAsync() {
        val currentState = _modelState.value
        if (currentState is ModelState.Ready || currentState is ModelState.Loading ||
            currentState is ModelState.Checking || currentState is ModelState.WarmingUp
        ) {
            Log.d(TAG, "initializeAsync ignorado — estado atual: $currentState")
            return
        }

        managerScope.launch {
            runInitPipeline()
        }
    }

    /**
     * Tenta recarregar o modelo após um erro.
     * Descarrega o estado anterior e recomeça do zero.
     */
    fun retryInitialization() {
        localInferenceService.unloadModel()
        _modelState.value = ModelState.NotLoaded
        initializeAsync()
    }

    /** Libera o modelo da memória. */
    fun release() {
        localInferenceService.unloadModel()
        _modelState.value = ModelState.NotLoaded
        Log.i(TAG, "Modelo liberado manualmente")
    }

    // ── Pipeline de inicialização ─────────────────────────────────────────

    private suspend fun runInitPipeline() {
        try {
            // Etapa 1: Verificar capacidades do dispositivo
            _modelState.value = ModelState.Checking
            val capability = deviceCapabilityChecker.check()

            if (!capability.canRunLocalModel) {
                val reason = capability.blockerReason() ?: "Dispositivo incompatível"
                _modelState.value = ModelState.Error(reason)
                Log.w(TAG, "Dispositivo não atende requisitos: $reason")
                return
            }

            // Etapa 2: Garantir que o arquivo do modelo está no filesystem
            val modelFile = ensureModelFile()
            if (modelFile == null) {
                _modelState.value = ModelState.Error(
                    "Modelo não encontrado. Coloque o arquivo em " +
                            "assets/${config.modelAssetPath}"
                )
                return
            }

            // Etapa 3: Carregar o modelo em memória
            _modelState.value = ModelState.Loading
            Log.i(TAG, "Carregando modelo: ${modelFile.absolutePath}")
            localInferenceService.loadModel(modelFile.absolutePath)

            // Etapa 4: Warmup opcional
            if (config.warmupEnabled) {
                _modelState.value = ModelState.WarmingUp
                Log.i(TAG, "Executando warmup...")
                localInferenceService.warmup(config.warmupPrompt)
            }

            // Pronto
            _modelState.value = ModelState.Ready
            Log.i(TAG, "Modelo pronto para uso")

        } catch (e: Exception) {
            val errorMsg = e.message ?: "Erro desconhecido no carregamento"
            _modelState.value = ModelState.Error(errorMsg)
            Log.e(TAG, "Pipeline de inicialização falhou: $errorMsg", e)
        }
    }

    /**
     * Garante que o modelo está disponível em filesDir.
     *
     * Ordem de busca:
     *  1. Já existe em filesDir → retorna diretamente
     *  2. Existe em assets → copia para filesDir
     *  3. Nenhum dos dois → retorna null
     */
    private fun ensureModelFile(): File? {
        val targetFile = deviceCapabilityChecker.getModelFile()

        if (targetFile.exists() && targetFile.length() > 0) {
            Log.d(TAG, "Modelo já presente em: ${targetFile.absolutePath}")
            return targetFile
        }

        if (!deviceCapabilityChecker.isModelInAssets()) {
            Log.w(TAG, "Modelo não encontrado em assets/${config.modelAssetPath}")
            return null
        }

        Log.i(TAG, "Copiando modelo de assets para: ${targetFile.absolutePath}")
        return try {
            targetFile.parentFile?.mkdirs()
            context.assets.open(config.modelAssetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "Modelo copiado: ${targetFile.length() / (1024 * 1024)}MB")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao copiar modelo: ${e.message}", e)
            targetFile.delete()
            null
        }
    }

    companion object {
        private const val TAG = "LocalModelManager"
    }
}

/**
 * Estados do ciclo de vida do modelo local.
 * Observado pela UI (para mostrar status de carregamento) e pelo InferenceRouter
 * (para decidir disponibilidade).
 */
sealed class ModelState {
    /** Modelo não foi carregado ainda (estado inicial) */
    data object NotLoaded : ModelState()

    /** Verificando capacidades do dispositivo */
    data object Checking : ModelState()

    /** Carregando pesos do modelo em memória */
    data object Loading : ModelState()

    /** Modelo carregado, executando warmup */
    data object WarmingUp : ModelState()

    /** Modelo pronto para inferência */
    data object Ready : ModelState()

    /** Carregamento falhou com [message] explicativo */
    data class Error(val message: String) : ModelState()

    override fun toString(): String = this::class.simpleName ?: "ModelState"
}
