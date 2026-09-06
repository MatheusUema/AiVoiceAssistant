package com.voiceassistant

import android.content.Context

/**
 * Variante RELEASE: no-op deliberado.
 *
 * Produção exigiria um provedor de atestação real (Play Integrity). Enquanto essa
 * decisão de implantação não for tomada, o tier de nuvem **não funciona em release** —
 * do mesmo modo que o tier servidor não funciona sem HTTPS ou cleartext liberado. As
 * duas pendências são da mesma natureza e estão registradas no `08` §5c.
 */
object AppCheckInitializer {
    fun install(context: Context) = Unit
}
