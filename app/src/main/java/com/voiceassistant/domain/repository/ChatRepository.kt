package com.voiceassistant.domain.repository

import com.voiceassistant.core.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Contrato da camada de domínio para persistência do histórico de chat.
 * A implementação concreta vive na camada de dados (data layer) e é injetada via Hilt.
 * A UI e os use cases dependem apenas desta interface — nunca da implementação.
 */
interface ChatRepository {

    /** Stream reativo de mensagens de uma sessão, ordenadas por timestamp. */
    fun getMessages(sessionId: String): Flow<List<ChatMessage>>

    /** Salva uma nova mensagem no histórico. */
    suspend fun saveMessage(message: ChatMessage)

    /** Retorna as últimas [limit] mensagens para construção de contexto. */
    suspend fun getRecentMessages(sessionId: String, limit: Int = 10): List<ChatMessage>

    /** Remove todo o histórico de uma sessão específica. */
    suspend fun clearSession(sessionId: String)

    /** Lista todos os IDs de sessão existentes. */
    fun getAllSessionIds(): Flow<List<String>>
}
