package com.voiceassistant.core.logging

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Uma tentativa de carregar um modelo local (uma linha por carregamento).
 *
 * Cobre H6 (tempo de carga + warmup) e H7 (tamanho em disco) do plano de hardware.
 *
 * Registra **inclusive as tentativas que falharam**: no estudo, "este modelo não coube
 * neste aparelho" é o resultado, não um erro a descartar. É essa tabela que sustenta a
 * afirmação sobre o limite de elasticidade de cada aparelho.
 */
@Entity(tableName = "model_load_log")
data class ModelLoadLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),

    /** FK para `device_profile`. */
    val deviceId: String,

    /** Rótulo estável do modelo, ex.: "gemma-4-e2b-it-q4_k_m". */
    val modelId: String,

    /** Tamanho do arquivo GGUF em disco (H7). 0 se o arquivo nem foi encontrado. */
    val modelSizeBytes: Long,

    /** H6: tempo de carga em ms. Também é medido quando a carga **falha**. */
    val loadMs: Long,

    /** H6: tempo do warmup em ms. 0 quando não houve. */
    val warmupMs: Long,

    /**
     * Desfecho: "SUCCESS" | "MISSING_FILE" | "BLOCKED_BY_DEVICE" | "FAILED" |
     * "KILLED_PREVIOUS_ATTEMPT" (processo morto pelo OOM killer na tentativa anterior).
     */
    val outcome: String,

    /** Motivo legível quando não foi SUCCESS. */
    val reason: String? = null,

    /** Motor: "llamacpp" | "mediapipe". */
    val runtime: String,

    /** ABI nativa do aparelho, ex.: "arm64-v8a". */
    val abi: String,

    /** Threads de inferência configuradas. -1 se a carga falhou antes disso. */
    val threads: Int = -1,

    /** Janela de contexto efetiva (KV-cache). -1 se a carga falhou. */
    val contextSize: Int = -1,

    /** Backends ggml ativos ("CPU", "Vulkan,CPU"). */
    val backends: String? = null,

    /** True se o build tinha Vulkan habilitado. */
    val vulkanEnabled: Boolean = false,

    /** RAM total e disponível no momento da tentativa (MB) — contexto da falha. */
    val totalRamMb: Long = -1,
    val availableRamMb: Long = -1
)

/** Append-only: registros de pesquisa não são editados. */
@Dao
interface ModelLoadLogDao {

    @Insert
    suspend fun insert(entry: ModelLoadLogEntry)

    @Query("SELECT * FROM model_load_log ORDER BY timestamp DESC")
    suspend fun getAll(): List<ModelLoadLogEntry>

    @Query("SELECT COUNT(*) FROM model_load_log")
    suspend fun count(): Int

    @Query("DELETE FROM model_load_log")
    suspend fun clear()
}
