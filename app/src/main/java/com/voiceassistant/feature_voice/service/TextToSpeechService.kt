package com.voiceassistant.feature_voice.service

/**
 * Contrato de domínio para Text-to-Speech.
 * Desacopla o motor TTS do Android da camada de negócio.
 *
 * Todas as operações são seguras para chamar mesmo se o motor não estiver pronto —
 * chamadas prematuras são silenciosamente ignoradas (no-op).
 */
interface TextToSpeechService {

    /**
     * Fala o [text] em voz alta.
     * Suspende até a fala terminar ou ser cancelada.
     *
     * @throws TtsException se o motor falhar durante a fala.
     */
    suspend fun speak(text: String)

    /** Para a fala imediatamente e limpa a fila. */
    fun stop()

    /** Define a velocidade da fala. Clamped para [0.5, 2.0]. */
    fun setSpeechRate(rate: Float)

    /**
     * Define o idioma do TTS a partir de uma tag BCP-47 (ex: "pt-BR").
     * Se o motor não suportar o idioma, mantém o anterior.
     */
    fun setLanguage(languageTag: String)

    /** Libera todos os recursos — chamar no onCleared do ViewModel. */
    fun shutdown()

    /** True se o motor está inicializado e pronto para uso. */
    val isReady: Boolean
}

class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
