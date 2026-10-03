package com.voiceassistant.feature_tutor.policy

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Coeficientes de uma política logística treinada offline (Fase 1), lidos de
 * `assets/policies/<id>.json`.
 *
 * O modelo do Python é `StandardScaler` + `LogisticRegression`, então pontuar são duas
 * contas: padronizar com a média/escala do treino e aplicar
 * `sigmoid(intercept + Σ coefᵢ·zᵢ)`. O lado Python já verificou que esta fórmula
 * reproduz o `predict_proba` do sklearn a 1e-16 — aqui é só somar e multiplicar.
 *
 * ## O score aponta para cima
 *
 * `score alto = maior probabilidade de o modelo local ACERTAR`. Portanto **escala-se quem
 * fica ABAIXO do corte**. É o contrário do que a intuição de "score = risco" sugere, e
 * inverter o sinal produziria uma política que escala exatamente as questões que o local
 * acertaria — pior que não rotear. O campo [uso] carrega essa frase do próprio JSON.
 *
 * ## Casar por NOME, nunca por posição
 *
 * O `features` do JSON é a ordem dos vetores [mean]/[scale]/[coef]. O extrator Kotlin tem
 * a sua própria ordem de emissão, e o `fixture-features-80.csv` tem uma terceira (com
 * prefixos `rot_`/`cas_`). Três ordens plausíveis para o mesmo conjunto: um casamento
 * posicional acertaria numa e produziria, nas outras, um score **numérico, plausível e
 * errado** — sem exceção, sem log, sem sintoma até alguém comparar com o Python.
 */
@Serializable
data class PolicyModel(
    val nome: String,
    val versao: String,
    /** O que a probabilidade prevê, em palavras. */
    val alvo: String = "",
    /** Nomes das features **na ordem** de [scaler] e [modelo]. */
    val features: List<String>,
    val scaler: Scaler,
    val modelo: Modelo,
    /** A fórmula e o sentido do score, como o Python os declarou. */
    val uso: String = "",
    val limiar: Limiar? = null,
    /** Hash do dataset de treino — casa com o `split`. */
    @Suppress("PropertyName") val dataset_hash: String = "",
    /** n do TREINO (309); as outras 80 são o holdout nunca treinado. */
    @Suppress("PropertyName") val treino_n: Int = 0,
    val split: String = "",
    val observacao: String = ""
) {
    @Serializable
    data class Scaler(val mean: List<Double>, val scale: List<Double>)

    @Serializable
    data class Modelo(
        val tipo: String = "",
        @Suppress("PropertyName") val C: Double = 1.0,
        val coef: List<Double>,
        val intercept: Double
    )

    /**
     * Cortes por ORÇAMENTO, e não um limiar fixo.
     *
     * Para escalar uma fração `f` das questões, usa-se o quantil `f` da distribuição de
     * scores do treino. É o que torna a política comparável em custo: fixar 0,5 daria
     * frações de escalonamento diferentes por aparelho e por conjunto, e o Pareto
     * custo×qualidade deixaria de ser traçável.
     */
    @Serializable
    data class Limiar(
        @Suppress("PropertyName") val referencia_mediana_treino: Double = 0.5,
        val esquema: String = "",
        @Suppress("PropertyName") val quantis_treino: Map<String, Double> = emptyMap()
    )

    /** Identificador estável para log e para a coluna `policyName` da `routing_log`. */
    val id: String get() = "$nome-$versao"

    /**
     * Reordena o mapa do extrator para a ordem deste modelo.
     *
     * Feature ausente é **erro fatal**, não zero: um zero seria padronizado para
     * `(0 − mean)/scale`, que costuma ser um valor extremo, e o score sairia
     * confiantemente errado. Melhor não pontuar do que pontuar mal.
     */
    fun vector(valores: Map<String, Double>): DoubleArray {
        val faltando = features.filterNot { it in valores }
        check(faltando.isEmpty()) {
            "política '$id': features ausentes ${faltando.take(5)} " +
                "(esperava ${features.size}, extrator deu ${valores.size})"
        }
        return DoubleArray(features.size) { valores.getValue(features[it]) }
    }

    /** Probabilidade em [0,1]. Alto = o local provavelmente acerta. */
    fun score(valores: Map<String, Double>): Double {
        val x = vector(valores)
        var z = modelo.intercept
        for (i in x.indices) {
            // `scale_` == 0 acontece em feature constante no treino; o sklearn a troca por
            // 1 para não dividir por zero. Repetir aqui, senão vira NaN silencioso.
            val s = if (scaler.scale[i] == 0.0) 1.0 else scaler.scale[i]
            z += modelo.coef[i] * ((x[i] - scaler.mean[i]) / s)
        }
        return 1.0 / (1.0 + exp(-z))
    }

    /**
     * Corte para escalar a fração [fracao] das questões, pelos quantis do treino.
     * Cai na mediana do treino quando o quantil pedido não foi exportado.
     */
    fun corteParaOrcamento(fracao: Double): Double {
        val l = limiar ?: return 0.5
        val chave = l.quantis_treino.keys.minByOrNull {
            val v = it.toDoubleOrNull() ?: return@minByOrNull Double.MAX_VALUE
            kotlin.math.abs(v - fracao)
        }
        return chave?.let { l.quantis_treino[it] } ?: l.referencia_mediana_treino
    }

    /** Coerência interna do asset — chamada na carga, para falhar cedo. */
    fun validate() {
        val n = features.size
        check(n > 0) { "política '$id': sem features" }
        check(modelo.coef.size == n && scaler.mean.size == n && scaler.scale.size == n) {
            "política '$id': tamanhos divergem — features=$n coef=${modelo.coef.size} " +
                "mean=${scaler.mean.size} scale=${scaler.scale.size}"
        }
        check(features.toSet().size == n) { "política '$id': nomes de feature repetidos" }
    }
}

/**
 * Carrega e cacheia as políticas dos assets.
 *
 * Ausência do asset **não** é exceção: devolve null e o roteador segue na heurística de
 * hoje — a mesma degradação graciosa que `runServer` já aplica quando a confiança vem
 * indisponível. Um build sem os assets continua sendo um app funcional; o que ele não faz
 * é rotear pela política.
 */
@Singleton
class PolicyCoefficients @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, PolicyModel?>()

    /** @param policyId nome do arquivo sem extensão, ex.: `roteador-v1`. */
    @Synchronized
    fun load(policyId: String): PolicyModel? = cache.getOrPut(policyId) {
        runCatching {
            val texto = context.assets.open("$ASSET_DIR/$policyId.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<PolicyModel>(texto).also { m ->
                m.validate()
                Log.i(
                    TAG,
                    "Política '${m.id}' carregada: ${m.features.size} features, " +
                        "treino n=${m.treino_n}, dataset=${m.dataset_hash}"
                )
            }
        }.getOrElse { erro ->
            // Distinguir "não existe" de "existe e está quebrado": o primeiro é o estado
            // normal antes de os assets chegarem; o segundo é um asset ruim, que grita.
            if (erro is java.io.FileNotFoundException) {
                Log.i(TAG, "Política '$policyId' ausente — roteamento segue na heurística")
            } else {
                Log.e(TAG, "Política '$policyId' inválida: ${erro.message}", erro)
            }
            null
        }
    }

    private companion object {
        const val TAG = "PolicyCoefficients"
        const val ASSET_DIR = "policies"
    }
}
