package com.voiceassistant.domain.usecase

import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case para observar o histórico de uma sessão em tempo real.
 * O ViewModel coleta este Flow e repassa à UI via StateFlow.
 */
class GetChatHistoryUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(sessionId: String): Flow<List<ChatMessage>> =
        chatRepository.getMessages(sessionId)
}
