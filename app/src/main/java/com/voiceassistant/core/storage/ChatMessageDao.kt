package com.voiceassistant.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voiceassistant.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operações de leitura e escrita do histórico de mensagens.
 */
@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage)

    /**
     * Retorna todas as mensagens de uma sessão, ordenadas por timestamp.
     * Emite novamente sempre que o banco for modificado (Flow reativo).
     */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<ChatMessage>>

    /** Últimas N mensagens de uma sessão — útil para construir contexto de conversa. */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE sessionId = :sessionId 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

    /** IDs distintos de sessões para exibir histórico de conversas anteriores. */
    @Query("SELECT DISTINCT sessionId FROM chat_messages ORDER BY timestamp DESC")
    fun getAllSessionIds(): Flow<List<String>>
}
