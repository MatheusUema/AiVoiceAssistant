package com.voiceassistant.feature_tutor.policy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * As 18 features baratas do **roteador aprendido** (Fase 1, etapa B), computáveis no
 * aparelho ANTES de inferir: 14 de varredura de string + 4 de área one-hot.
 *
 * Porte literal de `features_baratas()` em `roteamento_fase1.py:185`. A palavra literal
 * é o requisito: o modelo implantado usa os coeficientes ajustados sobre as features do
 * Python, então qualquer divergência aqui não vira "ruído" — vira um modelo alimentado
 * com entrada de outra distribuição, e o erro aparece como queda de desempenho que
 * ninguém consegue atribuir. `StringFeatureParityTest` existe para que isso falhe em
 * teste, não em campo.
 *
 * NÃO há embedding aqui, de propósito (nota do original): o custo de um encoder é
 * justamente o que um pré-filtro deveria evitar. Isto é uma passada de string.
 *
 * ## Sobre qual texto isto opera
 *
 * As features saem do **enunciado cru** — o prompt SEM a instrução do
 * [com.voiceassistant.feature_benchmark.data.EnemPromptBuilder] e SEM o `\nResposta:`
 * final. Ver [enunciadoCru]. Não é detalhe: incluir o cabeçalho somaria ~130 caracteres
 * constantes a todas as questões, achatando `n_chars`, `pal_media_len` e todas as
 * densidades — que são divididas por `n_chars`.
 *
 * ## Paridade com o Python: onde Kotlin e Python discordam por padrão
 *
 * Cada item abaixo é uma divergência real que foi tratada, não uma precaução teórica:
 *
 *  - **`len()`** do Python conta *code points*; `String.length` do Kotlin conta unidades
 *    UTF-16. Divergem em qualquer caractere fora do BMP. Aqui tudo é contado em code
 *    points ([codePoints]).
 *  - **`str.split()`** do Python quebra em corridas de espaço Unicode e descarta vazios;
 *    `split(Regex("\\s+"))` do Kotlin usa `\s` ASCII e produz um vazio inicial quando a
 *    string começa com espaço. Ver [palavras].
 *  - **`str.isdigit()`** do Python é verdadeiro para sobrescritos (`²`, `³`), que
 *    `Character.isDigit` recusa (são OTHER_NUMBER, não DECIMAL_DIGIT_NUMBER) — e o
 *    corpus tem esses caracteres, tanto que [RE_FORMULA] os procura. Ver [isPyDigit].
 *  - **`\b`, `\d` e `\s`** em regex: no Python são Unicode-aware; no Java são ASCII por
 *    padrão. Sem `(?U)`, `\bredação\b` casa onde o Python não casa (o `ç` conta como
 *    não-palavra para o Java). Todos os padrões daqui abrem com `(?U)`.
 *  - **`statistics.pstdev`** é desvio POPULACIONAL (÷N). A fórmula usual (÷N−1) daria
 *    outro número em listas de 5 alternativas. Ver [desvioPopulacional].
 */
@Singleton
class StringFeatureExtractor @Inject constructor() {

