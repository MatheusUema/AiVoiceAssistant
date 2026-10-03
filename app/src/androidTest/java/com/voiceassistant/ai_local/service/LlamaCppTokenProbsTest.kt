package com.voiceassistant.ai_local.service

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.llama.LlamaEngine
import com.voiceassistant.llama.LlamaParams
import com.voiceassistant.llama.TokenProbSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Coerência interna das amostras por token que o JNI passou a expor.
 *
 * **Por que não há teste de paridade para o tier local.** O `FeatureParityTest` compara
 * as features da cascata contra o Python usando logprobs do `llama-server` — os dois lados
 * veem o mesmo `completion_probabilities`, então a comparação é legítima. O tier local não
 * tem referência externa: os logprobs nascem no aparelho, do modelo carregado ali, e não
 * existe um "valor certo" produzido por outra ferramenta para confrontar. Afirmar que
 * houve paridade no local seria afirmar mais do que se mediu.
 *
 * O que **dá** para verificar, e é o que este teste faz, é coerência: o bridge já calculava
 * a confiança como a média das probabilidades dos tokens escolhidos, por um caminho
 * independente (soma e contador em C++). Se a média reconstruída a partir das amostras
 * novas bater com aquele número, as amostras se referem às mesmas posições, na mesma
 * ordem, com as mesmas probabilidades. É uma verificação de integridade do transporte
 * JNI — não de correção do modelo.
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppTokenProbsTest {

    // Corpo em bloco, e não `= runBlocking { ... }`: com a forma de expressão o método
    // herda o tipo do último `Log.i` (Int), e o JUnit recusa a classe inteira com
    // "should be void" — sem executar nenhum teste.
    @Test
    fun mediaReconstruidaBateComAConfiancaDoBridge() {
        runBlocking {
        assumeTrue("libllama_bridge.so indisponível", LlamaEngine.isNativeAvailable)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gguf = achaModelo(context.getExternalFilesDir("models"))
        assumeTrue("nenhum .gguf no aparelho — envie com scripts/push-model.ps1", gguf != null)

        val engine = LlamaEngine(context.applicationInfo?.nativeLibraryDir.orEmpty())
        val params = LlamaParams(maxTokens = 48, temperature = 0.2f, seed = 42)
        engine.load(gguf!!.absolutePath, params)
        assumeTrue("modelo não carregou", engine.isLoaded)

        try {
            val g = engine.generate("Explique em uma frase o que é fotossíntese.", params)

            assertTrue(
                "o JNI não devolveu amostras por token — nativeLastTokenProbs vazio",
                g.tokenProbs.isNotEmpty()
            )

            val media = g.tokenProbs.map { it.prob }.average()
            Log.i(
                TAG,
                // O `.format` liga-se só ao literal imediatamente à esquerda; com a
                // concatenação, o primeiro %.6f ficava sem substituir e o log saía cru.
                ("${g.tokenProbs.size} amostras | média reconstruída=%.6f | " +
                    "confidence do bridge=%.6f").format(media, g.stats.confidence)
            )

            // 1e-9: os dois lados somam os MESMOS doubles, em ordens de acumulação
            // diferentes (o C++ acumula incrementalmente, o Kotlin soma a lista). A
            // diferença admissível é só de ponto flutuante.
            assertEquals(
                "média das amostras ≠ confiança do bridge — as amostras não " +
                    "correspondem às posições que produziram a confiança",
                g.stats.confidence, media, 1e-9
            )

            // Uma amostra tem que ser uma distribuição plausível: probabilidade em [0,1],
            // top-k em ordem decrescente, e a do escolhido nunca acima da maior de todas.
            g.tokenProbs.forEachIndexed { i, t ->
                assertTrue("token $i: prob fora de [0,1]: ${t.prob}", t.prob in 0.0..1.0)
                assertEquals(
                    "token $i: esperava ${TokenProbSample.TOP_PROBS} candidatos",
                    TokenProbSample.TOP_PROBS, t.topProbs.size
                )
                for (k in 1 until t.topProbs.size) {
                    assertTrue(
                        "token $i: top-k fora de ordem em $k (${t.topProbs})",
                        t.topProbs[k] <= t.topProbs[k - 1] + 1e-12
                    )
                }
                assertTrue(
                    "token $i: prob do escolhido (${t.prob}) acima do maior " +
                        "candidato (${t.topProbs.first()})",
                    t.prob <= t.topProbs.first() + 1e-9
                )
                // A soma dos top-k é uma massa parcial do softmax: nunca passa de 1.
                assertTrue(
                    "token $i: soma dos top-k > 1 (${t.topProbs.sum()})",
                    t.topProbs.sum() <= 1.0 + 1e-6
                )
            }

            // O sampler usa temperatura > 0, então o escolhido NEM SEMPRE é o mais
            // provável. Se fosse sempre, seria sinal de que se está lendo o topo em vez
            // do escolhido — o erro que a doc de `accumulate_token_prob` alerta.
            val iguaisAoTopo = g.tokenProbs.count { abs(it.prob - it.topProbs.first()) < 1e-12 }
            Log.i(TAG, "escolhido == top1 em $iguaisAoTopo de ${g.tokenProbs.size} posições")

            // As STRINGS: sem elas `conf_letra_b1` cai no fallback no tier local enquanto
            // o servidor usa o valor real, e os dois passariam a medir features diferentes
            // com o mesmo nome.
            val comTexto = g.tokenProbs.count { it.token.isNotEmpty() }
            assertTrue(
                "nenhuma amostra trouxe o texto do token — nativeLastTokenStrings vazio " +
                    "ou desalinhado, e conf_letra_b1 cairia no fallback no tier local",
                comTexto > 0
            )
            // Alinhamento: o texto concatenado tem que reproduzir a resposta. Se as duas
            // listas estivessem deslocadas em uma posição, `conf_letra_b1` leria a
            // probabilidade de OUTRO token — numérica, plausível e errada.
            val reconstruido = g.tokenProbs.joinToString("") { it.token }
            assertTrue(
                "texto reconstruído dos tokens não contém a resposta — listas desalinhadas.\n" +
                    "  resposta=${g.text.take(80)}\n  reconstruído=${reconstruido.take(80)}",
                reconstruido.contains(g.text.trim().take(40))
            )
            Log.i(TAG, "$comTexto de ${g.tokenProbs.size} amostras com texto; " +
                    "reconstrução casa com a resposta")
            } finally {
                engine.unload()
            }
        }
    }

    private fun achaModelo(dir: File?): File? =
        dir?.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".gguf") && it.length() > 0 }

    private companion object {
        const val TAG = "LlamaCppTokenProbs"
    }
}
