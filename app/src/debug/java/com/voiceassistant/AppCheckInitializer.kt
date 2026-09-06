package com.voiceassistant

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.appCheck
import com.google.firebase.initialize

/**
 * Variante DEBUG: instala o provedor de App Check de depuração.
 *
 * POR QUE ISTO EXISTE. O Firebase AI Logic passou a exigir **App Check imposto**; sem
 * ele a API recusa a chamada com "Firebase AI Logic has been deactivated in this
 * project", mesmo com projeto válido e chave correta. Não é erro de credencial — é
 * política antiabuso do produto.
 *
 * O provedor de depuração emite um segredo no logcat na primeira execução, que precisa
 * ser cadastrado na allow-list do console. Isso vale só para builds de depuração e para
 * o aparelho onde o segredo foi gerado.
 *
 * Em RELEASE este arquivo é substituído pela variante em `src/release`, que não instala
 * nada — produção exigiria Play Integrity, decisão de implantação ainda em aberto (a
 * mesma família de pendências do `cleartextTrafficPermitted`).
 */
object AppCheckInitializer {
    fun install(context: Context) {
        Firebase.initialize(context)
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        Log.i("AppCheck", "provedor de DEPURACAO instalado; procure o debug secret no logcat")
    }
}
