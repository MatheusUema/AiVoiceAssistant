package com.voiceassistant.core.logging

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Energia de um bloco de N questões (H5) — uma linha por bloco.
 *
 * Existe porque o valor era **calculado e perdido**: o `BenchmarkRunner` computava o
 * `BlockEnergy` e o devolvia no relatório, mas nada o persistia. Na coleta do Device 1
 * três dos doze blocos se perderam quando o logcat rotacionou, e não havia como
 * recuperá-los — a coleta durou duas horas e não dá para repetir só um pedaço.
 *
 * Fica em tabela própria, e não como colunas da `routing_log`, porque a granularidade é
 * outra: uma linha por bloco contra vinte por bloco. Repetir a energia em cada questão
 * sugeriria que ela foi medida por questão, o que é justamente o que o contador de
 * bateria **não** consegue fazer — ver [com.voiceassistant.core.telemetry.EnergyMeter].
 *
 * O join com a `routing_log` é por [blockId], coluna que já existe lá.
 */
@Entity(tableName = "block_energy")
data class BlockEnergyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Chave de join com `routing_log.blockId`. */
    val blockId: String,

    /** Aparelho — FK para `device_profile`. */
    val deviceId: String = "",

    /** Modelo que respondeu as questões do bloco. */
    val modelId: String = "",

    /** Cenário da coleta: "local-only", "lan", "internet". */
    val scenario: String = "",

    /** N: questões medidas neste bloco. O divisor de [energyUahPerQuestion]. */
    val questions: Int,

    // ── Contadores brutos ────────────────────────────────────────────────────
    // Guardados junto do resultado de propósito: se a conta estiver errada, dá para
    // refazê-la a partir daqui sem repetir a coleta.

    /** `BATTERY_PROPERTY_CHARGE_COUNTER` no início (µAh). -1 se indisponível. */
    val chargeStartUah: Long = UNAVAILABLE_LONG,
    /** O mesmo contador no fim (µAh). Decresce ao descarregar. */
    val chargeEndUah: Long = UNAVAILABLE_LONG,

    /** Consumo total do bloco (µAh) = início − fim. -1 se indisponível. */
    val energyUahTotal: Long = UNAVAILABLE_LONG,
    /** Consumo por questão (µAh). -1 se indisponível. */
    val energyUahPerQuestion: Double = UNAVAILABLE_DOUBLE,

    val capacityStartPercent: Long = UNAVAILABLE_LONG,
    val capacityEndPercent: Long = UNAVAILABLE_LONG,

    val tempStartCelsius: Double = UNAVAILABLE_DOUBLE,
    val tempEndCelsius: Double = UNAVAILABLE_DOUBLE,
    /** Aquecimento no bloco. Negativo significa que o aparelho esfriou. */
    val deltaTempCelsius: Double = UNAVAILABLE_DOUBLE,

    val timestampStart: Long = 0,
    val timestampEnd: Long = 0,

    /**
     * True se o aparelho esteve no carregador em algum ponto — **bloco inválido**.
     * O contador sobe em vez de descer e o consumo sai negativo ou absurdo.
     */
    val charging: Boolean = false,

    /** False quando o bloco não serve para análise de energia (carregando, ou sem contador). */
    val valid: Boolean = false
) {
    val durationMs: Long get() = timestampEnd - timestampStart

    companion object {
        const val UNAVAILABLE_LONG: Long = -1L
        const val UNAVAILABLE_DOUBLE: Double = -1.0
    }
}

/** Append-only, como os demais logs de pesquisa. */
@Dao
interface BlockEnergyDao {

    @Insert
    suspend fun insert(entry: BlockEnergyEntry)

    @Query("SELECT * FROM block_energy ORDER BY timestampStart")
    suspend fun getAll(): List<BlockEnergyEntry>

    @Query("SELECT * FROM block_energy WHERE blockId = :blockId")
    suspend fun getByBlock(blockId: String): List<BlockEnergyEntry>

    @Query("SELECT COUNT(*) FROM block_energy")
    suspend fun count(): Int

    @Query("DELETE FROM block_energy")
    suspend fun clear()
}
