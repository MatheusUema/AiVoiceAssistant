package com.voiceassistant.core.logging

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Ficha de um aparelho — uma linha por aparelho (Table 1 do artigo).
 *
 * É a tabela que dá sentido às outras duas: `routing_log` e `model_load_log` guardam
 * `deviceId`, e o cruzamento por essa chave é o que permite comparar o mesmo modelo
 * entre aparelhos, ou o mesmo aparelho entre modelos.
 *
 * Os campos são preenchidos automaticamente a partir do `Build`/`ActivityManager`, com
 * exceção de [label] e [notes], que são editoriais. Preencher à mão o que a plataforma
 * já sabe informar seria fonte de erro numa frota que deve crescer.
 */
@Entity(tableName = "device_profile")
data class DeviceProfileEntry(
    /** Identificador estável do aparelho; FK das outras tabelas. */
    @PrimaryKey val deviceId: String,

    /** Rótulo editorial, ex.: "dev1 — topo de linha". Default: o modelo comercial. */
    val label: String,

    val manufacturer: String,
    /** Modelo comercial, ex.: "23088PND5R". */
    val model: String,
    /** Codinome interno, ex.: "corot". */
    val deviceName: String,

    /** Versão do Android, ex.: "15". */
    val androidVersion: String,
    val apiLevel: Int,

    /** SoC, ex.: "Mediatek MT6985". Vazio em aparelhos que não expõem. */
    val soc: String,
    /** ABI nativa primária, ex.: "arm64-v8a". */
    val abi: String,

    /** RAM total reportada pelo sistema (MB) — menor que a nominal do aparelho. */
    val totalRamMb: Long,
    /** RAM disponível no momento em que a ficha foi capturada (MB). */
    val availableRamMb: Long,
    /** RAM nominal em GB, editorial (o sistema reporta menos que o anunciado). */
    val nominalRamGb: Int = -1,
    /** RAM estendida por swap/zram, se o fabricante oferece (GB). */
    val extendedRamGb: Int = -1,

    val cpuCores: Int,
    /** Clock máximo observado nos cpufreq do sistema (GHz). -1 se não legível. */
    val cpuMaxGhz: Double = -1.0,

    /** Espaço livre no armazenamento interno (MB) quando a ficha foi capturada. */
    val availableStorageMb: Long,

    /** Quando a ficha foi capturada/atualizada. */
    val capturedAt: Long = System.currentTimeMillis(),

    /** Observações editoriais, ex.: "gate de RAM; valida o limite do modelo local". */
    val notes: String? = null
)

@Dao
interface DeviceProfileDao {

    /**
     * Upsert porque a ficha é recapturada a cada início: RAM disponível e espaço livre
     * mudam, e o mais recente é o que descreve as condições da coleta.
     */
    @Upsert
    suspend fun upsert(entry: DeviceProfileEntry)

    @Query("SELECT * FROM device_profile")
    suspend fun getAll(): List<DeviceProfileEntry>

    @Query("SELECT * FROM device_profile WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): DeviceProfileEntry?

    /** Só o rótulo e as notas são editoriais — o resto vem da plataforma. */
    @Query("UPDATE device_profile SET label = :label, notes = :notes WHERE deviceId = :deviceId")
    suspend fun annotate(deviceId: String, label: String, notes: String?)
}
