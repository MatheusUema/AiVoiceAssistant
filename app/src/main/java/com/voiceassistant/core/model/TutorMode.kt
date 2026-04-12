package com.voiceassistant.core.model

/**
 * Modos pedagógicos que determinam como o tutor responde.
 *
 * Cada modo produz um prompt com instruções diferentes, adaptando:
 *  - O tom da resposta (direto, socrático, analítico)
 *  - O nível de detalhe (curto para local, rico para cloud)
 *  - O objetivo didático (ensinar, guiar, revisar)
 *
 * @property label Nome exibido na UI (pt-BR)
 * @property icon Nome do Material Icon (referência para o Composable)
 */
enum class TutorMode(val label: String, val icon: String) {
    /**
     * Explicação direta e didática do conceito.
     * Local: resposta concisa com definição + 1 exemplo.
     * Cloud: resposta completa com analogias, exemplos e contexto.
     */
    EXPLAIN("Explicar", "menu_book"),

    /**
     * Dica sem entregar a resposta — abordagem socrática.
     * Local: 1-2 perguntas guia ou pista curta.
     * Cloud: sequência de dicas progressivas com perguntas reflexivas.
     */
    HINT("Dica", "lightbulb"),

    /**
     * Resumo estruturado do conteúdo.
     * Local: 2-3 bullet points com o essencial.
     * Cloud: resumo com tópicos, subtópicos e relações entre conceitos.
     */
    SUMMARY("Resumir", "summarize"),

    /**
     * Revisão e correção do que o aluno escreveu.
     * Local: aponta erros principais com correção breve.
     * Cloud: análise detalhada com erros, acertos, sugestões e nota qualitativa.
     */
    REVIEW("Revisar", "rate_review")
}
