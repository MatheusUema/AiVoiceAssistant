package com.voiceassistant.feature_tutor.policy

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Uma posição gerada, no formato **neutro de tier** que a cascata consome.
 *
 * Os dois tiers que expõem logprobs entregam a mesma coisa por caminhos diferentes: o
 * servidor em `completion_probabilities` (`n_probs=5`), o local pelo array achatado do
 * JNI. Convertê-los para este tipo antes do extrator é o que faz a entropia e a margem
 * saírem da mesma conta nos dois — senão a comparação local × servidor mediria a
 * diferença entre as fórmulas, e não entre os modelos.
 */
data class TokenDistribution(
    /**
     * Texto do token escolhido, quando o tier o expõe. Necessário **só** para
     * `conf_letra_b1`; null faz aquela feature cair no fallback definido pelo Python.
     */
    val token: String?,
    /**
     * Probabilidade do token escolhido — `exp(logprob)`. Null quando a posição não traz
     * logprob: o Python a exclui das estatísticas mas ainda a percorre nos laços de
     * entropia e da letra, e essa assimetria é preservada aqui.
     */
    val prob: Double?,
    /**
     * Probabilidades dos candidatos mais prováveis, **sem renormalizar**. A
     * renormalização é feita aqui dentro, uma vez, para os dois tiers.
     */
    val topProbs: List<Double>
)

/**
 * Converte as amostras do tier local para o formato neutro da cascata.
 *
 * Os dois tiers convergem aqui: o servidor passa por
 * `ServerInferenceService.toTokenDistributions`, o local por esta função. Depois deste
 * ponto o extrator não sabe — nem precisa saber — de onde veio a distribuição.
 *
 * O `token` vazio vira null: `conf_letra_b1` distingue "não sei a string" de "a string é
 * vazia", e a segunda nunca casaria com uma letra de qualquer forma.
 */
fun List<com.voiceassistant.llama.TokenProbSample>.toTokenDistributions(): List<TokenDistribution> =
    map { s ->
        TokenDistribution(
            token = s.token.ifEmpty { null },
            prob = s.prob,
            topProbs = s.topProbs
        )
    }

/**
 * As 17 features da **cascata recalibrada** (Fase 1, etapa C) — pós-inferência.
 *
 * Porte literal de `features_logprobs()` (`roteamento_fase1.py:226`), com o `limpa()` de
 * `recalcular_mcnemar_auc.py:220`. A cascata pergunta o que a confiança crua não responde:
 * *depois de responder, dá para saber se vale reperguntar a um modelo maior?* A média das
 * probabilidades — que é a confiança que o app já calcula — é apenas UMA destas 17, e a
 * Fase 1 mostrou que sozinha ela perde para o conjunto recalibrado.
 *
 * ## Paridade: onde Kotlin e numpy discordam por padrão
 *
 *  - **`np.percentile`** interpola linearmente entre os dois vizinhos. Um percentil por
 *    rank-mais-próximo — o jeito ingênuo — dá outro número em quase toda amostra. Ver
 *    [percentil].
 *  - **`np.median`** é a `percentile(50)`: em n par, a MÉDIA dos dois centrais, não o de
 *    baixo.
 *  - **`np.std`** é populacional (÷N). O desvio amostral (÷N−1) é o default de quase toda
 *    outra biblioteca.
 *  - **entropia e margem** saem do top-k **renormalizado** (`pv / pv.sum()`), com o
 *    `+1e-12` dentro do log — que não é decorativo: sem ele um candidato de probabilidade
 *    exatamente zero produz `ln(0) = -inf` e a entropia vira NaN.
 *  - **`conf_letra_b1`** casa a ÚLTIMA ocorrência, não a primeira: o laço do Python não
 *    tem `break`. Isso importa porque o modelo cita letras ao descartar alternativas antes
 *    de concluir — pegar a primeira pegaria uma que ele rejeitou.
 */
@Singleton
class LogprobFeatureExtractor @Inject constructor() {

