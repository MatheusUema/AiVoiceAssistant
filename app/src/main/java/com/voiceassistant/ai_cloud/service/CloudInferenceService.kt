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
     * @throws CloudInferenceException em caso de erro de rede, API ou conteúdo bloqueado.
     */
    suspend fun generate(prompt: String): String

    /**
     * True se o backend cloud está configurado no app (ex.: Firebase inicializado via
     * `google-services.json`). Não exige que o modelo remoto já tenha sido instanciado;
     * falhas na primeira chamada são reportadas por [generate].
     * O InferenceRouter consulta isto antes de rotear para o cloud.
     */
    val isAvailable: Boolean
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
