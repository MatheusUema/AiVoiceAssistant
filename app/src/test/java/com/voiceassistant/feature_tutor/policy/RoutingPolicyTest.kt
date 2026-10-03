package com.voiceassistant.feature_tutor.policy

import com.voiceassistant.core.model.PromptComplexity
import com.voiceassistant.feature_tutor.policy.InferenceRouter.Companion.PRESCORE_UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As políticas novas em [InferenceRouter.resolveRoute].
 *
 * O `InferenceRouterResolveRouteTest` continua sendo o dono das onze regras originais e
 * **não foi alterado** — o critério de aceitação desta etapa era exatamente esse: os
 * parâmetros novos têm defaults tais que toda a matriz existente vale sem uma linha mexida.
 * Aqui verifica-se só o que passou a existir.
 */
class RoutingPolicyTest {

    /** Cenário comum: online, com os três tiers disponíveis, sem privacidade. */
    private fun rota(
        policy: RoutingPolicy = RoutingPolicy.HEURISTIC,
        preScore: Float = PRESCORE_UNAVAILABLE,
        corte: Float = 0.5f,
        complexity: PromptComplexity = PromptComplexity.SIMPLE,
        isLocalAvailable: Boolean = true,
        isCloudAvailable: Boolean = true,
        isServerAvailable: Boolean = false,
        isOnline: Boolean = true,
        privacyMode: Boolean = false
    ) = InferenceRouter.resolveRoute(
        isOnline = isOnline,
        isLocalAvailable = isLocalAvailable,
        isServerAvailable = isServerAvailable,
        isCloudAvailable = isCloudAvailable,
        complexity = complexity,
        privacyMode = privacyMode,
        policy = policy,
        preScore = preScore,
        preScoreThreshold = corte
    )

    // ── Compatibilidade: os defaults preservam o comportamento de hoje ─────────

    @Test
    fun `sem politica os defaults reproduzem a heuristica`() {
        assertEquals(RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK, rota())
        assertEquals(
            RoutingDecision.CLOUD,
            rota(complexity = PromptComplexity.COMPLEX)
        )
    }

    // ── Roteador aprendido ────────────────────────────────────────────────────

    @Test
    fun `LEARNED escala quando o score fica ABAIXO do corte`() {
        // Score alto = local provavelmente acerta. Escala-se quem fica abaixo — inverter
        // isto produziria uma política que escala o que o local acertaria.
        assertEquals(RoutingDecision.CLOUD, rota(RoutingPolicy.LEARNED, preScore = 0.20f))
    }

    @Test
    fun `LEARNED nao escala quando o score fica acima do corte`() {
        assertEquals(
            RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK,
            rota(RoutingPolicy.LEARNED, preScore = 0.80f)
        )
    }

    @Test
    fun `LEARNED no limiar exato nao escala`() {
        // O corte é `< limiar`, então o valor exato fica com o local. Importa porque os
        // cortes vêm de quantis: com o quantil 0,25 espera-se escalar 25%, não 25% + os
        // empates.
        assertEquals(
            RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK,
            rota(RoutingPolicy.LEARNED, preScore = 0.5f, corte = 0.5f)
        )
    }

    @Test
    fun `LEARNED sem score cai na heuristica`() {
        // Degradação graciosa, igual ao `confidence == -1` do runServer: sinal ausente
        // devolve o comportamento anterior, nunca uma decisão sobre número inventado.
        assertEquals(
            RoutingDecision.LOCAL_WITH_CLOUD_FALLBACK,
            rota(RoutingPolicy.LEARNED, preScore = PRESCORE_UNAVAILABLE)
        )
        assertEquals(
            RoutingDecision.CLOUD,
            rota(
                RoutingPolicy.LEARNED,
                preScore = PRESCORE_UNAVAILABLE,
                complexity = PromptComplexity.COMPLEX
            )
        )
    }

    @Test
    fun `LEARNED sem nuvem nao escala mesmo com score baixo`() {
        assertEquals(
            RoutingDecision.LOCAL,
            rota(RoutingPolicy.LEARNED, preScore = 0.01f, isCloudAvailable = false)
        )
    }

