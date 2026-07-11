package com.voiceassistant.feature_chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.MessageRole
import com.voiceassistant.core.model.TutorMode
import com.voiceassistant.feature_chat.viewmodel.ListeningState
import com.voiceassistant.ui.theme.VoiceAssistantTheme

// ═══════════════════════════════════════════════════════════════════════════
// ChatMessageBubble
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = bubbleShape(isUser),
                color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 1.dp
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            if (!isUser && message.inferenceSource != null) {
                Spacer(modifier = Modifier.height(4.dp))
                InferenceSourceBadge(source = message.inferenceSource)
            }
        }
    }
}

private fun bubbleShape(isUser: Boolean) = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = if (isUser) 18.dp else 4.dp,
    bottomEnd = if (isUser) 4.dp else 18.dp
)

private class MessageProvider : PreviewParameterProvider<ChatMessage> {
    override val values = sequenceOf(
        ChatMessage(sessionId = "p", role = MessageRole.USER,
            content = "Qual é a capital do Brasil?"),
        ChatMessage(sessionId = "p", role = MessageRole.ASSISTANT,
            content = "A capital do Brasil é Brasília.",
            inferenceSource = InferenceSource.LOCAL),
        ChatMessage(sessionId = "p", role = MessageRole.ASSISTANT,
            content = "Resposta via Firebase AI (Gemini).",
            inferenceSource = InferenceSource.CLOUD),
        ChatMessage(sessionId = "p", role = MessageRole.ASSISTANT,
            content = "Modelo local falhou. Usando nuvem.",
            inferenceSource = InferenceSource.FALLBACK)
    )
}

