package com.voiceassistant.feature_tutor.prompt

import com.voiceassistant.core.model.ChatMessage
import com.voiceassistant.core.model.MessageRole
import com.voiceassistant.core.model.TutorMode
import javax.inject.Inject

/**
 * Constrói prompts formatados para o tutor educacional, adaptados ao
 * [TutorMode] selecionado e ao destino da inferência (local vs cloud).
 *
 * **Estratégia de prompt por destino:**
 *
 *  - **compact = true (local)**: instruções curtas, histórico limitado,
 *    diretivas de resposta concisa. Modelos on-device (Gemma 3 1B) têm janela
 *    de contexto pequena e geram melhor com prompts objetivos.
 *
 *  - **compact = false (cloud)**: instruções ricas, histórico completo,
 *    encorajamento de exemplos, analogias e formatação. Gemini pode
 *    aproveitar o contexto extra para respostas de alta qualidade.
 *
 * O prompt é texto plano único — funciona com APIs que recebem string
 * (MediaPipe) e com APIs que aceitam texto livre (Firebase AI Logic).
 */
class TutorPromptBuilder @Inject constructor() {

    /**
     * Monta o prompt completo: instrução de sistema + histórico + pergunta.
     *
     * @param userInput Texto do aluno
     * @param history Mensagens recentes para contexto
     * @param mode Modo pedagógico selecionado
     * @param compact true para inferência local (prompts curtos)
     * @param maxHistory Limite de mensagens de histórico (override automático por compact)
     */
    fun build(
        userInput: String,
        history: List<ChatMessage> = emptyList(),
        mode: TutorMode = TutorMode.EXPLAIN,
        compact: Boolean = false,
        maxHistory: Int = if (compact) MAX_HISTORY_LOCAL else MAX_HISTORY_CLOUD
    ): String = buildString {
        val systemInstruction = if (compact)
            compactSystemInstruction(mode)
        else
            fullSystemInstruction(mode)

        appendLine(systemInstruction)
        appendLine()

        val recentHistory = history.takeLast(maxHistory)
        if (recentHistory.isNotEmpty()) {
            appendLine("--- Histórico ---")
            recentHistory.forEach { message ->
                val role = when (message.role) {
                    MessageRole.USER -> "Aluno"
                    MessageRole.ASSISTANT -> "Tutor"
                }
                appendLine("$role: ${message.content}")
            }
            appendLine("---")
            appendLine()
        }

        appendLine("Aluno: $userInput")
        append("Tutor:")
    }

    // ── Instruções compactas (local / on-device) ──────────────────────────

    private fun compactSystemInstruction(mode: TutorMode): String {
        val base = "Você é um tutor educacional. Responda em português brasileiro, de forma concisa e factual."
        val modeDirective = when (mode) {
            TutorMode.EXPLAIN ->
                "Explique o conceito com uma definição clara e um exemplo curto. Responda em no máximo 4 frases."

            TutorMode.HINT ->
                "Dê apenas uma dica curta ou pergunta-guia. Não revele a resposta. Responda em no máximo 2 frases."

            TutorMode.SUMMARY ->
                "Resuma em 2-3 pontos essenciais com marcadores. Seja breve."

            TutorMode.REVIEW ->
                "Aponte os erros principais e dê a correção breve. Responda em no máximo 4 frases."
        }
        return "$base\nResponda apenas a última pergunta do aluno. Ignore o histórico se não for relevante.\n$modeDirective"
    }

    // ── Instruções completas (cloud / Gemini) ─────────────────────────────

    private fun fullSystemInstruction(mode: TutorMode): String {
        val modeBlock = when (mode) {
            TutorMode.EXPLAIN -> """
                |Modo: EXPLICAÇÃO
                |- Explique o conceito de forma clara e progressiva
                |- Use analogias do cotidiano para facilitar a compreensão
                |- Inclua pelo menos um exemplo prático
                |- Se o tema for amplo, estruture a resposta com subtópicos
                |- Adapte a profundidade ao nível aparente da pergunta
            """.trimMargin()

            TutorMode.HINT -> """
                |Modo: DICA (abordagem socrática)
                |- NÃO revele a resposta diretamente
                |- Faça perguntas que guiem o raciocínio do aluno
                |- Ofereça uma pista de cada vez, do mais genérico ao mais específico
                |- Se o aluno insistir, dê uma dica mais direta, mas nunca a resposta completa
                |- Encoraje o aluno a pensar por conta própria
            """.trimMargin()

            TutorMode.SUMMARY -> """
                |Modo: RESUMO
                |- Organize o conteúdo em tópicos com marcadores
                |- Destaque os conceitos-chave em negrito se possível
                |- Relacione conceitos entre si quando pertinente
                |- Mantenha o resumo completo mas sem redundância
                |- Inclua uma conclusão de 1 frase no final
            """.trimMargin()

            TutorMode.REVIEW -> """
                |Modo: REVISÃO
                |- Analise o que o aluno escreveu identificando acertos e erros
                |- Para cada erro, explique por que está errado e dê a correção
                |- Destaque também os pontos positivos para motivar
                |- Sugira melhorias específicas e acionáveis
                |- Dê uma avaliação qualitativa geral ao final (bom, precisa melhorar, etc.)
            """.trimMargin()
        }

        return """
            |Você é um tutor educacional inteligente e empático, especializado em ajudar estudantes de todos os níveis de ensino no Brasil.
            |
            |Regras gerais:
            |- Responda em português brasileiro natural e acessível
            |- Baseie respostas em fatos verificados e conteúdo educacional confiável
            |- Seja encorajador e positivo, motivando o aprendizado
            |- Se não souber a resposta com certeza, diga que não sabe
            |- Formate a resposta com marcadores quando listar itens
            |
            |$modeBlock
        """.trimMargin()
    }

    companion object {
        const val MAX_HISTORY_LOCAL = 2
        const val MAX_HISTORY_CLOUD = 10
    }
}
