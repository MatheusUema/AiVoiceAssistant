package com.voiceassistant.feature_tutor.policy

import com.voiceassistant.core.model.ResponseMode
import javax.inject.Inject

/**
 * Traduz o score da **cascata recalibrada** em um [ResponseMode] — o eixo vertical da
 * elasticidade (Bloco D parte 1).
 *
 * Função pura, sem Android e sem estado: o score entra, a faixa sai. É o que permite
 * testá-la em JVM, como `RoutingPolicyTest` já faz com [InferenceRouter.resolveRoute].
 *
 * ## Por que a cascata, e não a confiança do aplicativo
 *
 * A confiança crua do aplicativo — média da probabilidade do token escolhido — **não pode**
 * dirigir esta decisão, e isso vem de medição, não de preferência. No Qwen2.5-1.5B ela dá
 * AUC 0,420, IC [0,360; 0,480], **inteiramente abaixo do acaso**: a bandeira subiria
 * preferencialmente nas questões que o modelo acertou. Além disso o gatilho que a usa
 * **nunca dispara** — na coleta com os três níveis habilitados foram 12 de 12 no servidor,
 * com confiança mínima de 0,8013 contra um limiar de 0,30, 2,7× acima dele.
 *
 * A cascata recalibrada, sobre 17 estatísticas da distribuição de logprobs, dá AUC 0,735
 * fora da dobra, IC [0,731; 0,742]. É o sinal que torna a ressalva informativa.
 *
 * ## Os cortes são por ORÇAMENTO, e isso tem uma consequência que precisa ser dita
 *
 * Os dois cortes **não** são constantes deste arquivo: saem dos quantis do treino que o
 * próprio asset carrega, via [PolicyModel.corteParaOrcamento]. O corte de [DIRETO] é o
 * quantil 0,5 (mediana do treino, ≈0,634) e o de [MEDIAR] é o quantil 0,1 (≈0,285). Se o
 * asset for retreinado, os cortes acompanham sem edição de código.
 *
 * **A consequência, e ela não pode ser dissimulada na figura:** sob corte por orçamento,
 * uma **fração fixa** das perguntas cai em [ResponseMode.MEDIAR] — por construção, cerca
 * de um décimo —, *independentemente de o dia ter sido bom ou ruim*. O corte é um quantil
 * da distribuição de treino, não um juízo absoluto sobre a resposta. Portanto isto **não**
 * pode ser apresentado como "o sistema desiste quando a resposta é ruim": ele reserva uma
 * fatia do orçamento para mediação e manda para lá as perguntas de **menor score relativo**.
 * O que a AUC de 0,735 sustenta é que essa ordenação é informativa — não que exista um
 * limiar absoluto de qualidade.
 *
 * ## Ausência de sinal não é um modo
 *
 * Sem asset, sem `limiar` no asset, ou com score indisponível, o resolvedor devolve
 * **null** — e a UI não mostra bandeira nenhuma. Nunca um modo default: [ResponseMode]
 * é uma afirmação sobre a resposta, e um default inventaria uma faixa que não foi
 * calibrada. É a mesma degradação graciosa de [PolicyCoefficients], onde um app sem os
 * assets continua funcional, só sem roteamento aprendido.
 */
class ResponseModeResolver @Inject constructor() {

    /**
     * @param score score da cascata em [0,1]; [InferenceRouter.PRESCORE_UNAVAILABLE] (-1)
     *   quando o tier não expôs a distribuição de logprobs.
     * @param modelo a política `cascata-v1` carregada dos assets, ou null se ausente.
     * @return a faixa, ou **null** quando não há sinal em que se apoiar.
     */
    fun resolve(score: Float, modelo: PolicyModel?): ResponseMode? {
        if (modelo == null) return null
        // Score fora de [0,1] não é faixa: cobre a sentinela -1 e qualquer NaN que
        // escapasse do extrator. `!(score in 0f..1f)` em vez de `<0 || >1` porque NaN
        // falha as duas comparações e passaria.
        if (score !in 0f..1f) return null

        // Sem `limiar` o `corteParaOrcamento` devolve 0,5 para qualquer fração pedida —
        // e aí os dois cortes colapsam no mesmo número, a faixa da ressalva fica vazia e
        // 0,5 não é corte treinado nenhum. Melhor não classificar do que classificar com
        // um corte inventado.
        if (modelo.limiar == null) return null

        val corteDireto = modelo.corteParaOrcamento(FRACAO_DIRETO).toFloat()
        val corteMediar = modelo.corteParaOrcamento(FRACAO_MEDIAR).toFloat()

        // Os quantis exportados têm que estar ordenados para que as três faixas existam.
        // Se o asset trouxer um conjunto de quantis que não separa os dois cortes, isto é
        // asset malformado — e a resposta certa é não classificar, não escolher um dos
        // dois arbitrariamente.
        if (corteMediar >= corteDireto) return null

        return when {
            score >= corteDireto -> ResponseMode.DIRETO
            score >= corteMediar -> ResponseMode.RESSALVA
            else -> ResponseMode.MEDIAR
        }
    }

    companion object {
        /**
         * Fração de orçamento que separa [ResponseMode.DIRETO] do resto — o quantil
         * 0,5, isto é, a mediana dos scores do treino (≈0,634 na `cascata-v1`).
         */
        const val FRACAO_DIRETO = 0.5

        /**
         * Fração reservada a [ResponseMode.MEDIAR] — o quantil 0,1 (≈0,285 na
         * `cascata-v1`). Mediação é **rara por construção**, e é isto que a torna legível
         * quando acontece.
         */
        const val FRACAO_MEDIAR = 0.1
    }
}
