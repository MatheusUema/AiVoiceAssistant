package com.voiceassistant.domain.usecase

import com.voiceassistant.feature_voice.service.SpeechToTextService
import com.voiceassistant.feature_voice.service.SttResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case que inicia a captura de voz e emite [SttResult].
 * Delega ao [SpeechToTextService] sem expor detalhes de plataforma.
 *
 * O ViewModel coleta o Flow e atualiza o estado da UI conforme cada [SttResult]:
 * - [SttResult.Ready] → ativa indicador de "ouvindo"
 * - [SttResult.Partial] → mostra texto parcial como feedback visual
 * - [SttResult.Final] → preenche o campo de input com o texto definitivo
 * - [SttResult.Error] → exibe mensagem de erro ao usuário
 */
class TranscribeSpeechUseCase @Inject constructor(
    private val speechToTextService: SpeechToTextService
) {
    operator fun invoke(language: String = "pt-BR"): Flow<SttResult> =
        speechToTextService.startListening(language)
}
