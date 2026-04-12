package com.voiceassistant.ai_local.manager

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.voiceassistant.ai_local.model.LocalModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifica se o dispositivo atende aos requisitos mínimos para
 * executar o modelo LLM local via MediaPipe.
 *
 * Checagens:
 *  - RAM total e disponível
 *  - Espaço em disco para o modelo
 *  - Presença do arquivo do modelo no armazenamento interno
 *  - Nível da API (MediaPipe LLM requer API 26+)
 *
 * O [LocalModelManager] consulta esta classe antes de tentar carregar o modelo,
 * evitando crashes por OOM ou FileNotFoundException.
 */
@Singleton
open class DeviceCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: LocalModelConfig
) {
    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    fun check(): DeviceCapability {
        val totalRamMb = getTotalRamMb()
        val availableRamMb = getAvailableRamMb()
        val availableStorageMb = getAvailableStorageMb()
        val modelFile = getModelFile()
        val modelExists = modelFile.exists()
        val modelSizeMb = if (modelExists) modelFile.length() / (1024 * 1024) else 0L

        val ramSufficient = totalRamMb >= config.minRamMb
        val storageSufficient = modelExists || availableStorageMb >= config.modelSizeMb
        val apiLevelOk = Build.VERSION.SDK_INT >= 26

        val canRun = ramSufficient && apiLevelOk
        val modelReady = modelExists && modelSizeMb > 0

        Log.d(TAG, buildString {
            append("Device check: ")
            append("RAM=${totalRamMb}MB(avail=${availableRamMb}MB, min=${config.minRamMb}MB) ")
            append("storage=${availableStorageMb}MB ")
            append("model=${if (modelExists) "${modelSizeMb}MB" else "ausente"} ")
            append("api=${Build.VERSION.SDK_INT} ")
            append("→ canRun=$canRun modelReady=$modelReady")
        })

        return DeviceCapability(
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            availableStorageMb = availableStorageMb,
            modelFileExists = modelExists,
            modelSizeMb = modelSizeMb,
            meetsRamRequirement = ramSufficient,
            meetsStorageRequirement = storageSufficient,
            meetsApiLevel = apiLevelOk,
            canRunLocalModel = canRun,
            isModelReady = modelReady
        )
    }

    /**
     * Retorna o [File] onde o modelo deve estar (ou será copiado para).
     * MediaPipe requer um path no filesystem — não aceita assets diretamente.
     */
    fun getModelFile(): File =
        File(context.filesDir, config.modelFileName)

    /**
     * Verifica se o modelo está nos assets do APK (para cópia inicial).
     */
    fun isModelInAssets(): Boolean = try {
        context.assets.open(config.modelAssetPath).use { true }
    } catch (_: Exception) {
        false
    }

    private fun getTotalRamMb(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableRamMb(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    private fun getAvailableStorageMb(): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBytes / (1024 * 1024)
    } catch (_: Exception) {
        0L
    }

    companion object {
        private const val TAG = "DeviceCapability"
    }
}

/**
 * Snapshot das capacidades do dispositivo no momento da verificação.
 * Imutável — gere um novo chamando [DeviceCapabilityChecker.check].
 */
data class DeviceCapability(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val availableStorageMb: Long,
    val modelFileExists: Boolean,
    val modelSizeMb: Long,
    val meetsRamRequirement: Boolean,
    val meetsStorageRequirement: Boolean,
    val meetsApiLevel: Boolean,
    /** True se o dispositivo tem hardware suficiente (RAM + API level) */
    val canRunLocalModel: Boolean,
    /** True se o arquivo do modelo existe e tem tamanho > 0 */
    val isModelReady: Boolean
) {
    fun blockerReason(): String? = when {
        !meetsApiLevel -> "Android API 26+ necessário (atual: ${android.os.Build.VERSION.SDK_INT})"
        !meetsRamRequirement -> "RAM insuficiente (${totalRamMb}MB, mínimo requerido pelo modelo)"
        !isModelReady -> "Modelo local não encontrado no dispositivo"
        else -> null
    }
}
