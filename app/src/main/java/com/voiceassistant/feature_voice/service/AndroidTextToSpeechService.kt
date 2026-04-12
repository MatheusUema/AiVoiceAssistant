package com.voiceassistant.feature_voice.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementação de [TextToSpeechService] usando o [TextToSpeech] do Android.
 *
 * O motor é inicializado no construtor. Se a inicialização falhar (ex: motor TTS
 * não instalado), [isReady] permanece false e [speak] retorna silenciosamente.
 *
 * A fala é suspensa via [suspendCancellableCoroutine], permitindo que a coroutine
 * continue somente quando a utterance terminar, sem bloquear a main thread.
 */
@Singleton
class AndroidTextToSpeechService @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeechService {

    private var tts: TextToSpeech? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    private var currentLocale: Locale = Locale("pt", "BR")
    private var currentSpeechRate: Float = 1.0f

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                applyLocale(engine, currentLocale)
                engine.setSpeechRate(currentSpeechRate)
                isReady = true
            } else {
                Log.w(TAG, "TTS init falhou com status=$status")
                isReady = false
            }
        }
    }

    override suspend fun speak(text: String) {
        val engine = tts
        if (!isReady || engine == null || text.isBlank()) return

        suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) {
                        cont.resume(Unit)
                    }
                }

                // Overload moderno (API 21+) — recebe o error code
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) {
                        cont.resumeWithException(
                            TtsException("TTS erro $errorCode para utterance $id")
                        )
                    }
                }

                // Overload legado — mantido para dispositivos antigos
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) {
                        cont.resumeWithException(
                            TtsException("TTS erro desconhecido para utterance $id")
                        )
                    }
                }
            })

            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR && cont.isActive) {
                cont.resumeWithException(TtsException("TTS speak() retornou ERROR"))
            }

            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(currentSpeechRate)
    }

    override fun setLanguage(languageTag: String) {
        val locale = localeFromTag(languageTag)
        currentLocale = locale
        tts?.let { engine ->
            if (isReady) applyLocale(engine, locale)
        }
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    /** Aplica o idioma no motor, validando se é suportado */
    private fun applyLocale(engine: TextToSpeech, locale: Locale) {
        val availability = engine.isLanguageAvailable(locale)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        } else {
            Log.w(TAG, "Idioma $locale não suportado pelo motor TTS (availability=$availability)")
        }
    }

    /** Converte tag BCP-47 ("pt-BR") em [Locale] */
    private fun localeFromTag(tag: String): Locale {
        val parts = tag.split("-", "_")
        return when (parts.size) {
            1 -> Locale(parts[0])
            else -> Locale(parts[0], parts[1])
        }
    }

    companion object {
        private const val TAG = "AndroidTTS"
    }
}
