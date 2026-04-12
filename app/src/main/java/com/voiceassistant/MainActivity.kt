package com.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.voiceassistant.feature_chat.ui.ChatScreen
import com.voiceassistant.ui.theme.VoiceAssistantTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Único ponto de entrada do app.
 *
 * Responsabilidades intencionalmente mínimas:
 *  - Instalar splash screen
 *  - Solicitar permissão de microfone (resultado tratado no serviço STT)
 *  - Configurar tema e entregar controle ao Compose
 *
 * TODO (Fase 4): Substituir ChatScreen() por NavHost quando houver múltiplas telas.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Permissão concedida ou negada.
            // O AndroidSpeechToTextService emite Resource.Error se negada —
            // a UI exibe o erro via Snackbar sem travar.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestMicPermissionIfNeeded()

        setContent {
            VoiceAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen()
                }
            }
        }
    }

    private fun requestMicPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
