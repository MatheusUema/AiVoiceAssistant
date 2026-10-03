package com.voiceassistant.core.model

/**
 * Encapsula uma requisição de inferência enviada ao InferenceRouter.
 * Contém o prompt e metadados que influenciam a decisão de roteamento
 * e a construção do prompt final.
 */
data class InferenceRequest(
    val prompt: String,
    val sessionId: String,
    /** Histórico recente da conversa para fornecer contexto ao modelo */
    val conversationHistory: List<ChatMessage> = emptyList(),
    /** Estimativa de complexidade da pergunta (calculada pelo PromptComplexityAnalyzer) */
    val complexity: PromptComplexity = PromptComplexity.SIMPLE,
    /** Modo pedagógico que define o estilo da resposta */
    val tutorMode: TutorMode = TutorMode.EXPLAIN,

    /**
     * Envia [prompt] ao modelo **exatamente como está**, sem o enquadramento do
     * TutorPromptBuilder.
     *
     * Existe para a bateria de medição: as questões do ENEM já vêm com instrução e
     * alternativas no formato do artigo 1, e embrulhá-las em instrução pedagógica mudaria
     * o prompt — e portanto a acurácia e o custo de prefill — tornando os números
     * incomparáveis com os medidos no computador.
     */
    val rawPrompt: Boolean = false,

    /** Bloco de medição a que esta inferência pertence (H5). Null fora do modo teste. */
    val blockId: String? = null,

    /** k-ésima repetição desta questão dentro do bloco. Null fora do modo teste. */
    val runIndex: Int? = null,

    /**
     * Id da questão no dataset (`questao_87`). Null fora do modo teste.
     *
     * **Não é único sozinho**: no `maritaca_enem_irt.csv` são 540 linhas para 180 ids —
     * cada id se repete nos três anos. A chave é `(questionId, questionYear)`, e as
     * análises do artigo 1 já tropeçaram nisso (ver `_analise_debias.py`, que compara a
     * contagem pela "chave ANTIGA, com bug" com a correta).
     */
    val questionId: String? = null,

    /** Ano da questão — a outra metade da chave. Ver [questionId]. */
    val questionYear: Int? = null,

    /** Área (LC/CH/CN/MT), para a análise por área sem precisar juntar com o dataset. */
    val questionArea: String? = null,

    /**
     * Alternativas da questão, quando ela é de múltipla escolha.
     *
     * O **roteador aprendido** precisa delas: quatro das suas dezoito features descrevem
     * as alternativas (comprimento médio, desvio, fração de numéricas). Extraí-las de
     * volta do prompt montado por regex seria frágil — os enunciados têm parênteses e
     * quebras de linha —, então viajam ao lado dele.
     *
     * Vazia no chat, onde não há alternativas: as features saem 0 e o modelo pontua com o
     * que tem. Isso é uma diferença real entre a bateria e o uso, e entra como ressalva na
     * análise em vez de ser dissimulada.
     */
    val alternatives: List<String> = emptyList(),

    /**
     * Gabarito (A–E) da questão, quando ela tem um. Null fora do modo teste.
     *
     * É só um rótulo carregado junto: o roteador não decide nada com ele, apenas o
     * repassa ao log para que a linha nasça com o eixo de acurácia. Sem isso, casar
     * resposta com gabarito depois exigiria reidentificar a questão pelo texto — que é
     * frágil e se perde quando a amostra muda.
     */
    val expectedAnswer: String? = null,

    /**
     * Pede que o roteador derive o [ResponseMode] desta resposta — o eixo vertical da
     * elasticidade, que a interface do Bloco D parte 1 exibe.
     *
     * **Default false, e isso é deliberado.** Quem liga é o caminho de **chat**; a bateria
     * de medição não liga, e por isso não paga a aritmética extra nem muda uma linha do
     * `routing_log`. O eixo experimental do Bloco A (qual `RoutingPolicy` decide o
     * roteamento) fica intacto: derivar o modo pedagógico **não** é rotear, é descrever a
     * resposta que já saiu.
     *
     * Um flag explícito, e não a reutilização de [rawPrompt] ou de [blockId] como
     * sinalizador de "é a bateria": aqueles dois significam outra coisa, e sobrecarregá-los
     * esconderia esta decisão atrás de um efeito colateral.
     */
    val deriveResponseMode: Boolean = false
)

enum class PromptComplexity {
    SIMPLE,
    MODERATE,
    COMPLEX
}
