package com.voiceassistant.ai_cloud.model

/**
 * Configuração do modelo cloud via Firebase AI Logic (Gemini).
 *
 * Estes parâmetros são injetados no [FirebaseCloudInferenceService] via Hilt
 * e podem ser alterados em runtime sem recompilar.
 */
data class CloudModelConfig(
    /**
     * Nome do modelo Gemini.
     * Opções comuns:
     *  - "gemini-2.0-flash-lite" → leve, quota gratuita disponível (recomendado)
     *  - "gemini-2.0-flash"      → mais capaz, mas pode exigir billing
     *  - "gemini-1.5-flash"      → geração anterior, quota gratuita estável
     *  - "gemini-1.5-pro"        → melhor qualidade, mais lento
     */
    // MODELO: confirmado na lista que o Firebase AI Logic expoe para o backend
    // `googleAI` (Gemini Developer API) em 2026-09. NAO existe um `gemini-3-flash`
    // estavel; a familia suportada e gemini-3.7-flash / 3.6-flash / 3.5-flash /
    // 3.5-flash-lite / 3.1-flash-lite. Escolhido o 3.7-flash por ser o Flash de uso
    // geral mais recente da lista.
    //
    // FREE TIER: modelos Flash e Flash-Lite NAO exigem o plano Blaze quando usados pela
    // Gemini Developer API. Pro, imagem e TTS exigem billing -- por isso o tier cloud
    // deste estudo fica na familia Flash.
    //
    // Trocar de modelo e uma linha; a comparabilidade do estudo depende de declarar qual
    // foi usado, e o `modelId` viaja para a `routing_log` em cada linha.
    val modelName: String = "gemini-3.7-flash",

    /** Máximo de tokens na resposta gerada */
    val maxOutputTokens: Int = 1024,

    /** Temperatura (0.0 = determinístico, 1.0 = criativo) */
    // ALINHADO aos tiers local e servidor (0.2), para que a comparacao meça o modelo e
    // nao o regime de amostragem.
    val temperature: Float = 0.2f,

    /** Nucleus sampling — filtra tokens cuja probabilidade acumulada ultrapassa topP */
    // topP alinhado ao dos outros tiers (0.85).
    //
    // DIFERENCA INEVITAVEL, e declarada: o `generationConfig` do SDK Firebase nao expoe
    // `topK` da mesma forma que o llama.cpp, e a familia Gemini 3 faz raciocinio interno
    // com orcamento proprio de tokens. Ou seja, os tres tiers ficam alinhados em
    // temperatura e topP, mas NAO sao o mesmo amostrador. Isso limita o que se pode
    // atribuir ao modelo numa comparacao cloud x local, e precisa constar da analise.
    val topP: Float = 0.85f,

    /**
     * Nível de bloqueio de conteúdo sensível.
     *  - NONE          → desabilita filtros (não recomendado para educação)
     *  - ONLY_HIGH     → bloqueia apenas conteúdo claramente nocivo
     *  - MEDIUM_AND_ABOVE → bloqueio moderado (padrão para apps educacionais)
     *  - LOW_AND_ABOVE → mais restritivo
     */
    val safetyBlockThreshold: SafetyBlockThreshold = SafetyBlockThreshold.MEDIUM_AND_ABOVE,

    /**
     * Backend da API Gemini:
     *  - GOOGLE_AI   → Gemini Developer API (plano Spark, sem billing obrigatório)
     *  - VERTEX_AI   → Vertex AI Gemini API (requer billing / plano Blaze)
     */
    val backend: CloudBackend = CloudBackend.GOOGLE_AI
)

/**
 * Mapeamento para HarmBlockThreshold do Firebase AI SDK.
 * Mantém o domain model desacoplado da API do Firebase.
 */
enum class SafetyBlockThreshold {
    NONE,
    ONLY_HIGH,
    MEDIUM_AND_ABOVE,
    LOW_AND_ABOVE
}

enum class CloudBackend {
    GOOGLE_AI,
    VERTEX_AI
}
