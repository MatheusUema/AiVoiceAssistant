package com.voiceassistant.feature_tutor.policy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceassistant.feature_benchmark.data.EnemDataset
import com.voiceassistant.feature_benchmark.data.EnemPromptBuilder
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Paridade Kotlin × Python das features das duas políticas, questão a questão.
 *
 * Os modelos implantados usam coeficientes ajustados sobre as features que o Python
 * calculou. Se um extrator divergir, o app não fica "um pouco menos preciso" — ele
 * alimenta o modelo com entrada de outra distribuição, e o score sai numérico, plausível
 * e errado. Nada estoura, nada loga, e a queda de desempenho fica sem dono.
 *
 * É o mesmo mecanismo que o `enem_prompt.py` já usa do outro lado, onde monta os prompts a
 * partir do dataset e compara byte a byte com os que o aparelho produziu.
 *
 * ## O que cada metade consegue provar
 *
 * **Roteador (18 features de string):** ponta a ponta. A entrada é o enunciado, que o app
 * reconstrói do próprio dataset — se o resultado bate, o caminho inteiro bate.
 *
 * **Cascata (17 features de logprob):** o extrator, não a coleta. A entrada vem do
 * `fixture-logprobs-80.jsonl`, recortado do que o **llama-server** produziu — o mesmo
 * formato que o tier servidor entrega em `completion_probabilities`. Isso valida a
 * aritmética e o tier servidor de ponta a ponta; o tier **local** passa pelo mesmo
 * extrator, mas a sua entrada vem do JNI e não tem referência externa contra a qual
 * comparar. Para ele, o que dá para verificar é a coerência interna — ver
 * `LlamaCppTokenProbsTest`.
 */
@RunWith(AndroidJUnit4::class)
class FeatureParityTest {

    private val stringExtractor = StringFeatureExtractor()
    private val logprobExtractor = LogprobFeatureExtractor()
    private val promptBuilder = EnemPromptBuilder()

    // ── Roteador ──────────────────────────────────────────────────────────────

    @Test
    fun featuresDoRoteadorBatemComOPython() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val csv = leAsset(FIXTURE_FEATURES) ?: return pula(FIXTURE_FEATURES)

        val (colunas, linhas) = tabela(csv)
        val dataset = EnemDataset(instrumentation.targetContext)
        val porChave = dataset.all().associateBy { Triple(it.id, it.year, it.area) }

        val nomes = StringFeatureExtractor.FEATURE_NAMES
        val ausentes = nomes.filterNot { "rot_$it" in colunas }
        assertTrue("fixture não traz as features do roteador: $ausentes", ausentes.isEmpty())

        val divergencias = mutableListOf<String>()
        var conferidas = 0

