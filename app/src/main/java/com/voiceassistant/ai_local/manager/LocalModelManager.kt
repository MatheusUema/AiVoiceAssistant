package com.voiceassistant.ai_local.manager

import android.content.Context
import android.os.Build
import android.util.Log
import com.voiceassistant.ai_local.model.LocalModelConfig
import com.voiceassistant.ai_local.model.LocalModelVariant
import com.voiceassistant.ai_local.service.LlamaCppLocalInferenceService
import com.voiceassistant.ai_local.service.LocalInferenceService
import com.voiceassistant.core.device.DeviceProfileProvider
import com.voiceassistant.core.logging.ModelLoadLogEntry
import com.voiceassistant.core.logging.RoutingLogger
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
import kotlin.system.measureTimeMillis

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
 *  3. Delegar o carregamento ao [LocalInferenceService], caindo para o modelo
 *     [LocalModelConfig.fallback] se o primário não couber
 *  4. Executar warmup opcional para reduzir latência da 1ª pergunta
 *  5. Expor [modelState] para UI, [isAvailable] para o InferenceRouter e
 *     [loadAttempts] para o log de carregamento do estudo (`model_load_log`)
 *
 * Esta classe NÃO conhece o runtime — depende apenas da interface [LocalInferenceService].
 */
@Singleton
open class LocalModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localInferenceService: LocalInferenceService,
    private val deviceCapabilityChecker: DeviceCapabilityChecker,
    private val config: LocalModelConfig,
    private val routingLogger: RoutingLogger,
    private val deviceProfileProvider: DeviceProfileProvider
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _loadAttempts = MutableStateFlow<List<LocalModelLoadAttempt>>(emptyList())

    /**
     * Histórico de tentativas de carga desta sessão — inclusive as que falharam.
     * É a fonte da tabela `model_load_log`: no estudo, "o modelo não coube no aparelho"
     * é um resultado, não um erro a descartar.
     */
    val loadAttempts: StateFlow<List<LocalModelLoadAttempt>> = _loadAttempts.asStateFlow()

    /** Variante efetivamente em uso, ou null se nenhuma carregou. */
    @Volatile
    var activeVariant: LocalModelVariant? = null
        private set

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
        activeVariant = null
        _modelState.value = ModelState.NotLoaded
        Log.i(TAG, "Modelo liberado manualmente")
    }

    // ── Pipeline de inicialização ─────────────────────────────────────────

    private suspend fun runInitPipeline() {
        try {
            // Etapa 1: Verificar capacidades do dispositivo
            _modelState.value = ModelState.Checking
            val capability = deviceCapabilityChecker.check()

            if (!capability.meetsApiLevel) {
                val reason = capability.blockerReason() ?: "Dispositivo incompatível"
                _modelState.value = ModelState.Error(reason)
                Log.w(TAG, "Dispositivo não atende requisitos: $reason")
                return
            }

            // Etapa 2: Tentar cada variante na ordem (primário → fallback).
            // Se o primário não carrega no Device 2, a queda para o menor é registrada.
            val variants = config.loadOrder(capability.totalRamMb)
            if (variants.isEmpty()) {
                _modelState.value = ModelState.Error("Nenhum modelo local configurado")
                return
            }

            // A ficha do aparelho tem que existir antes da primeira linha de log: é ela
            // que dá sentido ao `deviceId` das outras duas tabelas.
            deviceProfileProvider.captureAndStore()

            val crashedVariant = consumeCrashMarker()
            val attempts = mutableListOf<LocalModelLoadAttempt>()

            for (variant in variants) {
                val attempt = tryLoad(variant, capability, crashedVariant)
                attempts += attempt
                _loadAttempts.value = attempts.toList()
                persist(attempt, capability)

                if (attempt.outcome == LoadOutcome.SUCCESS) {
                    activeVariant = variant
                    _modelState.value = ModelState.Ready
                    Log.i(TAG, "Modelo pronto para uso: ${variant.label}")
                    return
                }
                Log.w(TAG, "Variante ${variant.label} indisponível: ${attempt.outcome} — ${attempt.reason}")
            }

            _modelState.value = ModelState.Error(
                attempts.lastOrNull()?.reason ?: "Nenhum modelo local pôde ser carregado"
            )

        } catch (e: Exception) {
            val errorMsg = e.message ?: "Erro desconhecido no carregamento"
            _modelState.value = ModelState.Error(errorMsg)
            Log.e(TAG, "Pipeline de inicialização falhou: $errorMsg", e)
        }
    }

    /**
     * Tenta uma variante e devolve o desfecho **sem lançar**: toda falha vira dado.
     */
    private suspend fun tryLoad(
        variant: LocalModelVariant,
        capability: DeviceCapability,
        crashedVariant: String?
    ): LocalModelLoadAttempt {
        fun attempt(outcome: LoadOutcome, reason: String?, sizeBytes: Long = 0L, loadMs: Long = 0L, warmupMs: Long = 0L) =
            LocalModelLoadAttempt(variant, outcome, reason, sizeBytes, loadMs, warmupMs)

        if (crashedVariant == variant.label) {
            // O processo morreu durante o carregamento anterior desta mesma variante:
            // quase sempre o OOM killer. Não repetir — registrar e seguir para o fallback.
            return attempt(
                LoadOutcome.KILLED_PREVIOUS_ATTEMPT,
                "processo foi encerrado durante a carga anterior (provável OOM do sistema)"
            )
        }

        if (capability.totalRamMb in 1 until variant.minRamMb) {
            return attempt(
                LoadOutcome.BLOCKED_BY_DEVICE,
                "RAM total ${capability.totalRamMb}MB < mínimo ${variant.minRamMb}MB do modelo"
            )
        }

        val modelFile = ensureModelFile(variant)
            ?: return attempt(
                LoadOutcome.MISSING_FILE,
                "modelo não encontrado em assets/${variant.assetPath} nem em filesDir"
            )

        _modelState.value = ModelState.Loading
        Log.i(TAG, "Carregando modelo: ${modelFile.absolutePath}")

        writeCrashMarker(variant)
        // Cronometrado fora do try: o tempo até a falha também é dado (H6).
        val startNs = System.nanoTime()
        try {
            localInferenceService.loadModel(modelFile.absolutePath)
        } catch (e: Exception) {
            clearCrashMarker()
            return attempt(
                LoadOutcome.FAILED,
                e.message ?: "falha de carga sem mensagem",
                modelFile.length(),
                elapsedMs(startNs)
            )
        }
        val loadMs = elapsedMs(startNs)
        clearCrashMarker()

        // Etapa 3: Warmup opcional (H6 — custo da primeira inferência)
        var warmupMs = 0L
        if (config.warmupEnabled) {
            _modelState.value = ModelState.WarmingUp
            Log.i(TAG, "Executando warmup...")
            warmupMs = measureTimeMillis {
                runCatching { localInferenceService.warmup(config.warmupPrompt) }
                    .onFailure { Log.w(TAG, "Warmup falhou (não-fatal): ${it.message}") }
            }
        }

        return attempt(LoadOutcome.SUCCESS, null, modelFile.length(), loadMs, warmupMs)
    }

    /**
     * Resolve o arquivo do modelo, na ordem:
     *  1. `filesDir/<nome>` → usa direto
     *  2. `getExternalFilesDir("models")/<nome>` → usa **no lugar**, sem copiar
     *  3. `assets/<caminho>` → copia para filesDir
     *  4. nenhum → null
     *
     * O passo 2 é o caminho normal do estudo: o E2B tem 3,43 GB, então empacotar no APK
     * (e ainda duplicar ao copiar para filesDir) desperdiçaria ~7 GB num aparelho de 64 GB.
     * O `adb push` para o diretório externo do app não exige permissão nenhuma e o
     * llama.cpp faz mmap direto de lá. Ver `scripts/push-model.ps1`.
     */
    private fun ensureModelFile(variant: LocalModelVariant): File? {
        val targetFile = deviceCapabilityChecker.getModelFile(variant)

        if (targetFile.exists() && targetFile.length() > 0) {
            Log.d(TAG, "Modelo já presente em: ${targetFile.absolutePath}")
            return targetFile
        }

        val sideloaded = File(context.getExternalFilesDir(EXTERNAL_MODELS_DIR), variant.fileName)
        if (sideloaded.exists() && sideloaded.length() > 0) {
            Log.i(TAG, "Usando modelo enviado por adb: ${sideloaded.absolutePath}")
            return sideloaded
        }

        if (!deviceCapabilityChecker.isModelInAssets(variant)) {
            Log.w(
                TAG,
                "Modelo ${variant.fileName} não encontrado: nem em filesDir, nem em " +
                        "${sideloaded.absolutePath}, nem em assets/${variant.assetPath}"
            )
            return null
        }

        Log.i(TAG, "Copiando modelo de assets para: ${targetFile.absolutePath}")
        return try {
            targetFile.parentFile?.mkdirs()
            context.assets.open(variant.assetPath).use { input ->
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

    // ── Detecção de morte do processo durante a carga ─────────────────────
    // Carregar um GGUF grande num aparelho de 4 GB pode fazer o sistema matar o app
    // antes de qualquer exceção chegar ao Kotlin. O marcador em disco transforma esse
    // desaparecimento silencioso num resultado observável na próxima inicialização.

    /**
     * Persiste a tentativa em `model_load_log` — inclusive as que falharam.
     *
     * As condições do aparelho (RAM total e disponível) vão junto de propósito: sem
     * elas, um `FAILED` no Device 2 é só uma string, e não a evidência de que 3,43 GB de
     * pesos não cabiam nos ~3,6 GB reportados naquele momento.
     */
    private suspend fun persist(attempt: LocalModelLoadAttempt, capability: DeviceCapability) {
        val llama = localInferenceService as? LlamaCppLocalInferenceService
        val info = llama?.modelInfo
        routingLogger.logModelLoad(
            ModelLoadLogEntry(
                deviceId = deviceProfileProvider.deviceId(),
                modelId = attempt.variant.label,
                modelSizeBytes = attempt.modelSizeBytes,
                loadMs = attempt.loadMs,
                warmupMs = attempt.warmupMs,
                outcome = attempt.outcome.name,
                reason = attempt.reason,
                runtime = RUNTIME,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                // As threads efetivas, não o `config.threads` — que vale 0 quando se pede
                // a heurística, e gravar 0 tornaria a coluna inútil justamente para
                // explicar diferenças de desempenho entre aparelhos.
                threads = if (attempt.outcome == LoadOutcome.SUCCESS) llama?.threads ?: -1 else -1,
                contextSize = info?.contextSize ?: -1,
                // As features de CPU escolhidas em runtime entram junto dos backends:
                // dois aparelhos podem rodar kernels diferentes, e sem isso a comparação
                // entre eles ficaria sem explicação.
                backends = listOfNotNull(info?.backends, llama?.systemInfo)
                    .joinToString(" | ")
                    .takeIf { it.isNotBlank() },
                vulkanEnabled = info?.backends?.contains("Vulkan", ignoreCase = true) == true,
                totalRamMb = capability.totalRamMb,
                availableRamMb = capability.availableRamMb
            )
        )
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    private val crashMarker: File get() = File(context.filesDir, CRASH_MARKER_NAME)

    private fun writeCrashMarker(variant: LocalModelVariant) {
        runCatching { crashMarker.writeText(variant.label) }
            .onFailure { Log.w(TAG, "Não foi possível escrever o marcador de carga: ${it.message}") }
    }

    private fun clearCrashMarker() {
        runCatching { crashMarker.delete() }
    }

    /** @return label da variante que estava carregando quando o processo morreu, ou null. */
    private fun consumeCrashMarker(): String? {
        val marker = crashMarker
        if (!marker.exists()) return null
        val label = runCatching { marker.readText().trim() }.getOrNull()
        marker.delete()
        if (!label.isNullOrEmpty()) {
            Log.w(TAG, "Carga anterior de '$label' não terminou — processo foi encerrado")
        }
        return label
    }

    companion object {
        private const val TAG = "LocalModelManager"
        private const val CRASH_MARKER_NAME = ".model_load_in_progress"
        private const val RUNTIME = "llamacpp"

        /** Subpasta de `getExternalFilesDir` onde o `adb push` deposita os GGUF. */
        const val EXTERNAL_MODELS_DIR = "models"
    }
}

/** Desfecho de uma tentativa de carga — vira a coluna `outcome` da `model_load_log`. */
enum class LoadOutcome {
    SUCCESS,
    /** Arquivo GGUF ausente em assets e em filesDir. */
    MISSING_FILE,
    /** Gate de hardware barrou antes de tentar (RAM abaixo do mínimo da variante). */
    BLOCKED_BY_DEVICE,
    /** llama.cpp recusou a carga (ex.: KV-cache não coube). */
    FAILED,
    /** O processo foi morto durante a carga anterior desta variante (provável OOM). */
    KILLED_PREVIOUS_ATTEMPT
}

/**
 * Uma tentativa de carregar um modelo local, com o custo medido.
 * Cobre H6 (tempo de carga + warmup) e H7 (tamanho em disco) do plano de hardware.
 */
data class LocalModelLoadAttempt(
    val variant: LocalModelVariant,
    val outcome: LoadOutcome,
    val reason: String?,
    val modelSizeBytes: Long,
    val loadMs: Long,
    val warmupMs: Long
)

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