    /**
     * Calcula as 18 features.
     *
     * @param cru enunciado já sem o invólucro — ver [enunciadoCru].
     * @param alternativas alternativas na ordem A..E, como o dataset as entrega.
     * @param area "LC" | "CH" | "CN" | "MT". Vazio/desconhecida zera as quatro one-hot.
     *   Em uso real **não existe** área: o valor só está disponível na bateria, onde vem
     *   do metadata do dataset. Ver o caveat em [FEATURE_NAMES].
     */
    fun extract(cru: String, alternativas: List<String>, area: String?): Map<String, Double> {
        val cps = codePoints(cru)
        val nChars = cps.size
        // O Python divide as densidades por `max(1, len(cru))` — sem isso, um enunciado
        // vazio produziria NaN e envenenaria o score sem estourar.
        val denom = maxOf(1, nChars).toDouble()

        val pal = palavras(cru)
        val nPal = pal.size

        val digitos = cps.count { isPyDigit(it) }
        val simbolos = cps.count { MAT.contains(it) }
        val maiusculas = cps.count { Character.isUpperCase(it) }

        // `[len(a.split()) for a in alts] or [0]`: lista vazia vira [0], senão a média
        // dividiria por zero.
        val compAlts = if (alternativas.isEmpty()) {
            listOf(0.0)
        } else {
            alternativas.map { palavras(it).size.toDouble() }
        }

        val altNumericas = alternativas.count { alt ->
            val altCps = codePoints(alt)
            altCps.count { isPyDigit(it) } > 0.25 * maxOf(1, altCps.size)
        }

        val baixo = cru.lowercase()
        val nChaves = KEYS_BOUND.count { it.containsMatchIn(baixo) }

        val f = LinkedHashMap<String, Double>(FEATURE_NAMES.size)
        f["n_palavras"] = nPal.toDouble()
        f["n_chars"] = nChars.toDouble()
        f["dens_digitos"] = digitos / denom
        f["dens_simbolos"] = simbolos / denom
        f["tem_formula"] = if (RE_FORMULA.containsMatchIn(cru)) 1.0 else 0.0
        f["n_interrogacoes"] = contaCodePoint(cps, '?'.code).toDouble()
        f["dens_virgulas"] = contaCodePoint(cps, ','.code) / denom
        f["dens_pontos"] = contaCodePoint(cps, '.'.code) / denom
        f["pal_media_len"] = pal.sumOf { codePoints(it).size }.toDouble() / maxOf(1, nPal)
        f["alt_len_media"] = compAlts.sum() / compAlts.size
        f["alt_len_desvio"] = if (compAlts.size > 1) desvioPopulacional(compAlts) else 0.0
        f["alt_numericas"] = altNumericas.toDouble() / maxOf(1, alternativas.size)
        f["n_chaves"] = nChaves.toDouble()
        f["dens_maiusculas"] = maiusculas / denom
        for (a in AREAS) {
            f["area_$a"] = if (area == a) 1.0 else 0.0
        }
        return f
    }

    // ── Pré-processamento do texto ────────────────────────────────────────────

    /**
     * Remove o invólucro do `EnemPromptBuilder`, devolvendo "o que um aluno digitaria".
     *
     * Porte literal de `enunciado_cru()` (`analise_prefiltro_complexidade.py:85`):
     * ```python
     * t = prompt.replace(CABECALHO, "").strip()
     * return re.sub(r"\n\s*Resposta:\s*$", "", t).strip()
     * ```
     * `replace` do Python troca TODAS as ocorrências — `replace(...)` do Kotlin em String
     * também, então a semântica casa.
     *
     * Recebe o prompt MONTADO em vez de remontar o cru a partir do [EnemQuestion]: a
     * entrada tem que ser bit a bit a mesma que o Python recebeu, e o Python parte do
     * prompt montado. Remontar por outro caminho reintroduziria a chance de divergir num
     * espaço — exatamente o risco que `enem_prompt.py` já paga para evitar.
     *
     * Em uso real (chat) **não se chama isto**: o texto do aluno já é o cru. Passar um
     * texto de chat por aqui é inofensivo (nada casa, nada é removido), mas o caminho
     * correto é chamar [extract] direto.
     */
    fun enunciadoCru(promptMontado: String): String {
        val semCabecalho = promptMontado.replace(CABECALHO, "").trim()
        return RE_RESPOSTA_FINAL.replace(semCabecalho, "").trim()
    }

    // ── Utilitários de paridade ───────────────────────────────────────────────