    @Test
    fun `LEARNED_THEN_CASCADE tambem usa o pre-score`() {
        assertEquals(
            RoutingDecision.CLOUD,
            rota(RoutingPolicy.LEARNED_THEN_CASCADE, preScore = 0.10f)
        )
    }

    // ── Baselines ─────────────────────────────────────────────────────────────

    @Test
    fun `ALWAYS_LOCAL nunca escala, nem em pergunta complexa`() {
        assertEquals(
            RoutingDecision.LOCAL,
            rota(RoutingPolicy.ALWAYS_LOCAL, complexity = PromptComplexity.COMPLEX)
        )
    }

    @Test
    fun `ALWAYS_CLOUD escala mesmo em pergunta simples`() {
        assertEquals(
            RoutingDecision.CLOUD,
            rota(RoutingPolicy.ALWAYS_CLOUD, complexity = PromptComplexity.SIMPLE)
        )
    }

    @Test
    fun `ALWAYS_CLOUD nao fura o modo privacidade`() {
        // A política é uma escolha de CUSTO. Se ela pudesse vencer a privacidade, deixaria
        // de ser isso e passaria a ser uma violação — os dados sairiam do aparelho de quem
        // pediu que não saíssem.
        assertEquals(
            RoutingDecision.LOCAL,
            rota(RoutingPolicy.ALWAYS_CLOUD, privacyMode = true)
        )
    }

    @Test
    fun `ALWAYS_CLOUD nao inventa nuvem quando esta offline`() {
        assertEquals(
            RoutingDecision.LOCAL,
            rota(RoutingPolicy.ALWAYS_CLOUD, isOnline = false, isCloudAvailable = false)
        )
    }

    @Test
    fun `ALWAYS_LOCAL sem modelo local degrada em vez de falhar`() {
        assertNotEquals(
            RoutingDecision.ERROR_UNAVAILABLE,
            rota(RoutingPolicy.ALWAYS_LOCAL, isLocalAvailable = false)
        )
    }

    // ── Oráculo ───────────────────────────────────────────────────────────────

    @Test
    fun `ORACLE_IRT se comporta como a heuristica`() {
        // Não é implantável (precisa do difficulty_score do dataset). Existe como teto de
        // comparação na análise; se chegar ao roteador, não pode inventar comportamento.
        assertEquals(
            rota(RoutingPolicy.HEURISTIC, complexity = PromptComplexity.COMPLEX),
            rota(RoutingPolicy.ORACLE_IRT, complexity = PromptComplexity.COMPLEX)
        )
        assertEquals(
            rota(RoutingPolicy.HEURISTIC),
            rota(RoutingPolicy.ORACLE_IRT)
        )
    }

    // ── Metadados das políticas ───────────────────────────────────────────────

    @Test
    fun `as flags das politicas dizem quem precisa de que score`() {
        assertTrue(RoutingPolicy.LEARNED.needsPreScore)
        assertTrue(RoutingPolicy.LEARNED_THEN_CASCADE.needsPreScore)
        assertTrue(!RoutingPolicy.CASCADE.needsPreScore)

        assertTrue(RoutingPolicy.CASCADE.needsCascade)
        assertTrue(RoutingPolicy.LEARNED_THEN_CASCADE.needsCascade)
        assertTrue(!RoutingPolicy.LEARNED.needsCascade)

        assertTrue(RoutingPolicy.ALWAYS_LOCAL.isBaseline)
        assertTrue(RoutingPolicy.ALWAYS_CLOUD.isBaseline)
        assertTrue(!RoutingPolicy.HEURISTIC.isBaseline)
    }

    @Test
    fun `CASCADE nao muda a decisao pre-inferencia`() {
        // A cascata decide DEPOIS de inferir, então na resolução da rota ela tem que ser
        // indistinguível da heurística — o escalonamento dela acontece em `aplicaCascata`.
        assertEquals(rota(RoutingPolicy.HEURISTIC), rota(RoutingPolicy.CASCADE))
    }

