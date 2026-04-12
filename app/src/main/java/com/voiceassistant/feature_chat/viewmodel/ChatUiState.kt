package com.voiceassistant.feature_chat.viewmodel

import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.model.TutorMode

/**
 * Estado imutável da tela de chat.
 * Cada mudança no ViewModel emite uma nova cópia via StateFlow.
 */
data class ChatUiState(
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",

    /** Texto parcial transcrito pelo STT em tempo real */
    val partialTranscript: String = "",

    val isLoading: Boolean = false,
    val isSpeaking: Boolean = false,
    val isOffline: Boolean = false,
    val privacyModeEnabled: Boolean = false,
    val errorMessage: String? = null,

    /** Modo pedagógico ativo — determina o estilo da resposta do tutor */
    val selectedTutorMode: TutorMode = TutorMode.EXPLAIN,

    /**
     * Sub-estados do reconhecimento de voz:
     *  IDLE → não está ouvindo
     *  STARTING → callbackFlow criado, aguardando onReadyForSpeech
     *  LISTENING → microfone aberto, captando fala
     *  PROCESSING → fala encerrada, aguardando resultado final do motor
     */
    val listeningState: ListeningState = ListeningState.IDLE
) {
    val isListening: Boolean
        get() = listeningState != ListeningState.IDLE

    val canSend: Boolean
        get() = inputText.isNotBlank() && !isLoading
}

enum class ListeningState {
    IDLE,
    STARTING,
    LISTENING,
    PROCESSING
}
