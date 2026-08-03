package com.voiceassistant.ai_local.model

/**
 * Um modelo local candidato (arquivo GGUF + requisitos de hardware).
 *
 * Existem dois no estudo: o [LocalModelConfig.primary] (mesmo modelo nos 3 aparelhos,
 * para a comparação ser justa) e um [LocalModelConfig.fallback] menor, acionado quando
 * o primário não carrega — caso esperado no Device 2 (4 GB de RAM).
 */
data class LocalModelVariant(
    /** Caminho dentro de `assets/` (sem o prefixo `assets/`). */
    val assetPath: String,
    /** Nome do arquivo em `filesDir` após a cópia. */
    val fileName: String = assetPath.substringAfterLast('/'),
    /** Tamanho aproximado em MB — usado na checagem de espaço em disco (H7). */
    val sizeMb: Long,
    /**
     * RAM total mínima do aparelho (MB) para sequer TENTAR carregar.
     * Deliberadamente folgado: queremos que o Device 2 tente e a falha seja medida,
     * não que o gate a esconda.
     */
    val minRamMb: Long,
    /** Rótulo curto para logs e para a coluna `modelId` da `routing_log`. */
    val label: String = fileName.substringBeforeLast('.')
)

/**
 * Configuração do modelo LLM local executado via llama.cpp (módulo `:llama`).
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  INTEGRAÇÃO:                                                            │
 * │  1. Baixe o GGUF Q4_K_M do modelo escolhido                             │
 * │  2. Coloque em: app/src/main/assets/models/                             │
 * │  3. Ajuste [primary] se o nome do arquivo for diferente                 │
 * │  4. Na 1ª execução o arquivo é copiado de assets para filesDir          │
 * │     (llama.cpp precisa de um path real — não lê de assets)              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * O APK não deve versionar o GGUF (centenas de MB). Ver `assets/models/README.md`.
 */
data class LocalModelConfig(
    /** Modelo oficial do estudo — o mesmo nos 3 aparelhos. */
    val primary: LocalModelVariant = GEMMA_4_E2B_Q4_K_M,

    /**
     * Modelo menor usado se o [primary] não carregar. Null desliga o fallback.
     * O uso do fallback é REGISTRADO — é resultado de pesquisa (limite do aparelho),
     * não uma degradação silenciosa.
     */
    val fallback: LocalModelVariant? = GEMMA_3_1B_Q4_K_M,

    /**
     * Se a RAM total do aparelho for menor que isto (MB), pula direto para o [fallback]
     * sem tentar o primário. 0 = desligado: tenta sempre o primário primeiro, para que
     * a falha do Device 2 seja *medida* em vez de presumida.
     */
    val fallbackRamThresholdMb: Long = 0,

    /** Janela de contexto (KV-cache) em tokens. Limitada ao `n_ctx_train` do modelo. */
    val contextSize: Int = 2048,

    /** Threads de inferência. 0 = heurística (núcleos - 2, entre 2 e 4). */
    val threads: Int = 0,

    /** Tamanho do batch de prefill. */
    val batchSize: Int = 256,

    /** Máximo de tokens gerados por resposta (`n_predict`). */
    val maxTokens: Int = 512,

    /** Temperatura baixa para respostas factuais e consistentes */
    val temperature: Float = 0.2f,

    /** Top-K reduzido para limitar candidatos e melhorar precisão */
    val topK: Int = 20,

    /** Top-P (nucleus sampling) para controle fino da distribuição */
    val topP: Float = 0.85f,

    /** Seed fixa para reprodutibilidade nos blocos de medição (0 = aleatório) */
    val randomSeed: Int = 42,

    /**
     * Se true, executa uma inferência curta após carregar o modelo para
     * pré-aquecer caches internos e reduzir latência na primeira pergunta real (H6).
     */
    val warmupEnabled: Boolean = true,

    /** Prompt usado no warmup — deve ser curto para não desperdiçar tempo */
    val warmupPrompt: String = "Olá"
) {
    // ── Compatibilidade: o resto do app (InferenceRouter, DeviceCapabilityChecker)
    //    continua falando do "modelo atual" sem conhecer o par primário/fallback.
    val modelAssetPath: String get() = primary.assetPath
    val modelFileName: String get() = primary.fileName
    val modelSizeMb: Long get() = primary.sizeMb
    val minRamMb: Long get() = primary.minRamMb

    /** Variantes na ordem em que devem ser tentadas. */
    fun loadOrder(totalRamMb: Long): List<LocalModelVariant> {
        val skipPrimary = fallbackRamThresholdMb > 0 &&
                totalRamMb in 1 until fallbackRamThresholdMb &&
                fallback != null
        return when {
            skipPrimary -> listOfNotNull(fallback)
            else -> listOfNotNull(primary, fallback)
        }
    }

    companion object {
        /**
         * Modelo oficial do estudo: **Gemma 4 E2B instruct, Q4_K_M (3,43 GB)**.
         * `lmstudio-community/gemma-4-E2B-it-GGUF` → `gemma-4-E2B-it-Q4_K_M.gguf`.
         * 5B parâmetros totais / 2B efetivos. O mesmo modelo nos 3 aparelhos.
         *
         * `minRamMb` folgado de propósito: o Device 2 (4 GB, ~3,6 GB reportados) DEVE
         * chegar a tentar o carregamento para que a falha vire dado — 3,43 GB de pesos
         * mais o KV-cache quase certamente não cabem lá, e é isso que queremos medir.
         */
        val GEMMA_4_E2B_Q4_K_M = LocalModelVariant(
            assetPath = "models/gemma-4-E2B-it-Q4_K_M.gguf",
            sizeMb = 3430,
            minRamMb = 3000,
            label = "gemma-4-e2b-it-q4_k_m"
        )

        /**
         * Fallback para aparelhos onde o E2B não carrega (Device 2). ~0,8 GB.
         * Vem da família Gemma 3 porque o E2B é o **menor** Gemma 4 que existe.
         */
        val GEMMA_3_1B_Q4_K_M = LocalModelVariant(
            assetPath = "models/gemma-3-1b-it-Q4_K_M.gguf",
            sizeMb = 810,
            minRamMb = 1800,
            label = "gemma-3-1b-it-q4_k_m"
        )

        /** Modelo minúsculo para validar o build da ponte JNI sem depender de RAM. */
        val SMOKE_TEST_TINY = LocalModelVariant(
            assetPath = "models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 400,
            minRamMb = 1024,
            label = "qwen2.5-0.5b-instruct-q4_k_m"
        )

        const val DEFAULT_MODEL_ASSET_PATH: String = "models/gemma-4-E2B-it-Q4_K_M.gguf"
    }
}
