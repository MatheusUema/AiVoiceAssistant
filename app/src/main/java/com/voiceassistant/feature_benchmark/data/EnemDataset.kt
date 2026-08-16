package com.voiceassistant.feature_benchmark.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carrega o conjunto ENEM de `assets/datasets/maritaca_enem_irt.csv`.
 *
 * O parser é escrito à mão e segue o RFC 4180 porque os enunciados contêm vírgulas,
 * aspas e **quebras de linha dentro dos campos** — um `split(',')` produziria questões
 * truncadas silenciosamente, o que apareceria depois como queda de acurácia atribuída
 * ao aparelho.
 */
@Singleton
class EnemDataset @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile
    private var cached: List<EnemQuestion>? = null

    /** Todas as questões do arquivo, na ordem original. */
    fun all(): List<EnemQuestion> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    /**
     * Amostra balanceada por área e estratificada por dificuldade.
     *
     * Balancear por área importa porque as quatro áreas têm perfis muito diferentes de
     * comprimento de enunciado — e o comprimento domina o custo de prefill, que é o que
     * o estudo mede. Uma amostra enviesada para matemática (enunciados curtos) faria o
     * aparelho parecer mais rápido do que é.
     *
     * @param perArea quantas questões por área.
     * @param seed fixa a amostra: a mesma seleção em todos os aparelhos e modelos, senão
     *   a comparação entre eles mistura diferença de hardware com diferença de itens.
     */
    fun balancedSample(perArea: Int, seed: Long = DEFAULT_SEED): List<EnemQuestion> {
        val eligible = all().filterNot { it.needsImageButHasNoDescription }
        return eligible.groupBy { it.area }
            .toSortedMap()
            .flatMap { (_, questions) ->
                questions
                    .sortedBy { it.difficultyScore }
                    .stratifiedSample(perArea, seed)
            }
    }

    /**
     * Divide a lista (já ordenada por dificuldade) em [count] faixas e sorteia uma de
     * cada. Assim a amostra cobre o espectro de dificuldade em vez de se concentrar
     * numa ponta, o que enviesaria a acurácia.
     */
    private fun List<EnemQuestion>.stratifiedSample(count: Int, seed: Long): List<EnemQuestion> {
        if (isEmpty() || count <= 0) return emptyList()
        if (size <= count) return this

        val random = kotlin.random.Random(seed)
        val bucketSize = size.toDouble() / count
        return (0 until count).map { i ->
            val start = (i * bucketSize).toInt()
            val end = (((i + 1) * bucketSize).toInt()).coerceAtMost(size)
            val bucket = subList(start, end.coerceAtLeast(start + 1))
            bucket[random.nextInt(bucket.size)]
        }
    }

    private fun load(): List<EnemQuestion> = try {
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
            val rows = parseCsv(reader.readText())
            if (rows.isEmpty()) return emptyList()

            val header = rows.first().withIndex().associate { (i, name) -> name.trim() to i }
            rows.drop(1).mapNotNull { row -> toQuestion(row, header) }
                .also { Log.i(TAG, "Conjunto ENEM carregado: ${it.size} questões") }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao carregar $ASSET_PATH: ${e.message}", e)
        emptyList()
    }

    private fun toQuestion(row: List<String>, header: Map<String, Int>): EnemQuestion? {
        fun field(name: String): String? =
            header[name]?.let { row.getOrNull(it) }?.takeIf { it.isNotBlank() }

        val id = field("id") ?: return null
        val statement = field("question") ?: return null
        val label = field("label")?.trim()?.uppercase() ?: return null
        if (label !in EnemQuestion.LETTERS) return null

        return EnemQuestion(
            id = id,
            year = field("ano")?.toIntOrNull() ?: 0,
            area = field("area")?.trim().orEmpty(),
            statement = statement,
            alternatives = parseAlternatives(field("alternatives").orEmpty()),
            label = label,
            difficultyScore = field("difficulty_score")?.toDoubleOrNull() ?: 0.0,
            description = field("description")
        ).takeIf { it.alternatives.size == EnemQuestion.LETTERS.size }
    }

    /**
     * A coluna `alternatives` guarda uma lista em notação JSON dentro do CSV, ex.:
     * `["revolta com a falta de sorte.", "gosto pela prática da leitura.", ...]`.
     * Interpretada aqui sem trazer um parser JSON só para isso.
     */
    private fun parseAlternatives(raw: String): List<String> {
        val inner = raw.trim().removeSurrounding("[", "]")
        if (inner.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '"' && inQuotes && i + 1 < inner.length && inner[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out += current.toString().trim(); current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    /** Parser CSV RFC 4180: aspas duplicadas escapam aspas; campos podem conter `\n`. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    row += field.toString(); field.clear()
                }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    // \r\n conta como uma quebra só.
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any { it.isNotBlank() }) rows += row
        }
        return rows
    }

    companion object {
        private const val TAG = "EnemDataset"
        const val ASSET_PATH = "datasets/maritaca_enem_irt.csv"

        /** Seed fixa: a mesma amostra em todos os aparelhos e modelos. */
        const val DEFAULT_SEED = 42L
    }
}