    // ── Orçamento ─────────────────────────────────────────────────────────────

    @Test
    fun `o corte por orcamento escolhe o quantil mais proximo`() {
        val m = modeloFake(
            quantis = mapOf("0.1" to 0.27, "0.25" to 0.46, "0.5" to 0.62, "0.75" to 0.75)
        )
        assertEquals(0.27, m.corteParaOrcamento(0.10), 1e-9)
        assertEquals(0.46, m.corteParaOrcamento(0.25), 1e-9)
        assertEquals(0.62, m.corteParaOrcamento(0.50), 1e-9)
        // Fração sem quantil exportado: pega o mais próximo em vez de inventar.
        assertEquals(0.46, m.corteParaOrcamento(0.30), 1e-9)
    }

    @Test
    fun `sem quantis o corte cai na mediana do treino`() {
        val m = modeloFake(quantis = emptyMap(), mediana = 0.61)
        assertEquals(0.61, m.corteParaOrcamento(0.25), 1e-9)
    }

    @Test
    fun `o score reproduz a sigmoide da regressao`() {
        // Uma feature, mean=0, scale=1, coef=1, intercept=0 → sigmoid(x).
        val m = PolicyModel(
            nome = "t", versao = "v1", features = listOf("x"),
            scaler = PolicyModel.Scaler(mean = listOf(0.0), scale = listOf(1.0)),
            modelo = PolicyModel.Modelo(coef = listOf(1.0), intercept = 0.0)
        )
        assertEquals(0.5, m.score(mapOf("x" to 0.0)), 1e-12)
        assertEquals(1.0 / (1.0 + Math.exp(-2.0)), m.score(mapOf("x" to 2.0)), 1e-12)
    }

    @Test
    fun `scale zero nao vira NaN`() {
        // Feature constante no treino: o sklearn troca scale_ 0 por 1. Sem repetir isso, a
        // divisão produziria NaN, que atravessaria a sigmoide sem estourar.
        val m = PolicyModel(
            nome = "t", versao = "v1", features = listOf("x"),
            scaler = PolicyModel.Scaler(mean = listOf(1.0), scale = listOf(0.0)),
            modelo = PolicyModel.Modelo(coef = listOf(1.0), intercept = 0.0)
        )
        assertTrue(!m.score(mapOf("x" to 3.0)).isNaN())
    }

    @Test(expected = IllegalStateException::class)
    fun `feature ausente e erro, nao zero`() {
        // Um zero seria padronizado para `(0 - mean)/scale`, costuma ser valor extremo, e o
        // score sairia confiantemente errado. Melhor não pontuar do que pontuar mal.
        modeloFake().vector(mapOf("outra" to 1.0))
    }

    @Test
    fun `o vetor sai na ordem do modelo, nao na do extrator`() {
        val m = PolicyModel(
            nome = "t", versao = "v1", features = listOf("b", "a"),
            scaler = PolicyModel.Scaler(mean = listOf(0.0, 0.0), scale = listOf(1.0, 1.0)),
            modelo = PolicyModel.Modelo(coef = listOf(1.0, 1.0), intercept = 0.0)
        )
        // O mapa vem em outra ordem de propósito: o casamento é por NOME.
        val v = m.vector(linkedMapOf("a" to 1.0, "b" to 2.0))
        assertEquals(2.0, v[0], 0.0)
        assertEquals(1.0, v[1], 0.0)
    }

    private fun modeloFake(
        quantis: Map<String, Double> = mapOf("0.5" to 0.5),
        mediana: Double = 0.5
    ) = PolicyModel(
        nome = "fake", versao = "v1", features = listOf("x"),
        scaler = PolicyModel.Scaler(mean = listOf(0.0), scale = listOf(1.0)),
        modelo = PolicyModel.Modelo(coef = listOf(1.0), intercept = 0.0),
        limiar = PolicyModel.Limiar(
            referencia_mediana_treino = mediana,
            quantis_treino = quantis
        )
    )
}
