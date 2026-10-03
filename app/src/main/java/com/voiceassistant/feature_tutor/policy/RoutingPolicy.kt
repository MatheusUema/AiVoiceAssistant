package com.voiceassistant.feature_tutor.policy

/**
 * Qual política decide o roteamento — o eixo experimental do Bloco A.
 *
 * As políticas se dividem por **quando** decidem, e isso é o que separa as duas metades
 * da Fase 1:
 *
 *  - **Antes de inferir** ([HEURISTIC], [LEARNED], [PER_AREA]): decidem com o que dá para
 *    computar do enunciado, sem gastar o modelo. Baratas, mas cegas ao que o modelo faria.
 *  - **Depois de inferir** ([CASCADE]): já pagaram a inferência local e usam a
 *    distribuição de logprobs para decidir se vale reperguntar a um modelo maior. Mais
 *    informadas, mas o custo do local é irrecuperável.
 *
 * A comparação entre elas só é honesta contra os pisos e tetos, por isso [ALWAYS_LOCAL] e
 * [ALWAYS_CLOUD] são políticas de primeira classe e não casos de teste.
 */
enum class RoutingPolicy {
    /**
     * O comportamento de hoje: `PromptComplexityAnalyzer` classifica e COMPLEX vai para a
     * nuvem (regra 5). É o **baseline** contra o qual as outras se justificam — e a Fase 1
     * mostrou que, sem conserto, o pré-filtro é degenerado.
     */
    HEURISTIC,

    /**
     * Regressão logística sobre 18 features de string, ANTES de inferir
     * (`roteador-v1.json`). Score alto = o local provavelmente acerta; escala-se quem fica
     * abaixo do corte.
     */
    LEARNED,

    /**
     * Cascata recalibrada: infere no local e decide pela distribuição de logprobs
     * (`cascata-v1.json`), DEPOIS de inferir. Não usa a confiança crua — é justamente a
     * recalibração que a Fase 1 mostrou bater a média.
     */
    CASCADE,

    /**
     * As duas em série: o roteador aprendido evita a inferência local nos casos que já
     * parecem perdidos; quem passa por ele e é inferido ainda pode escalar pela cascata.
     * É a política com mais chance de dominar o Pareto, e a mais cara de explicar.
     */
    LEARNED_THEN_CASCADE,

    /**
     * Decide só pela área da questão, usando o que a Fase 1 mediu por área. Existe como
     * **controle**: se ela empatar com [LEARNED], as 14 features de string não estão
     * pagando o próprio custo e o roteador aprendido é elaboração desnecessária.
     *
     * Só é aplicável onde a área é conhecida — no benchmark ela vem do metadata do
     * dataset. Em uso real não existe rótulo de área, e é por isso que esta política é um
     * instrumento de medida, não um candidato a implantação.
     */
    PER_AREA,

    /** Piso: nunca escala. Todo o custo no aparelho, toda a qualidade do modelo pequeno. */
    ALWAYS_LOCAL,

    /** Teto: sempre escala. Melhor qualidade alcançável, custo e privacidade no pior caso. */
    ALWAYS_CLOUD,

    /**
     * Oráculo por dificuldade TRI — o teto de comparação da Fase 1 (AUC 0,68).
     *
     * **Não é implantável e não deve ser executada no aparelho**: precisa do
     * `difficulty_score` do dataset, que só existe porque as questões já foram calibradas
     * por TRI sobre milhares de respondentes. Está aqui para que a análise possa citá-la
     * como limite superior sem que alguém a confunda com uma opção real — o
     * [InferenceRouter] a trata como [HEURISTIC] se chegar até ele.
     */
    ORACLE_IRT;

    /** True quando a política precisa de um score computado ANTES de inferir. */
    val needsPreScore: Boolean
        get() = this == LEARNED || this == LEARNED_THEN_CASCADE

    /** True quando a política decide escalar DEPOIS da inferência local. */
    val needsCascade: Boolean
        get() = this == CASCADE || this == LEARNED_THEN_CASCADE

    /** True quando a decisão não olha para a questão — os pisos e tetos. */
    val isBaseline: Boolean
        get() = this == ALWAYS_LOCAL || this == ALWAYS_CLOUD
}

/**
 * Configuração da política em runtime.
 *
 * @param policy qual política decide.
 * @param budgetFraction fração das questões que se pretende ESCALAR, em [0,1]. É o que
 *   torna as políticas comparáveis em custo: um limiar fixo de 0,5 produziria frações de
 *   escalonamento diferentes por aparelho e por conjunto, e o Pareto custo×qualidade
 *   deixaria de ser traçável. O corte sai dos quantis do score no treino, que o JSON já
 *   traz — ver [PolicyModel.corteParaOrcamento]. Null usa a mediana do treino.
 */
data class RoutingPolicyConfig(
    val policy: RoutingPolicy = RoutingPolicy.HEURISTIC,
    val budgetFraction: Double? = null
) {
    companion object {
        /** O comportamento de hoje. É o default em todo lugar que não pede outra coisa. */
        val DEFAULT = RoutingPolicyConfig()
    }
}
