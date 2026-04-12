package com.voiceassistant.feature_voice.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import javax.inject.Inject

/**
 * Implementação de [SpeechToTextService] usando o [SpeechRecognizer] do Android.
 *
 * Restrição da plataforma: o SpeechRecognizer **deve** ser criado e operado na
 * thread principal. Esta classe usa um [Handler(Looper.getMainLooper())] para
 * garantir isso independente de onde o Flow é coletado.
 *
 * O callbackFlow encapsula todos os callbacks do [RecognitionListener] e propaga
 * cada evento como um [SttResult] tipado para o ViewModel/UseCase.
 */
class AndroidSpeechToTextService @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechToTextService {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    override var isListening: Boolean = false
        private set

    override fun startListening(language: String): Flow<SttResult> = callbackFlow {
        // Verifica suporte ao reconhecimento de voz no dispositivo
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SttResult.Error("Reconhecimento de voz indisponível neste dispositivo"))
            close()
            return@callbackFlow
        }

        val listener = object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                trySend(SttResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                // Usuário começou a falar — microfone já está captando
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Nível de volume — pode ser exposto futuramente para animação de onda
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Dados brutos de áudio — não utilizados nesta implementação
            }

            override fun onEndOfSpeech() {
                // Usuário parou de falar — aguarda onResults ou onError
                isListening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    trySend(SttResult.Partial(partial))
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val transcript = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                if (!transcript.isNullOrBlank()) {
                    trySend(SttResult.Final(transcript))
                } else {
                    trySend(SttResult.Error("Nenhum texto reconhecido"))
                }
                // Não fecha o Flow aqui — deixa o ViewModel decidir quando cancelar.
                // Isso evita race conditions entre o último trySend e o close.
            }

            override fun onError(error: Int) {
                isListening = false
                trySend(SttResult.Error(errorMessage(error)))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeFromTag(language))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        // Toda interação com SpeechRecognizer ocorre na thread principal via mainHandler
        mainHandler.post {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { rec ->
                rec.setRecognitionListener(listener)
                rec.startListening(recognizerIntent)
            }
        }

        // Quando o coletor cancela o Flow (ou o ViewModel chama stopListening)
        awaitClose {
            mainHandler.post {
                recognizer?.stopListening()
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
                isListening = false
            }
        }
    }

    override fun stopListening() {
        mainHandler.post {
            recognizer?.stopListening()
        }
        // isListening será atualizado pelo callback onEndOfSpeech/onError
    }

    /** Converte tag BCP-47 ("pt-BR") em [Locale] para o SpeechRecognizer */
    private fun localeFromTag(tag: String): Locale {
        val parts = tag.split("-", "_")
        return when (parts.size) {
            1 -> Locale(parts[0])
            else -> Locale(parts[0], parts[1])
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO ->
            "Erro de gravação de áudio"
        SpeechRecognizer.ERROR_CLIENT ->
            "Erro no cliente de reconhecimento"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Permissão de microfone negada"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Erro de rede durante reconhecimento"
        SpeechRecognizer.ERROR_NO_MATCH ->
            "Fala não reconhecida — tente novamente"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Reconhecedor ocupado — aguarde"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Nenhuma fala detectada"
        SpeechRecognizer.ERROR_SERVER ->
            "Erro no servidor de reconhecimento"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Muitas requisições seguidas — aguarde"
        else ->
            "Erro de reconhecimento ($error)"
    }
}
