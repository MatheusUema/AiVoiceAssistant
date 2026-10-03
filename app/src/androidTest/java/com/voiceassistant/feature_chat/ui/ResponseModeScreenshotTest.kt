package com.voiceassistant.feature_chat.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.model.InferenceSource
import com.voiceassistant.core.model.MessageRole
import com.voiceassistant.core.model.ResponseMode
import com.voiceassistant.feature_chat.viewmodel.ChatUiState
import com.voiceassistant.ui.theme.VoiceAssistantTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captura os três modos de resposta em PNG — as figuras do Bloco D parte 1.
 *
 * ## O que estas imagens demonstram, e o que NÃO demonstram
 *
 * **Estado forçado.** O [ResponseMode] de cada tela é escrito à mão aqui, não vem de um
 * score de cascata. Portanto estas imagens demonstram que a **interface** expressa os três
 * modos — e **não** que o roteador os produziu. A §16 exige que a legenda de cada figura
 * diga isso, e a §15 chama de medição que engana exatamente a confusão entre as duas
 * coisas.
 *
 * O print que demonstra o **comportamento** é outro, e vem do aparelho com o
 * `-Plocal.model=qwen-1.5b` e questão real. Os dois são necessários e respondem a
 * perguntas diferentes: este mostra que a tela sabe dizer as três coisas; o do aparelho
 * mostra que o sinal as escolhe.
 *
 * ## Cenário
 *
 * `isOffline = true` e `privacyModeEnabled = true` — o cenário **Unplugged**: sem internet
 * e sem servidor na rede, só o aparelho. É nele que o eixo vertical é a única elasticidade
 * disponível, e é o cenário para o qual o aplicativo foi desenhado.
 *
 * Os arquivos saem no diretório externo do app e são recolhidos por `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class ResponseModeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capturaModoDireto() {
        captura(
            nome = "d1-1-direto",
            modo = ResponseMode.DIRETO,
            resposta = "A luz do Sol se espalha ao atravessar a atmosfera, e o azul se " +
                "espalha mais que as outras cores porque tem onda mais curta."
        )
    }

    @Test
    fun capturaModoRessalva() {
        captura(
            nome = "d1-2-ressalva",
            modo = ResponseMode.RESSALVA,
            resposta = "O azul aparece porque a atmosfera espalha a luz, e creio que as " +
                "cores de onda mais curta se espalham mais."
        )
    }

    @Test
    fun capturaModoMediar() {
        captura(
            nome = "d1-3-mediar",
            modo = ResponseMode.MEDIAR,
            resposta = "O céu é azul por causa do reflexo da água dos oceanos na atmosfera."
        )
    }

    /**
     * O modo mediado com a resposta revelada — a segunda metade da figura 3.
     *
     * Existe porque o recolhimento é a parte do desenho que uma imagem só não mostra: sem
     * este print, o leitor não vê que a resposta **continua disponível**, e poderia
     * concluir que o sistema a descartou.
     */
    @Test
    fun capturaModoMediarRevelado() {
        compose.setContent {
            VoiceAssistantTheme {
                ChatContent(
                    uiState = estadoUnplugged(
                        ResponseMode.MEDIAR,
                        "O céu é azul por causa do reflexo da água dos oceanos na atmosfera."
                    ),
                    onInputChanged = {}, onSendClick = {}, onMicClick = {},
                    onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
                    onTutorModeSelected = {}
                )
            }
        }
        // Se o texto do botão mudar, o compilador não avisa — então a falha tem que ser
        // explícita e com nome, e não um print silenciosamente ainda recolhido.
        val revelador = compose.onAllNodes(hasText(REVELADOR)).fetchSemanticsNodes()
        assertTrue("botão '$REVELADOR' não encontrado — o texto mudou?", revelador.isNotEmpty())
        compose.onNode(hasText(REVELADOR)).performClick()
        compose.waitForIdle()
        salva("d1-3b-mediar-revelado")
    }

    private fun captura(nome: String, modo: ResponseMode, resposta: String) {
        compose.setContent {
            VoiceAssistantTheme {
                ChatContent(
                    uiState = estadoUnplugged(modo, resposta),
                    onInputChanged = {}, onSendClick = {}, onMicClick = {},
                    onNewSession = {}, onDismissError = {}, onStopSpeaking = {},
                    onTutorModeSelected = {}
                )
            }
        }
        compose.waitForIdle()
        salva(nome)
    }

    /**
     * Onde gravar para que o arquivo **sobreviva** à execução.
     *
     * O `connectedAndroidTest` **desinstala** as duas APKs no fim, e o diretório externo do
     * app vai embora com elas — os PNG existiam durante o teste e desapareciam antes de
     * alguém poder recolhê-los. O `additionalTestOutputDir` é o mecanismo próprio do AGP
     * para isto: o que é gravado ali é copiado para
     * `build/outputs/connected_android_test_additional_output/` antes da desinstalação.
     *
     * O fallback no diretório externo fica para quando o teste roda por `am instrument` à
     * mão, onde esse argumento não é passado e não há desinstalação.
     */
    private fun diretorioDeSaida(): File {
        val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val base = if (arg != null) File(arg) else File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "prints-d1"
        )
        return base.apply { mkdirs() }
    }

    private fun salva(nome: String) {
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = diretorioDeSaida()
        val arquivo = File(dir, "$nome.png")
        arquivo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("não gravou $arquivo", arquivo.length() > 0)
        android.util.Log.i("PrintsD1", "gravado: ${arquivo.absolutePath} (${arquivo.length()} B)")
    }

    private companion object {
        /** O texto do revelador, num lugar só — usado pelo teste e pela asserção. */
        const val REVELADOR = "Ver a resposta mesmo assim"
    }

    private fun estadoUnplugged(modo: ResponseMode, resposta: String) = ChatUiState(
        sessionId = "d1",
        messages = listOf(
            ChatMessage(
                id = "u1", sessionId = "d1", role = MessageRole.USER,
                content = "Por que o céu é azul?"
            ),
            ChatMessage(
                id = "a1", sessionId = "d1", role = MessageRole.ASSISTANT,
                content = resposta,
                inferenceSource = InferenceSource.LOCAL,
                latencyMs = 24_000,
                responseMode = modo
            )
        ),
        isOffline = true,
        privacyModeEnabled = true
    )
}
