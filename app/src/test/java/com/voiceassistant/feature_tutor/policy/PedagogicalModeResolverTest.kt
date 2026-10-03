package com.voiceassistant.feature_tutor.policy

import com.voiceassistant.core.model.PedagogicalMode
import com.voiceassistant.feature_tutor.policy.InferenceRouter.Companion.PRESCORE_UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As três faixas do eixo vertical, e as fronteiras entre elas.
 *
 * O que estes testes protegem não é aritmética — é o **sentido** do score. Ele aponta para
 * cima (alto = o local provavelmente acerta), então a mediação fica **abaixo**. Inverter
 * isso produziria uma interface que pede confirmação ao professor justamente nas perguntas
 * que o modelo acertou, que é exatamente o defeito que a fórmula de confiança antiga tem e
 * que o Bloco D parte 1 existe para não repetir. O erro seria silencioso: três modos
 * aparecendo, em proporção plausível, todos na pergunta errada.
 *
 * Os cortes usados aqui são os da `cascata-v1` real (quantil 0,1 ≈ 0,285 e quantil 0,5
 * ≈ 0,634) para que as fronteiras testadas sejam as que vão ao aparelho.
 */
class PedagogicalModeResolverTest {

    private val resolver = PedagogicalModeResolver()

    private fun modo(score: Float, modelo: PolicyModel? = cascataFake()) =
        resolver.resolve(score, modelo)

    // ── As três faixas ────────────────────────────────────────────────────────

    @Test
    fun `score alto responde direto`() {
        assertEquals(PedagogicalMode.DIRETO, modo(0.90f))
        assertEquals(PedagogicalMode.DIRETO, modo(1.0f))
    }

    @Test
    fun `score intermediario responde com ressalva`() {
        assertEquals(PedagogicalMode.RESSALVA, modo(0.50f))
        assertEquals(PedagogicalMode.RESSALVA, modo(0.40f))
    }

    @Test
    fun `score baixo media ao professor`() {
        assertEquals(PedagogicalMode.MEDIAR, modo(0.10f))
        assertEquals(PedagogicalMode.MEDIAR, modo(0.0f))
    }

    @Test
    fun `a mediacao fica ABAIXO, nunca acima`() {
        // A regressão que este teste existe para pegar: se o sentido do score for
        // invertido, a ressalva sobe nas questões que o modelo acertou — pior que não ter
        // ressalva, porque ensina o aluno a desconfiar do sinal.
        val baixo = modo(0.05f)
        val alto = modo(0.95f)
        assertEquals(PedagogicalMode.MEDIAR, baixo)
        assertEquals(PedagogicalMode.DIRETO, alto)
        assertTrue("MEDIAR tem que falar e DIRETO tem que calar",
            baixo!!.temMensagem && !alto!!.temMensagem)
    }

    // ── As fronteiras ─────────────────────────────────────────────────────────

    @Test
    fun `a fronteira do direto e inclusiva no corte`() {
        // Exatamente no corte é DIRETO: `>=`. Um `>` aqui mandaria a mediana do treino
        // para a ressalva e deslocaria a fração de todas as faixas.
        assertEquals(PedagogicalMode.DIRETO, modo(CORTE_DIRETO))
        assertEquals(PedagogicalMode.RESSALVA, modo(CORTE_DIRETO - 0.0001f))
    }

    @Test
    fun `a fronteira da mediacao e inclusiva na ressalva`() {
        assertEquals(PedagogicalMode.RESSALVA, modo(CORTE_MEDIAR))
        assertEquals(PedagogicalMode.MEDIAR, modo(CORTE_MEDIAR - 0.0001f))
    }

    // ── Ausência de sinal não é um modo ───────────────────────────────────────

    @Test
    fun `sem asset nao ha modo`() {
        // Null, nunca DIRETO: um default afirmaria que a resposta está boa sem ter com
        // que afirmar. Um app sem os assets segue funcional, só sem bandeira.
        assertNull(modo(0.5f, modelo = null))
    }

    @Test
    fun `score indisponivel nao ha modo`() {
        assertNull(modo(PRESCORE_UNAVAILABLE))
    }

    @Test
    fun `score fora de 0 a 1 nao ha modo`() {
        assertNull(modo(1.5f))
        assertNull(modo(-0.2f))
    }

    @Test
    fun `NaN nao ha modo`() {
        // NaN falha toda comparação, então um `score < 0 || score > 1` o deixaria passar
        // e ele cairia em MEDIAR pelo `else`. Mediação por aritmética quebrada seria o
        // pior dos casos: a tela manda o aluno ao professor sem motivo nenhum.
        assertNull(modo(Float.NaN))
    }

    @Test
    fun `asset sem limiar nao ha modo`() {
        // Sem `limiar`, `corteParaOrcamento` devolve 0,5 para qualquer fração: os dois
        // cortes colapsam, a faixa da ressalva fica vazia e 0,5 não é corte treinado.
        val semLimiar = PolicyModel(
            nome = "cascata", versao = "v1", features = listOf("x"),
            scaler = PolicyModel.Scaler(mean = listOf(0.0), scale = listOf(1.0)),
            modelo = PolicyModel.Modelo(coef = listOf(1.0), intercept = 0.0)
        )
        assertNull(modo(0.5f, modelo = semLimiar))
    }

    @Test
    fun `quantis que nao separam os cortes nao ha modo`() {
        // Asset malformado: os dois cortes caem no mesmo número. Não classificar é a
        // resposta certa — escolher um dos dois seria arbitrário e invisível.
        val degenerado = cascataFake(quantis = mapOf("0.1" to 0.5, "0.5" to 0.5))
        assertNull(modo(0.5f, modelo = degenerado))
    }

    // ── O corte vem do asset, não do código ───────────────────────────────────

    @Test
    fun `retreinar o asset move os cortes sem tocar no codigo`() {
        // Os cortes são quantis do treino, não constantes daqui. Com outro asset, a mesma
        // pergunta muda de faixa — e é isso que mantém a demonstração honesta se a
        // política for reajustada.
        val outro = cascataFake(quantis = mapOf("0.1" to 0.70, "0.5" to 0.90))
        assertEquals(PedagogicalMode.MEDIAR, modo(0.60f, modelo = outro))
        assertEquals(PedagogicalMode.RESSALVA, modo(0.80f, modelo = outro))
        assertEquals(PedagogicalMode.DIRETO, modo(0.95f, modelo = outro))
    }

    private fun cascataFake(
        quantis: Map<String, Double> = mapOf(
            "0.1" to CORTE_MEDIAR.toDouble(),
            "0.25" to 0.4361378857840839,
            "0.5" to CORTE_DIRETO.toDouble(),
            "0.75" to 0.7388994589698668
        )
    ) = PolicyModel(
        nome = "cascata", versao = "v1", features = listOf("x"),
        scaler = PolicyModel.Scaler(mean = listOf(0.0), scale = listOf(1.0)),
        modelo = PolicyModel.Modelo(coef = listOf(1.0), intercept = 0.0),
        limiar = PolicyModel.Limiar(
            referencia_mediana_treino = CORTE_DIRETO.toDouble(),
            quantis_treino = quantis
        )
    )

    private companion object {
        /** Quantil 0,5 da `cascata-v1` — a mediana dos scores do treino. */
        const val CORTE_DIRETO = 0.6340934050743208f

        /** Quantil 0,1 da `cascata-v1` — a fatia reservada à mediação. */
        const val CORTE_MEDIAR = 0.2851805736931631f
    }
}