    /**
     * @param tokens posições geradas, na ordem em que saíram.
     * @param letra alternativa escolhida (A–E), para `conf_letra_b1`. Null quando não se
     *   sabe — o que é o caso em runtime no tier local, e faz a feature cair no fallback.
     * @return as 17 features, ou **null** quando há menos de [MIN_TOKENS] posições com
     *   probabilidade. Null é o mesmo `continue` do Python: uma resposta curta demais não
     *   sustenta estatística de distribuição, e inventar zeros alimentaria o modelo com
     *   uma questão que ele nunca viu no treino.
     */
    fun extract(tokens: List<TokenDistribution>, letra: String?): Map<String, Double>? {
        val ps = tokens.mapNotNull { it.prob }
        if (ps.size < MIN_TOKENS) return null

        val ordenado = ps.sorted()

        // Entropia e margem percorrem TODOS os tokens, inclusive os sem `prob` — é o que
        // o Python faz, e alinhar os dois laços mudaria o denominador das médias.
        val entropias = ArrayList<Double>(tokens.size)
        val margens = ArrayList<Double>(tokens.size)
        for (t in tokens) {
            val pv = t.topProbs
            if (pv.size < 2) continue
            val soma = pv.sum()
            if (soma <= 0.0) continue
            val norm = pv.map { it / soma }
            entropias += -norm.sumOf { it * ln(it + EPS) }
            val desc = norm.sortedDescending()
            margens += desc[0] - desc[1]
        }

        val f = LinkedHashMap<String, Double>(FEATURE_NAMES.size)
        f["media"] = ps.average()
        f["mediana"] = percentil(ordenado, 50.0)
        f["minimo"] = ordenado.first()
        f["desvio"] = desvioPopulacional(ps)
        f["p10"] = percentil(ordenado, 10.0)
        f["p25"] = percentil(ordenado, 25.0)
        f["frac_baixa"] = ps.count { it < 0.5 }.toDouble() / ps.size
        f["frac_muito_baixa"] = ps.count { it < 0.2 }.toDouble() / ps.size
        f["n_tokens"] = ps.size.toDouble()
        f["media_ultimos10"] = ps.takeLast(10).average()
        f["media_primeiros10"] = ps.take(10).average()
        f["entropia_media"] = if (entropias.isEmpty()) 0.0 else entropias.average()
        f["entropia_max"] = if (entropias.isEmpty()) 0.0 else entropias.max()
        f["margem_media"] = if (margens.isEmpty()) 0.0 else margens.average()
        f["margem_min"] = if (margens.isEmpty()) 0.0 else margens.min()

        val b1 = confiancaDaLetra(tokens, letra)
        // Fallback EXATO do Python: mediana das probabilidades, e `tem_letra` marcando que
        // o valor é substituto. Um zero aqui seria lido pelo modelo como "letra de
        // probabilidade nula", que é uma afirmação — e falsa.
        f["conf_letra_b1"] = b1 ?: f.getValue("mediana")
        f["tem_letra"] = if (b1 != null) 1.0 else 0.0
        return f
    }

    /**
     * Probabilidade do token que corresponde à letra escolhida, ou null se não houver.
     *
     * Percorre até o fim sem interromper: vence a ÚLTIMA ocorrência. O modelo menciona
     * letras enquanto descarta alternativas ("a alternativa A está errada porque...") e só
     * no fim declara a sua; parar na primeira mediria a confiança de uma que ele rejeitou.
     */
    private fun confiancaDaLetra(tokens: List<TokenDistribution>, letra: String?): Double? {
        if (letra.isNullOrEmpty()) return null
        var achado: Double? = null
        for (t in tokens) {
            val p = t.prob ?: continue
            if (limpa(t.token) == letra) achado = p
        }
        return achado
    }

    /**
     * `(tok or "").strip().strip(":.,)( -–—\n\t*").upper()` — `recalcular_mcnemar_auc.py:220`.
     *
     * São duas limpezas em sequência, e a ordem importa: primeiro espaço em branco, depois
     * a pontuação. O token do modelo costuma vir como `" B"` ou `"B)"`, e sem isso nenhum
     * casaria com a letra.
     */
    private fun limpa(tok: String?): String {
        if (tok == null) return ""
        val semEspaco = tok.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }
        return semEspaco.trim { it in PONTUACAO }.uppercase()
    }

    /**
     * `np.percentile` com interpolação linear (o default).
     *
     * A posição é `q/100 · (n−1)` em índice fracionário; o valor sai da interpolação entre
     * os vizinhos. Arredondar para o índice mais próximo é o erro clássico e dá outro
     * número em quase toda amostra — para n=279 e q=10, o índice é 27,8: o rank-mais-
     * próximo devolveria o 28º, a interpolação devolve 20% do caminho entre o 27º e o 28º.
     *
     * @param ordenado a amostra **já ordenada** de forma crescente.
     */
    private fun percentil(ordenado: List<Double>, q: Double): Double {
        if (ordenado.isEmpty()) return 0.0
        if (ordenado.size == 1) return ordenado[0]
        val pos = (q / 100.0) * (ordenado.size - 1)
        val baixo = kotlin.math.floor(pos).toInt()
        val alto = kotlin.math.ceil(pos).toInt()
        if (baixo == alto) return ordenado[baixo]
        val frac = pos - baixo
        return ordenado[baixo] + (ordenado[alto] - ordenado[baixo]) * frac
    }

    /** Desvio POPULACIONAL (÷N), como `np.std` sem `ddof`. */
    private fun desvioPopulacional(xs: List<Double>): Double {
        val media = xs.average()
        return sqrt(xs.sumOf { val d = it - media; d * d } / xs.size)
    }

    companion object {
        /** Ordem em que [extract] emite. O JSON da política traz a sua própria ordem. */
        val FEATURE_NAMES: List<String> = listOf(
            "media", "mediana", "minimo", "desvio", "p10", "p25",
            "frac_baixa", "frac_muito_baixa", "n_tokens",
            "media_ultimos10", "media_primeiros10",
            "entropia_media", "entropia_max", "margem_media", "margem_min",
            "conf_letra_b1", "tem_letra"
        )

        /** `if len(lps) < 5: continue` no Python. */
        const val MIN_TOKENS = 5

        /** O `+1e-12` dentro do log, que evita `ln(0) = -inf` virar NaN na entropia. */
        private const val EPS = 1e-12

        /** O conjunto de `strip(":.,)( -–—\n\t*")`. */
        private val PONTUACAO: Set<Char> =
            setOf(':', '.', ',', ')', '(', ' ', '-', '–', '—', '\n', '\t', '*')
    }
}
