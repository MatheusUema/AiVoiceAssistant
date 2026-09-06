package com.voiceassistant.ai_cloud.service

/**
 * Contrato de domínio para inferência via API cloud.
 *
 * A camada de domínio (use cases, InferenceRouter) depende apenas desta interface.
 * A implementação concreta (Firebase, OpenAI, etc.) é injetada via Hilt.
 */
interface CloudInferenceService {

    /**
     * Gera uma resposta para o [prompt].
     *
     * O prompt já vem formatado pelo [TutorPromptBuilder] com
     * instrução de sistema + histórico + pergunta do usuário.
     *
     * DEVOLVE UM RESULTADO ESTRUTURADO, e nao so a String. Ate 2026-09-05 a assinatura
     * era `suspend fun generate(prompt: String): String`, e o `usageMetadata` da resposta
     * -- que traz as contagens de tokens -- era descartado DENTRO da implementacao, sem
     * por onde trafegar. O efeito era que toda linha do tier cloud na `routing_log` saia
     * com prefill, decode e tokens em -1: o tier respondia, mas nao era mensuravel.
     * Corrigir isso exigia mudar a interface, nao so o ponto de chamada.
     *
     * @throws CloudInferenceException em caso de erro de rede, API ou conteúdo bloqueado.
     */
    suspend fun generate(prompt: String): CloudResult

    /**
     * True se o backend cloud está configurado no app (ex.: Firebase inicializado via
     * `google-services.json`). Não exige que o modelo remoto já tenha sido instanciado;
     * falhas na primeira chamada são reportadas por [generate].
     * O InferenceRouter consulta isto antes de rotear para o cloud.
     */
    val isAvailable: Boolean
}

/**
 * Resultado de uma geração no tier cloud, com o que a API informa sobre o custo.
 *
 * NAO HA CONFIANCA AQUI, e a ausencia e deliberada: o SDK do Firebase AI Logic nao expoe
 * logprobs por token. O `InferenceRouter` registra `confidenceMethod = "none"` para este
 * tier, e nao um -1 silencioso que pareceria uma medicao falha. Obter confianca exigiria
 * sair do SDK e usar a API crua do Gemini (`responseLogprobs`), o que muda autenticacao.
 */
data class CloudResult(
    val text: String,
    val latencyMs: Long,
    /** `usageMetadata.promptTokenCount` — tokens do prompt cobrados. */
    val promptTokens: Int = UNAVAILABLE,
    /** `usageMetadata.candidatesTokenCount` — tokens gerados. */
    val generatedTokens: Int = UNAVAILABLE,
    /**
     * Tokens de raciocinio interno, quando o modelo os separa (familia Gemini 3).
     * Entram no total cobrado mas nao aparecem no texto — sem esta coluna, o custo por
     * questao ficaria subestimado justamente nos modelos que mais raciocinam.
     */
    val reasoningTokens: Int = UNAVAILABLE,
    /** Modelo que de fato respondeu, como a API o identifica. */
    val modelId: String? = null,
    val truncated: Boolean = false
) {
    /** Soma cobrada pela API: prompt + saida + raciocinio. */
    val totalTokens: Int
        get() = listOf(promptTokens, generatedTokens, reasoningTokens)
            .filter { it >= 0 }.sum()

    companion object {
        const val UNAVAILABLE = -1
    }
}

/**
 * Exceção tipada para falhas na inferência cloud.
 * O [reason] indica a categoria do erro para tratamento diferenciado.
 */
class CloudInferenceException(
    message: String,
    val reason: CloudErrorReason = CloudErrorReason.UNKNOWN,
    cause: Throwable? = null
) : Exception(message, cause)

enum class CloudErrorReason {
    /** Sem conexão de rede */
    NETWORK,
    /** Resposta bloqueada pelo filtro de segurança do modelo */
    SAFETY_BLOCKED,
    /** API retornou resposta vazia */
    EMPTY_RESPONSE,
    /** Credenciais inválidas ou google-services.json ausente */
    AUTH_ERROR,
    /** Cota de API excedida */
    QUOTA_EXCEEDED,
    /** Erro desconhecido */
    UNKNOWN
}