        for (linha in linhas) {
            val id = linha[colunas.getValue("id")].trim()
            val ano = linha[colunas.getValue("ano")].trim().toIntOrNull() ?: continue
            val area = linha[colunas.getValue("area")].trim()
            val q = porChave[Triple(id, ano, area)]
            assertTrue("questão $id/$ano/$area do fixture não existe no dataset", q != null)

            val cru = stringExtractor.enunciadoCru(promptBuilder.build(q!!))
            val meu = stringExtractor.extract(cru, q.alternatives, q.area)

            // O fixture traz o tamanho do cru; conferir antes das features localiza um
            // erro de pré-processamento no PRÉ-PROCESSAMENTO, em vez de o fazer aparecer
            // espalhado por sete densidades que dividem por ele.
            colunas["enunciado_cru_n_chars"]?.let { i ->
                val esperado = linha[i].trim().toIntOrNull()
                if (esperado != null && esperado.toDouble() != meu.getValue("n_chars")) {
                    divergencias += "$id/$ano/$area · enunciado_cru_n_chars: " +
                        "kotlin=${meu.getValue("n_chars").toInt()} python=$esperado"
                }
            }

            for (nome in nomes) {
                val esperado = linha[colunas.getValue("rot_$nome")].trim().toDoubleOrNull()
                    ?: continue
                val obtido = meu.getValue(nome)
                if (abs(obtido - esperado) > TOLERANCIA) {
                    divergencias += "$id/$ano/$area · $nome: kotlin=%.9f python=%.9f"
                        .format(obtido, esperado)
                }
            }
            conferidas++
        }
        relata("roteador", conferidas, nomes.size, divergencias)
    }

    // ── Cascata ───────────────────────────────────────────────────────────────

    @Test
    fun featuresDaCascataBatemComOPython() {
        val csv = leAsset(FIXTURE_FEATURES) ?: return pula(FIXTURE_FEATURES)
        val jsonl = leAsset(FIXTURE_LOGPROBS) ?: return pula(FIXTURE_LOGPROBS)

        val (colunas, linhas) = tabela(csv)
        val esperadoPorChave = linhas.associateBy {
            Triple(
                it[colunas.getValue("id")].trim(),
                it[colunas.getValue("ano")].trim(),
                it[colunas.getValue("area")].trim()
            )
        }

        val nomes = LogprobFeatureExtractor.FEATURE_NAMES
        val ausentes = nomes.filterNot { "cas_$it" in colunas }
        assertTrue("fixture não traz as features da cascata: $ausentes", ausentes.isEmpty())

        val divergencias = mutableListOf<String>()
        var conferidas = 0
        var semFeatures = 0

        for (bruta in jsonl.lineSequence()) {
            val linhaJson = bruta.trim()
            if (linhaJson.isEmpty()) continue
            val o = JSONObject(linhaJson)
            val chave = Triple(
                o.getString("id"), o.getString("ano"), o.getString("area")
            )
            val esperado = esperadoPorChave[chave] ?: continue

            val tokens = mutableListOf<TokenDistribution>()
            val arr = o.getJSONArray("toks")
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val top = t.getJSONArray("top")
                tokens += TokenDistribution(
                    token = if (t.isNull("t")) null else t.getString("t"),
                    // `lp` é o LOGPROB; a probabilidade é exp(lp). O fixture guarda o
                    // logprob cru justamente para não embutir a exponencial no arquivo.
                    prob = if (t.isNull("lp")) null else kotlin.math.exp(t.getDouble("lp")),
                    topProbs = List(top.length()) { k -> kotlin.math.exp(top.getDouble(k)) }
                )
            }

            val letra = if (o.isNull("letra")) null else o.getString("letra")
            val meu = logprobExtractor.extract(tokens, letra)
            if (meu == null) { semFeatures++; continue }

            for (nome in nomes) {
                val alvo = esperado[colunas.getValue("cas_$nome")].trim().toDoubleOrNull()
                    ?: continue
                val obtido = meu.getValue(nome)
                if (abs(obtido - alvo) > TOLERANCIA) {
                    divergencias += "${chave.first}/${chave.second}/${chave.third} · " +
                        "$nome: kotlin=%.9f python=%.9f".format(obtido, alvo)
                }
            }
            conferidas++
        }
        Log.i(TAG, "cascata: $semFeatures questões sem tokens suficientes (esperado: 0)")
        relata("cascata", conferidas, nomes.size, divergencias)
    }

    // ── Extrator de letra ─────────────────────────────────────────────────────

    /**
     * O extrator de letra do app contra o `sugestao_auto` do Python, sobre as MESMAS
     * respostas — e, separadamente, contra a leitura humana.
     *
     * São duas perguntas distintas e as duas importam:
     *
     *  1. **Paridade com o Python** (`letra_auto`): tem que ser exata. Uma divergência
     *     aqui é bug de porte, e `conf_letra_b1` passaria a medir outra coisa em runtime.
     *  2. **Concordância com a leitura humana** (`letra_manual`): NÃO é exata, e não
     *     deveria ser — o extrator erra quando o modelo se corrige depois de cravar. O
     *     que se verifica é que a taxa continua no patamar conhecido (~99%), e não que
     *     ela é 100%. Se um dia cair, é sinal de que os modelos mudaram de formato.
     */
    @Test
    fun extratorDeLetraBateComOPython() {
        val csv = leAsset(FIXTURE_LETRAS) ?: return pula(FIXTURE_LETRAS)
        val (colunas, linhas) = tabela(csv)
        val extractor = AnswerLetterExtractor()

        var n = 0
        var igualAoManual = 0
        var semLetra = 0
        val divergePython = mutableListOf<String>()
        val divergeManual = mutableListOf<String>()

        for (l in linhas) {
            val id = l[colunas.getValue("id")].trim()
            val ano = l[colunas.getValue("ano")].trim()
            val area = l[colunas.getValue("area")].trim()
            val manual = l[colunas.getValue("letra_manual")].trim().uppercase()
            val python = l[colunas.getValue("letra_auto")].trim().uppercase()
            val resposta = l[colunas.getValue("response")]

            val meu = extractor.extract(resposta) ?: ""
            n++
            if (meu != python) divergePython += "$id/$ano/$area: kotlin='$meu' python='$python'"
            if (meu.isEmpty()) semLetra++ else if (meu == manual) igualAoManual++
            if (meu.isNotEmpty() && meu != manual) {
                divergeManual += "$id/$ano/$area: auto=$meu manual=$manual"
            }
        }

        Log.i(TAG, "letra: $n respostas | == python: ${n - divergePython.size} | " +
                "== manual: $igualAoManual | sem letra: $semLetra")
        divergeManual.forEach { Log.i(TAG, "  auto≠manual (esperado, ~1%): $it") }

        assertTrue(
            "extrator diverge do Python em ${divergePython.size}: ${divergePython.take(5)}",
            divergePython.isEmpty()
        )
        // Patamar, não igualdade: o extrator erra por construção quando o modelo se
        // corrige. Abaixo de 95% seria mudança de comportamento, não ruído.
        val taxa = 100.0 * igualAoManual / n
        assertTrue(
            "concordância com a leitura humana caiu para %.2f%% (esperado ~99%%)".format(taxa),
            taxa >= 95.0
        )
    }

    // ── Políticas: o score reproduz o sklearn ─────────────────────────────────

    @Test
    fun politicasCarregamEPontuam() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val coef = PolicyCoefficients(instrumentation.targetContext)

        for ((id, nomes) in listOf(
            "roteador-v1" to StringFeatureExtractor.FEATURE_NAMES,
            "cascata-v1" to LogprobFeatureExtractor.FEATURE_NAMES
        )) {
            val m = coef.load(id)
            assumeTrue("asset policies/$id.json ausente", m != null)
            m!!

            // As features do JSON e as do extrator têm que ser o MESMO conjunto. Se
            // divergirem, `vector()` falharia em runtime — melhor descobrir aqui.
            assertTrue(
                "$id: features do asset ≠ do extrator. " +
                    "só no asset=${m.features - nomes.toSet()} " +
                    "só no extrator=${nomes - m.features.toSet()}",
                m.features.toSet() == nomes.toSet()
            )

            // Score de um vetor arbitrário: o que se verifica aqui é que a conta roda e
            // devolve probabilidade válida. A igualdade numérica com o sklearn foi
            // verificada do lado Python (1e-16) e é reconferida pelos testes acima, que
            // alimentam features reais.
            val entrada = nomes.associateWith { 0.0 }
            val s = m.score(entrada)
            assertTrue("$id: score fora de [0,1]: $s", s in 0.0..1.0 && !s.isNaN())
            // `%%` escapa o literal: com "25%" solto, o Formatter lê o '%' como início de
            // especificador e estoura com UnknownFormatConversionException.
            Log.i(TAG, "$id: ${m.features.size} features, treino n=${m.treino_n}, " +
                    "corte p/ 25%% = %.4f".format(m.corteParaOrcamento(0.25)))
        }
    }

    // ── Apoio ─────────────────────────────────────────────────────────────────

    private fun leAsset(nome: String): String? = runCatching {
        InstrumentationRegistry.getInstrumentation().context.assets.open(nome)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private fun pula(nome: String) {
        assumeTrue(
            "Fixture ausente: app/src/androidTest/assets/$nome " +
                "(origem: resultados/politicas/)",
            false
        )
    }

    private fun relata(
        qual: String,
        conferidas: Int,
        porQuestao: Int,
        divergencias: List<String>
    ) {
        Log.i(TAG, "$qual: $conferidas questões × $porQuestao features = " +
                "${conferidas * porQuestao} comparações")
        if (divergencias.isNotEmpty()) {
            Log.e(TAG, "DIVERGÊNCIAS em $qual (${divergencias.size}):")
            divergencias.take(MAX_LOG).forEach { Log.e(TAG, "  $it") }
        }
        // Todas de uma vez: descobrir uma por execução, num teste que roda em aparelho,
        // custaria um ciclo de build por feature.
        assertTrue(
            "$qual: ${divergencias.size} divergências acima de $TOLERANCIA " +
                "(primeiras: ${divergencias.take(3)})",
            divergencias.isEmpty()
        )
        assertTrue("$qual: nenhuma questão conferida", conferidas > 0)
    }

    /** @return (nome da coluna → índice, linhas de dados). */
    private fun tabela(csv: String): Pair<Map<String, Int>, List<List<String>>> {
        val linhas = parseCsv(csv)
        assertTrue("fixture vazio ou só com cabeçalho", linhas.size > 1)
        val colunas = linhas.first().withIndex()
            .associate { (i, nome) -> nome.trim() to i }
        for (c in listOf("id", "ano", "area")) {
            assertTrue("fixture sem coluna '$c'", c in colunas)
        }
        val dados = linhas.drop(1).filter { it.size >= linhas.first().size }
        return colunas to dados
    }

    /**
     * Parser RFC 4180 mínimo: os enunciados do ENEM têm vírgula, aspas e quebra de linha
     * dentro dos campos, e um `split(',')` deslocaria colunas em silêncio — o mesmo motivo
     * pelo qual `EnemDataset` escreve o seu à mão.
     */
    private fun parseCsv(texto: String): List<List<String>> {
        val linhas = mutableListOf<List<String>>()
        var linha = mutableListOf<String>()
        val campo = StringBuilder()
        var emAspas = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                emAspas && c == '"' && i + 1 < texto.length && texto[i + 1] == '"' -> {
                    campo.append('"'); i++
                }
                c == '"' -> emAspas = !emAspas
                c == ',' && !emAspas -> { linha.add(campo.toString()); campo.setLength(0) }
                (c == '\n' || c == '\r') && !emAspas -> {
                    if (c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') i++
                    linha.add(campo.toString()); campo.setLength(0)
                    if (linha.any { it.isNotBlank() }) linhas.add(linha)
                    linha = mutableListOf()
                }
                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || linha.isNotEmpty()) {
            linha.add(campo.toString())
            if (linha.any { it.isNotBlank() }) linhas.add(linha)
        }
        return linhas
    }

    private companion object {
        const val TAG = "FeatureParity"
        const val FIXTURE_FEATURES = "politicas/fixture-features-80.csv"
        const val FIXTURE_LOGPROBS = "politicas/fixture-logprobs-80.jsonl"
        const val FIXTURE_LETRAS = "politicas/fixture-letras-80.csv"

        /**
         * 1e-6 absoluto. As features vão de 0 a ~1500 (`n_chars`), então tolerância
         * relativa esconderia erro nas grandes; as contagens são inteiras e têm que bater
         * exatamente, e a menor divergência real possível numa densidade é um caractere a
         * mais ou a menos (≈ 1e-3).
         */
        const val TOLERANCIA = 1e-6
        const val MAX_LOG = 40
    }
}
