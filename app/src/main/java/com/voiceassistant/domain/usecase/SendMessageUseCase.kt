package com.voiceassistant.domain.usecase

import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.model.InferenceRequest
import com.voiceassistant.core.model.MessageRole
import com.voiceassistant.core.model.TutorMode
import com.voiceassistant.domain.repository.ChatRepository
import com.voiceassistant.domain.repository.InferenceRepository
import com.voiceassistant.feature_tutor.policy.PromptComplexityAnalyzer
import javax.inject.Inject

/**
 * Use case principal do fluxo de chat.
 * Orquestra: salvar mensagem do usuário → inferir resposta → salvar resposta do assistente.
 *
 * Não conhece detalhes de MediaPipe, Firebase ou Android Speech APIs.
 */
class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val inferenceRepository: InferenceRepository,
    private val complexityAnalyzer: PromptComplexityAnalyzer
) {
    /**
     * @param userText Texto digitado ou transcrito pelo usuário
     * @param sessionId Identificador da sessão atual
     * @param tutorMode Modo pedagógico selecionado na UI
     * @return A mensagem do assistente salva no banco
     */
    suspend operator fun invoke(
        userText: String,
        sessionId: String,
        tutorMode: TutorMode = TutorMode.EXPLAIN
    ): ChatMessage {
        // Persiste a mensagem do usuário imediatamente para exibição responsiva na UI
        val userMessage = ChatMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = userText
        )
        chatRepository.saveMessage(userMessage)

        // Recupera contexto recente para fornecer ao modelo
        val history = chatRepository.getRecentMessages(sessionId, limit = 10)

        // Avalia complexidade para orientar o InferenceRouter
        val complexity = complexityAnalyzer.analyze(userText)

        val request = InferenceRequest(
            prompt = userText,
            sessionId = sessionId,
            conversationHistory = history,
            complexity = complexity,
            tutorMode = tutorMode
        )

        val result = inferenceRepository.infer(request)

        val assistantMessage = ChatMessage(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = result.text,
            inferenceSource = result.source,
            latencyMs = result.latencyMs
        )
        chatRepository.saveMessage(assistantMessage)

        return assistantMessage
    }
}
