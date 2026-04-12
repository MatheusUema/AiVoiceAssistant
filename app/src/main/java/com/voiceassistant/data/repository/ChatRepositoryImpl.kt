package com.voiceassistant.data.repository

import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.storage.ChatMessageDao
import com.voiceassistant.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Implementação concreta de [ChatRepository] usando Room (SQLite).
 * Registrada no módulo Hilt como implementação da interface de domínio.
 */
class ChatRepositoryImpl @Inject constructor(
    private val dao: ChatMessageDao
) : ChatRepository {

    override fun getMessages(sessionId: String): Flow<List<ChatMessage>> =
        dao.getMessagesBySession(sessionId)

    override suspend fun saveMessage(message: ChatMessage) {
        dao.insert(message)
    }

    override suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessage> =
        dao.getRecentMessages(sessionId, limit).reversed() // reversed para ordem cronológica

    override suspend fun clearSession(sessionId: String) {
        dao.clearSession(sessionId)
    }

    override fun getAllSessionIds(): Flow<List<String>> =
        dao.getAllSessionIds()
}
