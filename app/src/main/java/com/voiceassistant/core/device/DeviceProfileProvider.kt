package com.voiceassistant.core.device

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import com.voiceassistant.core.logging.DeviceProfileDao
import com.voiceassistant.core.logging.DeviceProfileEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Descobre e persiste a ficha do aparelho, e fornece o `deviceId` que amarra as três
 * tabelas do estudo.
 *
 * Por que automático: a frota deve crescer para além dos 3 aparelhos da Table 1, e
 * preencher à mão o que a plataforma já informa é fonte garantida de erro de transcrição
 * — justamente nos campos usados para explicar os resultados. Só `label` e `notes` ficam
 * editoriais, via [annotate].
 */
@Singleton
class DeviceProfileProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DeviceProfileDao
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedId: String? = null

    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    /**
     * Identificador estável do aparelho.
     *
     * Baseado no `ANDROID_ID`, que é por (aparelho, app, usuário) e sobrevive a
     * reinstalações com a mesma chave de assinatura — o que importa aqui, já que o app é
     * reinstalado a cada troca de modelo. Prefixado pelo codinome para as linhas do CSV
     * serem legíveis sem consultar a tabela: `corot-a1b2c3d4`.
     */
    suspend fun deviceId(): String {
        cachedId?.let { return it }
        return mutex.withLock {
            cachedId ?: buildDeviceId().also { cachedId = it }
        }
    }

    /**
     * Captura a ficha e grava (upsert). Chamado na inicialização: RAM disponível e
     * espaço livre mudam entre execuções, e o mais recente descreve as condições da
     * coleta em curso.
     *
     * Falhas aqui não podem derrubar o app — sem ficha, perde-se contexto de análise,
     * não a coleta.
     *
     * @return o deviceId, ou null se não foi possível gravar.
     */
    suspend fun captureAndStore(): String? = try {
        val id = deviceId()
        val existing = dao.get(id)
        dao.upsert(snapshot(id, existing))
        Log.i(TAG, "Ficha do aparelho registrada: $id")
        id
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao registrar a ficha do aparelho: ${e.message}")
        null
    }

    /** Rótulo e observações editoriais, ex.: `annotate("dev1 — topo de linha", "...")`. */
    suspend fun annotate(label: String, notes: String?) {
        runCatching { dao.annotate(deviceId(), label, notes) }
            .onFailure { Log.w(TAG, "Falha ao anotar a ficha: ${it.message}") }
    }

    private fun snapshot(id: String, existing: DeviceProfileEntry?): DeviceProfileEntry {
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return DeviceProfileEntry(
            deviceId = id,
            // Preserva o rótulo editorial já atribuído; só usa o modelo como default.
            label = existing?.label ?: Build.MODEL.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            deviceName = Build.DEVICE.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            soc = readSoc(),
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            totalRamMb = memInfo.totalMem / BYTES_PER_MB,
            availableRamMb = memInfo.availMem / BYTES_PER_MB,
            nominalRamGb = existing?.nominalRamGb ?: -1,
            extendedRamGb = existing?.extendedRamGb ?: -1,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuMaxGhz = readMaxCpuGhz(),
            availableStorageMb = readAvailableStorageMb(),
            notes = existing?.notes
        )
    }

    @SuppressLint("HardwareIds")
    private fun buildDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val suffix = androidId.takeIf { it.isNotBlank() }?.take(8)
            // Sem ANDROID_ID (raro), cai para algo derivado do próprio aparelho. Não é
            // único entre dois aparelhos idênticos, mas é melhor que um id vazio — e o
            // caso pode ser desempatado pelo `label`.
            ?: "${Build.DEVICE}${Build.MODEL}".hashCode().toUInt().toString(16).take(8)

        val prefix = Build.DEVICE?.takeIf { it.isNotBlank() } ?: "device"
        return "$prefix-$suffix"
    }

    /** SoC exposto a partir da API 31; antes disso, só o codinome da placa. */
    private fun readSoc(): String = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
        }
        else -> Build.HARDWARE.orEmpty()
    }

    /** Maior `cpuinfo_max_freq` entre os núcleos, em GHz. -1 se não for legível. */
    private fun readMaxCpuGhz(): Double {
        val maxKhz = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.canRead() }
                    ?.readText()
                    ?.trim()
                    ?.toLongOrNull()
            }.getOrNull()
        }.maxOrNull() ?: return -1.0
        return maxKhz / 1_000_000.0
    }

    private fun readAvailableStorageMb(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes / BYTES_PER_MB
    }.getOrDefault(-1L)

    companion object {
        private const val TAG = "DeviceProfile"
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
