package com.voiceassistant.feature_tutor.policy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * A alternativa (A–E) que o **modelo cravou**, lida do texto da resposta.
 *
 * Porte literal de `sugestao()` em `gerar_lotes_classificacao.py:66`, o mesmo extrator que
 * preenche a coluna `sugestao_auto` das planilhas de classificação.
 *
 * ## O que esta letra é, e o que NÃO é
 *
 * É a resposta do **modelo**, não o gabarito. A confusão custaria caro: `conf_letra_b1`,
 * a feature da cascata que depende disto, mede *a confiança com que o modelo disse o que
 * disse* — se fosse alimentada com o gabarito, mediria outra coisa e vazaria o rótulo
 * para dentro da feature. Verificado nos dados de treino: `manual_answer` coincide com
 * `expected_answer` em 57,33% das 389, que é exatamente a acurácia do modelo. Fosse o
 * gabarito, seriam 100%.
 *
 * ## Não é um palpite
 *
 * Ou o padrão explícito que o próprio prompt pede (`"Resposta: X"`) está no texto, ou o
 * resultado é null. Nunca se escolhe "a alternativa mais mencionada" ou coisa parecida —
 * o modelo percorre e descarta alternativas antes de concluir, e uma heurística sobre isso
 * produz um número plausível e errado (medido: 2 de 4 respostas do Qwen foram atribuídas a
 * alternativas que ele tinha acabado de descartar).
 *
 * ## A ÚLTIMA ocorrência, não a primeira
 *
 * Pelo mesmo motivo: o modelo às vezes escreve "Resposta: B" e depois se corrige. A última
 * é a que ele sustenta.
 *
 * ## Concordância com a leitura humana
 *
 * 99,74% nas 389 do `qwen2.5-1.5b` (388/389) e 99,86% no conjunto de todos os tiers
 * (`rigor-passo1.md` §3). No holdout de 80: 98,75% (79/80). A única divergência ali é a
 * `questao_152/2022/MT`, onde o modelo se corrigiu de um jeito que o padrão não capta.
 *
 * **A consequência para a cascata**: no treino a letra veio da classificação MANUAL; em
 * runtime vem daqui. Então `conf_letra_b1` em produção é igual à do treino em ~99% dos
 * casos, e nos demais cai no fallback — que é o MESMO caminho que o treino já usava
 * quando não havia letra (mediana, `tem_letra=0`). O viés é conhecido, pequeno e
 * declarado, e não um efeito silencioso.
 */
@Singleton
class AnswerLetterExtractor @Inject constructor() {

    /** @return a letra em maiúscula, ou null quando o padrão não aparece no texto. */
    fun extract(texto: String?): String? {
        if (texto.isNullOrEmpty()) return null
        // `findAll` + `last`: a última ocorrência vence.
        return PADRAO.findAll(texto).lastOrNull()
            ?.groupValues?.get(1)?.uppercase()
    }

    private companion object {
        /** `\w` do Python, para reconstruir o `\b` final sem depender de flag. */
        private const val W = """[\p{L}\p{N}_]"""

        /** `\s` do Python: espaço ASCII mais os separadores Unicode. */
        private const val S = """[\s\p{Z}]"""

        /**
         * `[Rr]esposta\s*[:\-–]?\s*\**\s*\(?([A-E])\)?\b` do Python.
         *
         * Aceita `:`, `-` ou `–` como separador e os `**` de negrito do Markdown, porque o
         * modelo formata de várias formas. O `\b` final é escrito como lookahead negativo
         * sobre a classe de palavra: o `\b` do Java é ASCII e o Android **rejeita a flag
         * `(?U)`** que o corrigiria (`PatternSyntaxException` em runtime, não em teste de
         * JVM). Aqui a fronteira impede casar o "A" de "Alternativa" logo depois da letra.
         */
        val PADRAO = Regex("""[Rr]esposta$S*[:\-–]?$S*\**$S*\(?([A-E])\)?(?!$W)""")
    }
}
