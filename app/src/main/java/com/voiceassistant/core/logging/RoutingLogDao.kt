package com.voiceassistant.core.logging

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO do log de pesquisa de roteamento. Somente inserção e leitura — os registros
 * são imutáveis (append-only) para preservar a integridade dos dados coletados.
 */
@Dao
interface RoutingLogDao {

    @Insert
    suspend fun insert(entry: RoutingLogEntry)

    @Query("SELECT * FROM routing_log ORDER BY timestamp DESC")
    suspend fun getAll(): List<RoutingLogEntry>

    @Query("SELECT * FROM routing_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<RoutingLogEntry>

    @Query("SELECT COUNT(*) FROM routing_log")
    suspend fun count(): Int

    /**
     * Linhas de uma execução da bateria, para apurar a acurácia no fim.
     *
     * Filtra por `sessionId` e não por `blockId` porque o warm-up usa outra sessão
     * (`<label>-warmup`) — incluí-lo misturaria inferências descartáveis na acurácia.
     */
    @Query("SELECT * FROM routing_log WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun getBySession(sessionId: String): List<RoutingLogEntry>

    @Query("DELETE FROM routing_log")
    suspend fun clear()
}
