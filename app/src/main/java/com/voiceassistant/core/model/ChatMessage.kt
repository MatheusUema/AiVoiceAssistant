package com.voiceassistant.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Representa uma única mensagem na conversa entre usuário e assistente.
 * Anotada com @Entity para ser persistida no Room.
 */
@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Indica qual motor gerou esta resposta (apenas para mensagens do assistente) */
    val inferenceSource: InferenceSource? = null,
    /** Quanto tempo (em ms) levou para gerar a resposta */
    val latencyMs: Long? = null
)

enum class MessageRole {
    USER,
    ASSISTANT
}
