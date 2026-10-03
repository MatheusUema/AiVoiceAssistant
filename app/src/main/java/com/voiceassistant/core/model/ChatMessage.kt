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
    val latencyMs: Long? = null,
    /**
     * Como esta resposta deve ser apresentada — o eixo **vertical** da elasticidade
     * (ver [ResponseMode]). Null em mensagens do usuário, em respostas de tiers que
     * não expõem logprobs, e em builds sem os assets de política: **null não é um modo**,
     * e a UI não mostra bandeira nenhuma nesse caso.
     *
     * Persistido de propósito. A tela lê a conversa do Room, então um campo só em memória
     * faria a bandeira desaparecer ao recarregar a sessão — o que, numa demonstração,
     * pareceria defeito.
     */
    val responseMode: ResponseMode? = null
)

enum class MessageRole {
    USER,
    ASSISTANT
}
