package com.voiceassistant

import android.app.Application
import com.voiceassistant.ai_local.manager.LocalModelManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class anotada com @HiltAndroidApp para inicializar o grafo de DI.
 *
 * O [LocalModelManager] é injetado para iniciar o pipeline de carregamento
 * em background assim que o processo é criado. O pipeline executa:
 *   1. Verificação de capacidades do dispositivo (RAM, storage)
 *   2. Cópia do modelo de assets para filesDir (se necessário)
 *   3. Carregamento dos pesos do modelo em memória
 *   4. Warmup opcional (inferência descartável)
 *
 * Se o modelo .bin não estiver em assets/models/, o pipeline falha
 * graciosamente e o InferenceRouter usa apenas a inferência cloud.
 */
@HiltAndroidApp
class VoiceAssistantApp : Application() {

    @Inject
    lateinit var localModelManager: LocalModelManager

    override fun onCreate() {
        super.onCreate()
        // Antes do primeiro uso do tier de nuvem: o Firebase AI Logic exige App Check.
        // A implementacao varia por variante (debug/release) — ver AppCheckInitializer.
        AppCheckInitializer.install(this)
        localModelManager.initializeAsync()
    }
}