@Preview(showBackground = true, name = "Message Bubbles")
@Composable
private fun ChatMessageBubblePreview(
    @PreviewParameter(MessageProvider::class) message: ChatMessage
) {
    VoiceAssistantTheme {
        Box(modifier = Modifier.padding(12.dp)) { ChatMessageBubble(message = message) }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// InferenceSourceBadge
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun InferenceSourceBadge(
    source: InferenceSource,
    modifier: Modifier = Modifier
) {
    val style = when (source) {
        InferenceSource.LOCAL -> BadgeStyle("LOCAL", Icons.Default.Home,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer)
        InferenceSource.SERVER -> BadgeStyle("SERVER", Icons.Default.Dns,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer)
        InferenceSource.CLOUD -> BadgeStyle("CLOUD", Icons.Default.Cloud,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer)
        InferenceSource.FALLBACK -> BadgeStyle("FALLBACK", Icons.Default.Warning,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer)
    }

    Row(
        modifier = modifier
            .background(style.container, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(style.icon, null, tint = style.content, modifier = Modifier.size(10.dp))
        Text(style.label, style = MaterialTheme.typography.labelSmall, color = style.content)
    }
}

private data class BadgeStyle(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color
)

@Preview(showBackground = true, name = "Inference Badges")
@Composable
private fun InferenceSourceBadgePreview() {
    VoiceAssistantTheme {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InferenceSourceBadge(InferenceSource.LOCAL)
            InferenceSourceBadge(InferenceSource.CLOUD)
            InferenceSourceBadge(InferenceSource.FALLBACK)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TypingIndicator
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val dotScale by transition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(450, delayMillis = index * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_scale_$index"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .scale(dotScale)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer, CircleShape)
            )
        }
    }
}

@Preview(showBackground = true, name = "Typing Indicator")
@Composable
private fun TypingIndicatorPreview() {
    VoiceAssistantTheme { Box(Modifier.padding(16.dp)) { TypingIndicator() } }
}

// ═══════════════════════════════════════════════════════════════════════════
// MicButton
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Botão de microfone com três estados visuais:
 * - [ListeningState.IDLE] → ícone de microfone, fundo neutro
 * - [ListeningState.STARTING] → spinner circular (aguardando motor)
 * - [ListeningState.LISTENING] → ícone stop + anel pulsante vermelho
 * - [ListeningState.PROCESSING] → ícone hourglass (processando resultado)
 */
@Composable
fun MicButton(
    listeningState: ListeningState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        // Anel pulsante quando ouvindo ativamente
        if (listeningState == ListeningState.LISTENING) {
            val transition = rememberInfiniteTransition(label = "mic_pulse")
            val pulseScale by transition.animateFloat(
                initialValue = 1f, targetValue = 1.5f,
                animationSpec = infiniteRepeatable(
                    tween(700, easing = LinearEasing), RepeatMode.Reverse
                ),
                label = "pulse_scale"
            )
            val pulseAlpha by transition.animateFloat(
                initialValue = 0.35f, targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    tween(700, easing = LinearEasing), RepeatMode.Reverse
                ),
                label = "pulse_alpha"
            )
            Box(
                Modifier
                    .size(48.dp)
                    .scale(pulseScale)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha), CircleShape)
            )
        }

        when (listeningState) {
            // Spinner enquanto aguarda o SpeechRecognizer inicializar
            ListeningState.STARTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Microfone ou Stop/Hourglass para os demais estados
            else -> {
                val isActive = listeningState != ListeningState.IDLE
                FilledIconButton(
                    onClick = onClick,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isActive)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isActive)
                            MaterialTheme.colorScheme.onError
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = when (listeningState) {
                            ListeningState.LISTENING -> Icons.Default.Stop
                            ListeningState.PROCESSING -> Icons.Default.HourglassTop
                            else -> Icons.Default.Mic
                        },
                        contentDescription = when (listeningState) {
                            ListeningState.LISTENING -> "Parar gravação"
                            ListeningState.PROCESSING -> "Processando fala"
                            else -> "Iniciar gravação"
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Mic — Idle")
@Composable
private fun MicIdlePreview() {
    VoiceAssistantTheme {
        Box(Modifier.padding(24.dp)) {
            MicButton(ListeningState.IDLE, true, {})
        }
    }
}

@Preview(showBackground = true, name = "Mic — Starting")
@Composable
private fun MicStartingPreview() {
    VoiceAssistantTheme {
        Box(Modifier.padding(24.dp)) {
            MicButton(ListeningState.STARTING, true, {})
        }
    }
}

@Preview(showBackground = true, name = "Mic — Listening")
@Composable
private fun MicListeningPreview() {
    VoiceAssistantTheme {
        Box(Modifier.padding(24.dp)) {
            MicButton(ListeningState.LISTENING, true, {})
        }
    }
}

@Preview(showBackground = true, name = "Mic — Processing")
@Composable
private fun MicProcessingPreview() {
    VoiceAssistantTheme {
        Box(Modifier.padding(24.dp)) {
            MicButton(ListeningState.PROCESSING, true, {})
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ModeStatusBar
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Faixa contextual exibida abaixo da TopAppBar quando há modos ativos.
 * Inclui indicadores de Privacidade, Offline, Escuta ativa e TTS.
 */
@Composable
fun ModeStatusBar(
    isPrivacyMode: Boolean,
    isOffline: Boolean,
    listeningState: ListeningState,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    val isListeningActive = listeningState != ListeningState.IDLE
    val hasContent = isPrivacyMode || isOffline || isListeningActive || isSpeaking

    AnimatedVisibility(
        visible = hasContent,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPrivacyMode) {
                StatusPill(Icons.Default.Lock, "Privacidade", MaterialTheme.colorScheme.tertiary)
            }
            if (isOffline) {
                StatusPill(Icons.Default.WifiOff, "Offline", MaterialTheme.colorScheme.error)
            }
            if (isListeningActive) {
                val label = when (listeningState) {
                    ListeningState.STARTING -> "Iniciando…"
                    ListeningState.LISTENING -> "Ouvindo…"
                    ListeningState.PROCESSING -> "Processando…"
                    else -> ""
                }
                StatusPill(Icons.Default.GraphicEq, label, MaterialTheme.colorScheme.error)
            }
            if (isSpeaking) {
                StatusPill(Icons.Default.VolumeUp, "Falando…", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StatusPill(icon: ImageVector, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Preview(showBackground = true, name = "Status Bar — Listening + Privacy")
@Composable
private fun StatusBarListeningPreview() {
    VoiceAssistantTheme {
        ModeStatusBar(true, false, ListeningState.LISTENING, false)
    }
}

@Preview(showBackground = true, name = "Status Bar — All Active")
@Composable
private fun StatusBarAllPreview() {
    VoiceAssistantTheme {
        ModeStatusBar(true, true, ListeningState.LISTENING, true)
    }
}

@Preview(showBackground = true, name = "Status Bar — Hidden")
@Composable
private fun StatusBarHiddenPreview() {
    VoiceAssistantTheme {
        ModeStatusBar(false, false, ListeningState.IDLE, false)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ChatInputBar
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Barra de entrada na parte inferior.
 * Campo de texto + [MicButton] + botão de envio.
 *
 * O campo de texto é desabilitado enquanto ouvindo ativamente para evitar
 * conflito entre a digitação manual e a transcrição do STT.
 */
@Composable
fun ChatInputBar(
    inputText: String,
    listeningState: ListeningState,
    isLoading: Boolean,
    partialTranscript: String,
    onTextChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isListening = listeningState != ListeningState.IDLE

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // Texto parcial transcrito em tempo real
            AnimatedVisibility(visible = isListening && partialTranscript.isNotBlank()) {
                Text(
                    text = "🎤 $partialTranscript",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onTextChanged,
                    placeholder = {
                        Text(
                            if (isListening) "Ouvindo…"
                            else "Digite ou fale uma pergunta…"
                        )
                    },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                    enabled = !isLoading && !isListening,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                )

                MicButton(
                    listeningState = listeningState,
                    enabled = !isLoading,
                    onClick = onMicClick
                )

                FilledIconButton(
                    onClick = onSendClick,
                    enabled = inputText.isNotBlank() && !isLoading && !isListening,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Input Bar — Empty")
@Composable
private fun InputBarEmptyPreview() {
    VoiceAssistantTheme {
        ChatInputBar("", ListeningState.IDLE, false, "", {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Input Bar — With Text")
@Composable
private fun InputBarTextPreview() {
    VoiceAssistantTheme {
        ChatInputBar("Explique fotossíntese", ListeningState.IDLE, false, "", {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Input Bar — Listening")
@Composable
private fun InputBarListeningPreview() {
    VoiceAssistantTheme {
        ChatInputBar(
            "Como funciona a clorofila",
            ListeningState.LISTENING,
            false,
            "Como funciona a clorofila",
            {}, {}, {}
        )
    }
}

@Preview(showBackground = true, name = "Input Bar — Starting")
@Composable
private fun InputBarStartingPreview() {
    VoiceAssistantTheme {
        ChatInputBar("", ListeningState.STARTING, false, "", {}, {}, {})
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TutorModeSelector
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Barra horizontal de chips para seleção do modo pedagógico.
 * Scrollável para acomodar todos os modos sem truncar.
 */
@Composable
fun TutorModeSelector(
    selectedMode: TutorMode,
    onModeSelected: (TutorMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TutorMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            FilterChip(
                selected = isSelected,
                onClick = { onModeSelected(mode) },
                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = mode.iconVector,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

/**
 * Mapeia [TutorMode] para Material Icons.
 * Separado do enum para não criar dependência de Compose no core/model.
 */
val TutorMode.iconVector: ImageVector
    get() = when (this) {
        TutorMode.EXPLAIN -> Icons.Default.MenuBook
        TutorMode.HINT -> Icons.Default.Lightbulb
        TutorMode.SUMMARY -> Icons.Default.Summarize
        TutorMode.REVIEW -> Icons.Default.RateReview
    }

@Preview(showBackground = true, name = "TutorMode — Explain selected")
@Composable
private fun TutorModeExplainPreview() {
    VoiceAssistantTheme {
        TutorModeSelector(selectedMode = TutorMode.EXPLAIN, onModeSelected = {})
    }
}

@Preview(showBackground = true, name = "TutorMode — Hint selected")
@Composable
private fun TutorModeHintPreview() {
    VoiceAssistantTheme {
        TutorModeSelector(selectedMode = TutorMode.HINT, onModeSelected = {})
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EmptyConversationPlaceholder
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun EmptyConversationPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "🎓", style = MaterialTheme.typography.displayLarge)
        Text(
            text = "Como posso ajudar você?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Digite uma pergunta ou toque no microfone para falar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Preview(showBackground = true, name = "Empty Placeholder")
@Composable
private fun EmptyPlaceholderPreview() {
    VoiceAssistantTheme {
        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), Alignment.Center) {
            EmptyConversationPlaceholder()
        }
    }
}
