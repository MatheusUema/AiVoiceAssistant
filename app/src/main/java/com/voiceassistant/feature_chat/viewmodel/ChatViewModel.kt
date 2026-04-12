package com.voiceassistant.feature_chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceassistant.ai_cloud.service.CloudErrorReason
import com.voiceassistant.ai_cloud.service.CloudInferenceException
import com.voiceassistant.ai_local.service.LocalInferenceException
import com.voiceassistant.ai_local.service.LocalModelNotReadyException
import com.voiceassistant.core.model.TutorMode
import com.voiceassistant.core.network.NetworkMonitor
import com.voiceassistant.domain.repository.SettingsRepository
import com.voiceassistant.domain.usecase.GetChatHistoryUseCase
import com.voiceassistant.domain.usecase.SendMessageUseCase
import com.voiceassistant.domain.usecase.TranscribeSpeechUseCase
import com.voiceassistant.feature_tutor.policy.InferenceUnavailableException
import com.voiceassistant.feature_tutor.policy.PrivacyModeException
import com.voiceassistant.feature_voice.service.SttResult
import com.voiceassistant.feature_voice.service.TextToSpeechService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel da tela de chat.
 *
 * Orquestra os use cases de mensagem, voz (STT/TTS) e settings.
 * Não conhece detalhes de Compose, MediaPipe ou Firebase.
 *
 * Cada operação de longa duração é rastreada em um [Job] para cancelamento explícito.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val getChatHistoryUseCase: GetChatHistoryUseCase,
    private val transcribeSpeechUseCase: TranscribeSpeechUseCase,
    private val textToSpeechService: TextToSpeechService,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(sessionId = UUID.randomUUID().toString())
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var historyJob: Job? = null
    private var listeningJob: Job? = null
    private var currentLanguage: String = "pt-BR"

    init {
        observeChatHistory()
        observeSettings()
        observeNetwork()
    }

    // ── Observadores contínuos ────────────────────────────────────────────

    private fun observeChatHistory() {
        historyJob?.cancel()
        historyJob = getChatHistoryUseCase(_uiState.value.sessionId)
            .onEach { messages -> _uiState.update { it.copy(messages = messages) } }
            .launchIn(viewModelScope)
    }

    private fun observeSettings() {
        settingsRepository.settings
            .onEach { settings ->
                _uiState.update { it.copy(privacyModeEnabled = settings.privacyModeEnabled) }
                currentLanguage = settings.preferredLanguage
                textToSpeechService.setSpeechRate(settings.ttsSpeechRate)
                textToSpeechService.setLanguage(settings.preferredLanguage)
            }
            .launchIn(viewModelScope)
    }

    private fun observeNetwork() {
        networkMonitor.isOnline
            .onEach { online -> _uiState.update { it.copy(isOffline = !online) } }
            .launchIn(viewModelScope)
    }

    // ── Ações do usuário ──────────────────────────────────────────────────

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun onTutorModeSelected(mode: TutorMode) {
        _uiState.update { it.copy(selectedTutorMode = mode) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (!_uiState.value.canSend) return

        val mode = _uiState.value.selectedTutorMode
        _uiState.update { it.copy(inputText = "", isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                sendMessageUseCase(text, _uiState.value.sessionId, mode)
            }.onSuccess { assistantMessage ->
                _uiState.update { it.copy(isLoading = false) }
                speakIfEnabled(assistantMessage.content)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = friendlyErrorMessage(error))
                }
            }
        }
    }

    // ── STT: início, processamento e cancelamento ─────────────────────────

    /**
     * Inicia a captura de voz.
     *
     * O Flow emite [SttResult] que são mapeados para o [ChatUiState]:
     * - [SttResult.Ready] → LISTENING (microfone aberto)
     * - [SttResult.Partial] → atualiza campo de input com texto parcial
     * - [SttResult.Final] → preenche input com texto definitivo, para de ouvir
     * - [SttResult.Error] → exibe erro como Snackbar
     *
     * O [onCompletion] garante que o estado sempre retorne a IDLE
     * independente de como o Flow terminar (sucesso, erro, cancelamento).
     */
    fun startListening() {
        if (_uiState.value.isListening) return

        listeningJob?.cancel()
        _uiState.update {
            it.copy(
                listeningState = ListeningState.STARTING,
                partialTranscript = "",
                errorMessage = null
            )
        }

        listeningJob = transcribeSpeechUseCase(currentLanguage)
            .onEach { result ->
                when (result) {
                    is SttResult.Ready -> {
                        _uiState.update {
                            it.copy(listeningState = ListeningState.LISTENING)
                        }
                    }
                    is SttResult.Partial -> {
                        _uiState.update {
                            it.copy(
                                partialTranscript = result.text,
                                inputText = result.text
                            )
                        }
                    }
                    is SttResult.Final -> {
                        _uiState.update {
                            it.copy(
                                inputText = result.text,
                                partialTranscript = "",
                                listeningState = ListeningState.IDLE
                            )
                        }
                        // Cancela o Flow — transcrição completa
                        listeningJob?.cancel()
                        listeningJob = null
                    }
                    is SttResult.Error -> {
                        _uiState.update {
                            it.copy(
                                listeningState = ListeningState.IDLE,
                                partialTranscript = "",
                                errorMessage = result.message
                            )
                        }
                    }
                }
            }
            .onCompletion {
                // Rede de segurança: garante IDLE se o Flow terminar por qualquer motivo
                _uiState.update {
                    if (it.listeningState != ListeningState.IDLE) {
                        it.copy(listeningState = ListeningState.IDLE, partialTranscript = "")
                    } else it
                }
            }
            .launchIn(viewModelScope)
    }

    /** Para a captura de voz manualmente (botão de stop). */
    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        // onCompletion cuidará de zerar o listeningState
    }

    // ── TTS ───────────────────────────────────────────────────────────────

    fun stopSpeaking() {
        textToSpeechService.stop()
        _uiState.update { it.copy(isSpeaking = false) }
    }

    private fun speakIfEnabled(text: String) {
        viewModelScope.launch {
            val ttsEnabled = settingsRepository.settings.first().ttsEnabled
            if (!ttsEnabled) return@launch
            _uiState.update { it.copy(isSpeaking = true) }
            runCatching { textToSpeechService.speak(text) }
            _uiState.update { it.copy(isSpeaking = false) }
        }
    }

    // ── Erros ──────────────────────────────────────────────────────────────

    /**
     * Converte exceções tipadas do [InferenceRouter] e [CloudInferenceService]
     * em mensagens legíveis para exibição na UI (Snackbar).
     */
    private fun friendlyErrorMessage(error: Throwable): String = when (error) {
        is CloudInferenceException -> when (error.reason) {
            CloudErrorReason.NETWORK ->
                "Sem conexão com a internet. Verifique sua rede."
            CloudErrorReason.SAFETY_BLOCKED ->
                "Resposta bloqueada pelo filtro de segurança. Tente reformular a pergunta."
            CloudErrorReason.EMPTY_RESPONSE ->
                "O modelo não gerou uma resposta. Tente novamente."
            CloudErrorReason.AUTH_ERROR ->
                "Firebase não configurado. Veja as instruções no README."
            CloudErrorReason.QUOTA_EXCEEDED ->
                "Limite de uso da API atingido. Tente novamente mais tarde."
            CloudErrorReason.UNKNOWN ->
                "Erro na inferência cloud. Tente novamente."
        }
        is LocalModelNotReadyException ->
            "Modelo local não carregado. Verifique se o arquivo está em assets/models/."
        is LocalInferenceException ->
            "Erro no modelo local: ${error.message}"
        is PrivacyModeException ->
            "Modo privacidade ativo e modelo local indisponível."
        is InferenceUnavailableException ->
            error.message ?: "Nenhum serviço de IA disponível."
        else ->
            error.message ?: "Erro inesperado."
    }

    // ── Gerais ────────────────────────────────────────────────────────────

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun startNewSession() {
        stopListening()
        stopSpeaking()
        _uiState.update { ChatUiState(sessionId = UUID.randomUUID().toString()) }
        observeChatHistory()
    }

    override fun onCleared() {
        super.onCleared()
        textToSpeechService.shutdown()
    }
}
