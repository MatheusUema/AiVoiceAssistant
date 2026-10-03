package com.voiceassistant.feature_chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.MessageRole
import com.voiceassistant.core.model.ResponseMode
import com.voiceassistant.core.model.TutorMode
import com.voiceassistant.feature_chat.viewmodel.ChatUiState
import com.voiceassistant.feature_chat.viewmodel.ChatViewModel
import com.voiceassistant.feature_chat.viewmodel.ListeningState
import com.voiceassistant.ui.theme.VoiceAssistantTheme

// ═══════════════════════════════════════════════════════════════════════════
// Ponto de entrada com ViewModel
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ChatContent(
        uiState = uiState,
        onInputChanged = viewModel::onInputTextChanged,
        onSendClick = viewModel::sendMessage,
        onMicClick = {
            if (uiState.isListening) viewModel.stopListening()
            else viewModel.startListening()
        },
        onNewSession = viewModel::startNewSession,
        onDismissError = viewModel::dismissError,
        onStopSpeaking = viewModel::stopSpeaking,
        onTutorModeSelected = viewModel::onTutorModeSelected
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// Conteúdo sem estado — testável e previewável
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatContent(
    uiState: ChatUiState,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onNewSession: () -> Unit,
    onDismissError: () -> Unit,
    onStopSpeaking: () -> Unit,
    onTutorModeSelected: (TutorMode) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onDismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Tutor IA", style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = onNewSession) {
                            Icon(Icons.Default.Add, "Nova conversa")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                ModeStatusBar(
                    isPrivacyMode = uiState.privacyModeEnabled,
                    isOffline = uiState.isOffline,
                    listeningState = uiState.listeningState,
                    isSpeaking = uiState.isSpeaking
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                TutorModeSelector(
                    selectedMode = uiState.selectedTutorMode,
                    onModeSelected = onTutorModeSelected
                )
                ChatInputBar(
                    inputText = uiState.inputText,
                    listeningState = uiState.listeningState,
                    isLoading = uiState.isLoading,
                    partialTranscript = uiState.partialTranscript,
                    onTextChanged = onInputChanged,
                    onSendClick = onSendClick,
                    onMicClick = onMicClick
                )
                // O aviso GERAL sobre resposta de IA mora aqui, uma vez, e nunca no balão:
                // um aviso que aparece sempre não carrega informação e competiria com a
                // ressalva por mensagem. Ver o KDoc de ResponseMode.
                AvisoGeralIa()
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.messages.isEmpty() && !uiState.isLoading) {
                EmptyConversationPlaceholder(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        ChatMessageBubble(message = message)
                    }
                    if (uiState.isLoading) {
                        item(key = "typing") {
                            TypingIndicator(Modifier.fillMaxWidth(0.25f))
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Previews
// ═══════════════════════════════════════════════════════════════════════════

private fun previewMessages() = listOf(
    ChatMessage(sessionId = "p", role = MessageRole.USER,
        content = "Qual é a diferença entre DNA e RNA?"),
    ChatMessage(sessionId = "p", role = MessageRole.ASSISTANT,
        content = "O DNA armazena informação genética em dupla-hélice. O RNA é uma cópia temporária usada para fabricar proteínas.",
        inferenceSource = InferenceSource.LOCAL),
    ChatMessage(sessionId = "p", role = MessageRole.USER,
        content = "E como ocorre a transcrição?"),
    ChatMessage(sessionId = "p", role = MessageRole.ASSISTANT,
        content = "A RNA polimerase lê o DNA e produz uma fita de mRNA complementar.",
        inferenceSource = InferenceSource.CLOUD)
)

@Preview(showBackground = true, name = "Chat — Messages", showSystemUi = true)
@Composable
private fun ChatMessagesPreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = ChatUiState(sessionId = "p", messages = previewMessages()),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}

@Preview(showBackground = true, name = "Chat — Empty", showSystemUi = true)
@Composable
private fun ChatEmptyPreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = ChatUiState(sessionId = "p"),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}

@Preview(showBackground = true, name = "Chat — Hint Mode + Listening", showSystemUi = true)
@Composable
private fun ChatHintModePreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = ChatUiState(
                sessionId = "p",
                messages = previewMessages().take(2),
                listeningState = ListeningState.LISTENING,
                privacyModeEnabled = true,
                selectedTutorMode = TutorMode.HINT,
                partialTranscript = "Como ocorre a respiração celular…",
                inputText = "Como ocorre a respiração celular…"
            ),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}

// ───────────────────────────────────────────────────────────────────────────
// Bloco D parte 1 — os três modos no cenário Unplugged
//
// ATENÇÃO A QUEM FOTOGRAFAR: estes três previews são ESTADO FORÇADO. Eles
// demonstram a INTERFACE, não o comportamento do roteador — a faixa vem de um
// `responseMode` escrito à mão aqui, não de um score de cascata. A §16 exige
// que a legenda de cada imagem diga isso. Um print de estado forçado
// apresentado como comportamento do sistema seria exatamente o tipo de
// medição que engana de que a §15 fala.
//
// O cenário é o Unplugged: `isOffline = true` e `privacyModeEnabled = true`,
// isto é, sem internet e sem servidor — só o aparelho. É nele que o eixo
// vertical é a única elasticidade disponível.
// ───────────────────────────────────────────────────────────────────────────

private const val PERGUNTA_D1 = "Por que o céu é azul?"

private fun mensagensD1(modo: ResponseMode, resposta: String) = listOf(
    ChatMessage(sessionId = "d1", role = MessageRole.USER, content = PERGUNTA_D1),
    ChatMessage(
        sessionId = "d1", role = MessageRole.ASSISTANT,
        content = resposta,
        inferenceSource = InferenceSource.LOCAL,
        latencyMs = 24_000,
        responseMode = modo
    )
)

private fun uiStateUnplugged(modo: ResponseMode, resposta: String) = ChatUiState(
    sessionId = "d1",
    messages = mensagensD1(modo, resposta),
    isOffline = true,
    privacyModeEnabled = true
)

@Preview(showBackground = true, name = "D1 — 1. Responder direto", showSystemUi = true)
@Composable
private fun ChatModoDiretoPreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = uiStateUnplugged(
                ResponseMode.DIRETO,
                "A luz do Sol se espalha ao atravessar a atmosfera, e o azul se " +
                    "espalha mais que as outras cores porque tem onda mais curta."
            ),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}

@Preview(showBackground = true, name = "D1 — 2. Responder com ressalva", showSystemUi = true)
@Composable
private fun ChatModoRessalvaPreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = uiStateUnplugged(
                ResponseMode.RESSALVA,
                "O azul aparece porque a atmosfera espalha a luz, e creio que as " +
                    "cores de onda mais curta se espalham mais."
            ),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}

@Preview(showBackground = true, name = "D1 — 3. Mediar ao professor", showSystemUi = true)
@Composable
private fun ChatModoMediarPreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = uiStateUnplugged(
                ResponseMode.MEDIAR,
                "O céu é azul por causa do reflexo da água dos oceanos na atmosfera."
            ),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}

@Preview(showBackground = true, name = "Chat — Summary Mode + Offline", showSystemUi = true)
@Composable
private fun ChatSummaryOfflinePreview() {
    VoiceAssistantTheme {
        ChatContent(
            uiState = ChatUiState(
                sessionId = "p",
                messages = previewMessages(),
                isLoading = true,
                isOffline = true,
                selectedTutorMode = TutorMode.SUMMARY
            ),
            onInputChanged = {}, onSendClick = {}, onMicClick = {},
            onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
            onTutorModeSelected = {}
        )
    }
}