    /** Code points, e não `Char`: `String.length` conta unidades UTF-16. */
    private fun codePoints(s: String): IntArray {
        if (s.isEmpty()) return IntArray(0)
        val out = IntArray(s.codePointCount(0, s.length))
        var i = 0
        var j = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out[j++] = cp
            i += Character.charCount(cp)
        }
        return out
    }

    private fun contaCodePoint(cps: IntArray, alvo: Int): Int = cps.count { it == alvo }

    /**
     * Equivalente a `str.split()` sem argumento: quebra em corridas de espaço em branco
     * e **descarta vazios** — inclusive o da borda.
     *
     * `Regex("\\s+")` não serve por dois motivos que se somam: `\s` do Java é ASCII
     * (`[ \t\n\x0B\f\r]`), então espaços Unicode não quebrariam palavra; e `split` em
     * string iniciada por espaço devolve um primeiro elemento vazio, inflando `n_palavras`
     * em 1. O `PromptComplexityAnalyzer` atual tem essa segunda forma de bug.
     *
     * O predicado de espaço é `isWhitespace || isSpaceChar` porque o
     * `Py_UNICODE_ISSPACE` do Python considera o espaço não-separável (U+00A0) um espaço,
     * enquanto `Character.isWhitespace` o exclui de propósito.
     */
    private fun palavras(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * `str.isdigit()` do Python: verdadeiro para Numeric_Type ∈ {Decimal, Digit}.
     *
     * `Character.isDigit` cobre só Decimal (categoria Nd). Os dígitos sobrescritos e
     * subscritos são Digit mas não Decimal — o Python os conta, o Java não. Eles OCORREM
     * no corpus: a própria [RE_FORMULA] procura `[⁰-⁹]` e `[₀-₉]`, o que só faz sentido
     * porque aparecem em fórmulas e unidades das questões de CN e MT.
     *
     * Note que `½` (fração vulgar) NÃO entra: para o Python `'½'.isdigit()` é falso
     * (é `isnumeric`, Numeric_Type=Numeric). Por isso a checagem é uma lista explícita, e
     * não `getType == OTHER_NUMBER` — que incluiria as frações e divergiria.
     */
    private fun isPyDigit(cp: Int): Boolean =
        Character.isDigit(cp) || cp in DIGITOS_NAO_DECIMAIS

    /** Desvio POPULACIONAL (÷N), como `statistics.pstdev`. O usual (÷N−1) daria outro valor. */
    private fun desvioPopulacional(xs: List<Double>): Double {
        val media = xs.sum() / xs.size
        val varia = xs.sumOf { val d = it - media; d * d } / xs.size
        return kotlin.math.sqrt(varia)
    }

    companion object {
        /** Ordem canônica de [AREAS] no Python (`roteamento_fase1.py:68`). */
        val AREAS = listOf("LC", "CH", "CN", "MT")

        /**
         * Nomes das 18 features na ordem em que [extract] as emite.
         *
         * **Esta ordem não é contrato com o modelo.** O `roteador-v1.json` traz a sua
         * própria `featureNames`, e [PolicyCoefficients] reordena por nome. O motivo é
         * concreto: o `features-roteamento.csv` do Python sai com as colunas em ordem
         * ALFABÉTICA (`alt_len_desvio` primeiro), enquanto a lista `AREAS` do Python é
         * LC/CH/CN/MT — casar por posição acertaria no CSV e erraria no modelo, ou vice-
         * versa, sem erro visível: o score sairia numérico e errado.
         */
        val FEATURE_NAMES: List<String> = listOf(
            "n_palavras", "n_chars", "dens_digitos", "dens_simbolos", "tem_formula",
            "n_interrogacoes", "dens_virgulas", "dens_pontos", "pal_media_len",
            "alt_len_media", "alt_len_desvio", "alt_numericas", "n_chaves",
            "dens_maiusculas"
        ) + AREAS.map { "area_$it" }

        /**
         * Cabeçalho do `EnemPromptBuilder`, removido por [enunciadoCru].
         *
         * Duplicado aqui de propósito, e não importado de `EnemPromptBuilder.INSTRUCTION`
         * (que é `private`): o que precisa ser removido é a constante que o Python
         * conhece (`CABECALHO`, `analise_prefiltro_complexidade.py:80`). Se um dia a
         * instrução do app mudar, este valor **não deve** acompanhar sozinho — as
         * features passariam a sair de outro texto e o modelo ficaria desalinhado em
         * silêncio. A divergência tem que aparecer no teste de paridade.
         */
        const val CABECALHO =
            "Responda à questão de múltipla escolha abaixo. " +
                "Explique seu raciocínio e termine indicando a alternativa correta " +
                "no formato \"Resposta: X\", onde X é A, B, C, D ou E."

        /**
         * ## Por que estes padrões não usam `(?U)`
         *
         * A flag inline `(?U)` (UNICODE_CHARACTER_CLASS) existe no `java.util.regex` da
         * JVM, mas **o Android usa ICU** e a rejeita em runtime com
         * `PatternSyntaxException: Syntax error in regexp pattern near index 3`. O erro
         * acontece na inicialização da classe, então derruba tudo que a toca — e não
         * aparece em teste unitário de JVM, onde a flag é aceita e funciona.
         *
         * A alternativa portátil é escrever as classes Unicode explicitamente. É mais
         * verboso, mas tem uma vantagem: diz exatamente o que casa, em vez de depender de
         * uma flag cujo significado muda entre engines.
         */

        /** `\w` do Python em modo str: letras, números e sublinhado. */
        private const val W = """[\p{L}\p{N}_]"""

        /**
         * `\b` do Python. Precisa da forma geral (as duas transições) e não só do
         * lookahead negativo, porque na [RE_FORMULA] o `\b` aparece depois de `°` e `%`,
         * que são não-palavra — ali a fronteira depende do caractere SEGUINTE. Simplificar
         * mudaria o que o padrão casa.
         */
        private const val B = """(?:(?<=$W)(?!$W)|(?<!$W)(?=$W))"""

        /** `\s` do Python em modo str: espaço ASCII mais os separadores Unicode. */
        private const val S = """[\s\p{Z}]"""

        /** `re.sub(r"\n\s*Resposta:\s*$", "", t)`. */
        private val RE_RESPOSTA_FINAL = Regex("""\n$S*Resposta:$S*$""")

        /**
         * O conjunto `MAT` do Python — os símbolos matemáticos contados por
         * `dens_simbolos`. Escrito só no literal abaixo de propósito: repeti-lo na
         * documentação introduziria a sequência que encerra um bloco de comentário, e o
         * arquivo deixaria de compilar de um jeito que aponta para a linha errada.
         */
        private val MAT: Set<Int> = "+-*/=^%<>≤≥≠√π∫∑∆·×÷±".map { it.code }.toSet()

        /**
         * `RE_FORMULA` do Python, com `\d` → `\p{Nd}`, `\s` → [S] e `\b` → [B].
         *
         * `\p{Nd}` é exatamente o `\d` do Python em modo str (dígito decimal Unicode) —
         * note que NÃO cobre os sobrescritos, e nem deveria: o padrão os procura à parte,
         * em `[⁰-⁹]`. Quem os conta como dígito é [isPyDigit], que responde por
         * `str.isdigit()`, uma pergunta diferente.
         */
        private val RE_FORMULA = Regex(
            """\p{Nd}$S*[+\-*/=^]$S*\p{Nd}""" +
                """|$B\p{Nd}+$S*(?:m|km|kg|g|s|h|min|°|%)$B""" +
                """|[₀-₉]|[⁰-⁹]|\p{Nd}+/\p{Nd}+|\p{Nd}+,\p{Nd}+"""
        )

        /**
         * `COMPLEX_KEYWORDS` com fronteira de palavra
         * (`KEYS_BOUND`, `roteamento_fase1.py:147`).
         *
         * O conserto que o Python fez foi justamente pôr `\b` em todas — no analisador
         * original só `\btese\b` tinha, então "comparecer" disparava "compare" e
         * "analisem" disparava "analise". Aqui já nascem com fronteira.
         */
        private val KEYS_BOUND: List<Regex> = listOf(
            "explique", "compare", "analise", "demonstre", "prove",
            "diferencie", "calcule", "resolva", "derive", "integre",
            "essay", "redação", "dissertação", """\btese\b""", "argumente",
            "por que", "como funciona", "quais são as causas",
            "qual a diferença entre", "explica detalhadamente"
        ).map { k ->
            // As palavras-chave começam e terminam em letra, então aqui o `\b` reduz aos
            // dois lookarounds negativos — mais simples e equivalente. `\btese\b` já vem
            // com as fronteiras no Python; tira-se para não duplicá-las.
            val nu = k.removePrefix("""\b""").removeSuffix("""\b""")
            Regex("""(?<!$W)$nu(?!$W)""")
        }

        /**
         * Numeric_Type=Digit que não são Decimal — o Python conta como dígito, o Java não.
         * Sobrescritos e subscritos, que aparecem em fórmulas de CN e MT.
         */
        private val DIGITOS_NAO_DECIMAIS: Set<Int> = buildSet {
            add('²'.code); add('³'.code); add('¹'.code)          // U+00B2, U+00B3, U+00B9
            addAll(('⁰'..'⁹').map { it.code })          // ⁰-⁹ sobrescritos
            addAll(('₀'..'₉').map { it.code })          // ₀-₉ subscritos
        }
    }
}
