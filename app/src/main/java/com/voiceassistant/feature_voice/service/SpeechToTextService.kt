package com.voiceassistant.feature_voice.service

import kotlinx.coroutines.flow.Flow

/**
 * Contrato de domínio para Speech-to-Text.
 *
 * O Flow emite [SttResult] para distinguir resultados parciais de finais,
 * permitindo que a UI mostre feedback em tempo real e saiba quando a
 * transcrição terminou com o texto definitivo.
 */
interface SpeechToTextService {

    /**
     * Inicia a escuta e retorna um Flow de resultados de transcrição.
     *
     * Emissões esperadas (em ordem):
     * 1. [SttResult.Ready] — microfone aberto, aguardando fala
     * 2. [SttResult.Partial] — texto parcial enquanto o usuário fala (pode haver vários)
     * 3. [SttResult.Final] — texto definitivo; o Flow **não fecha** automaticamente
     *    para permitir que o coletor faça cleanup antes de cancelar
     * 4. [SttResult.Error] — falha; o Flow fecha depois do erro
     *
     * O Flow é cold: cada chamada cria uma nova sessão de reconhecimento.
     * Para cancelar, basta cancelar o Job que coleta o Flow (ou chamar [stopListening]).
     */
    fun startListening(language: String = "pt-BR"): Flow<SttResult>

    /** Para a escuta manualmente. Causa o fechamento do Flow. */
    fun stopListening()

    /** True enquanto o microfone está aberto e ouvindo. */
    val isListening: Boolean
}

/**
 * Resultado tipado do STT — cada variante mapeia para um estado da UI.
 */
sealed interface SttResult {
    /** Microfone aberto, aguardando fala. */
    data object Ready : SttResult

    /** Texto parcial enquanto o usuário fala — usado para feedback visual. */
    data class Partial(val text: String) : SttResult

    /** Texto definitivo — transcrição completa. */
    data class Final(val text: String) : SttResult

    /** Falha com mensagem legível para o usuário. */
    data class Error(val message: String) : SttResult
}
