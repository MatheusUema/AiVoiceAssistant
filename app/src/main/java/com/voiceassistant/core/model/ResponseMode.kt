package com.voiceassistant.core.model

/**
 * **Como** o sistema responde, dentro do que a infraestrutura alcança — o eixo
 * **vertical** da elasticidade pedagógica.
 *
 * ## Cuidado com o nome: `pedagogicalMode` já existe e é OUTRA coisa
 *
 * Este enum chama-se `ResponseMode`, e não `PedagogicalMode`, para **não** colidir com a
 * coluna `pedagogicalMode` da `routing_log`
 * ([com.voiceassistant.core.logging.RoutingLogEntry]), que existe desde a v2 do banco e
 * guarda o [TutorMode] — `EXPLAIN`, `HINT`, `SUMMARY`, `REVIEW`, isto é, o **estilo**
 * pedido pelo aluno, não a forma de entrega decidida pelo sistema.
 *
 * Duas colunas homônimas com sentidos diferentes, uma delas já exportada no CSV da
 * pesquisa, produziriam um erro de análise silencioso — do tipo que não estoura, não loga e
 * só aparece quando alguém cruza as duas tabelas. A coluna antiga **não** foi renomeada
 * justamente porque é dado de pesquisa já coletado; quem renomeou foi o lado novo.
 *
 * ## Dois eixos, e eles são ortogonais
 *
 * Não confundir com [InferenceSource], que é o eixo **horizontal**:
 *
 *  - **Horizontal — o que é alcançável:** local, servidor ou nuvem, conforme a escola
 *    tenha rede e servidor. É [InferenceSource].
 *  - **Vertical — como se responde dentro do alcançável:** responder direto, responder
 *    com ressalva, ou mediar ao professor. É este enum.
 *
 * O eixo vertical existe porque o horizontal pode ter uma casa só. Escalar para servidor
 * ou nuvem pressupõe internet ou um servidor na rede local, e **há o cenário em que a
 * escola não tem nenhum dos dois — só o celular**. É nele que a escolha entre as três
 * formas de resposta é a única elasticidade disponível, e é o cenário para o qual este
 * aplicativo foi desenhado.
 *
 * ## O gatilho, e por que não é a confiança do aplicativo
 *
 * A faixa é decidida pelo score da **cascata recalibrada** (AUC 0,735 fora da dobra,
 * IC [0,731; 0,742]), e **não** pela confiança crua — média da probabilidade do token
 * escolhido —, que no Qwen2.5-1.5B está **invertida**: AUC 0,420, IC [0,360; 0,480],
 * inteiramente abaixo do acaso. Com ela a ressalva subiria preferencialmente nas questões
 * que o modelo **acertou**, o que é pior que não ter ressalva, porque ensina o aluno a
 * desconfiar do sinal. Ver [com.voiceassistant.feature_tutor.policy.ResponseModeResolver].
 *
 * ## Dois níveis de aviso, e por que o geral não mora aqui
 *
 * Este enum é o aviso **por mensagem**. O aviso **geral** sobre resposta de IA — o que
 * fica sempre na tela — é outra coisa, e de propósito: um aviso que aparece **sempre não
 * carrega informação**. Ele habitua, deixa de ser lido e, pior, **compete com a ressalva**:
 * se toda resposta trouxesse "sempre confira", o aluno não distinguiria *esta aqui pode
 * estar errada* do texto permanente. O valor da ressalva vem de ela **não** aparecer
 * sempre.
 *
 * Daí [DIRETO] não ter texto: o modo direto é a **ausência** de ressalva, não uma
 * afirmação de que a resposta está certa. Um selo positivo por mensagem prometeria algo
 * que uma AUC de 0,735 não sustenta.
 *
 * @property mensagem o que a tela diz ao aluno, ou null quando o modo não fala.
 */
enum class ResponseMode(val mensagem: String?) {

    /**
     * Resposta entregue sem ressalva. **Não** é um selo de acerto — é a ausência de
     * bandeira, e é por isso que [mensagem] é null. Ver a nota sobre os dois níveis de
     * aviso no KDoc do enum.
     */
    DIRETO(null),

    /**
     * Resposta entregue **com incerteza explicitada**: o aluno recebe o conteúdo e o
     * convite a confirmar.
     *
     * É a terceira saída do arcabouço, e a única que **não depende de rede nenhuma** —
     * por isso é a que importa no cenário sem conectividade.
     */
    RESSALVA("Esta resposta pode conter erros. Confira com seu professor antes de usar."),

    /**
     * O sistema **deixa de responder diretamente** e devolve a tarefa para revisão
     * humana.
     *
     * A resposta gerada não é destruída — a inferência já foi paga —, mas também não é
     * entregue de imediato: a UI a recolhe atrás de uma revelação explícita. Sem isso a
     * frase abaixo conviveria com a resposta à vista e seria falsa.
     */
    MEDIAR("Não consigo responder a esta pergunta com confiança suficiente. " +
        "Leve-a ao seu professor.");

    /** True quando o modo tem algo a dizer ao aluno — ou seja, tudo menos [DIRETO]. */
    val temMensagem: Boolean get() = mensagem != null

    companion object {
        /**
         * O aviso **geral** sobre resposta de IA: estático, uma vez na tela, nunca por
         * mensagem.
         *
         * Fica aqui, e não numa constante de UI, para que a razão de ele ser separado do
         * aviso por mensagem esteja escrita ao lado dos dois. Ver o KDoc do enum: aviso
         * permanente não informa e competiria com [RESSALVA].
         */
        const val AVISO_GERAL = "Respostas geradas por IA podem conter erros. Confira sempre."
    }
}
