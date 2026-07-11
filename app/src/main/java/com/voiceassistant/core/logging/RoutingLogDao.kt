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

    @Query("DELETE FROM routing_log")
    suspend fun clear()
}
