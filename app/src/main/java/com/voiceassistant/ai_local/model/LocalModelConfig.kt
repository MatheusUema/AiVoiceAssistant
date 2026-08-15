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

    /**
     * Máximo de tokens gerados por resposta (`n_predict`).
     *
     * 1024 e não 512: medido no Device 1, o Gemma 4 gastou 422 tokens só raciocinando
     * numa pergunta trivial e ainda estourou o teto de 512 sem chegar à resposta. Com
     * teto curto a bateria mede truncamento, não desempenho. **Precisa de calibração**
     * na Fase 4 contra o comprimento real das questões do ENEM.
     */
    val maxTokens: Int = 1024,

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
    val warmupPrompt: String = "Olá",

    /**
     * Raciocínio ligado em modelos que o suportam (Gemma 4).
     *
     * Ligado é o default porque é o comportamento real do modelo — o custo de pensar
     * faz parte do custo de rodá-lo no aparelho, que é o que o estudo mede. Desligar é
     * uma condição experimental a declarar no protocolo, e a comparação de tokens/s
     * com o Qwen só fecha usando `reasoningTokens`/`answerTokens` separados.
     */
    val enableThinking: Boolean = true,

    /**
     * Tempo máximo de geração local antes de desistir (ms). 0 = sem limite.
     *
     * Uma geração que estoura isto é **resultado**, não erro: significa que o aparelho
     * não sustenta aquele modelo em uso real. Também evita que a coleta fique horas
     * presa num caso perdido nos aparelhos mais fracos.
     *
     * 180 s é deliberadamente folgado. No Device 1 — o mais rápido dos três — o Gemma 4
     * E2B levou 74 s numa única resposta; um limite curto transformaria a bateria inteira
     * em timeouts e destruiria os dados. Como a latência completa fica registrada,
     * limiares mais rígidos ("o aluno teria esperado 20 s?") podem ser aplicados depois,
     * na análise, sem precisar recoletar.
     */
    val generationTimeoutMs: Long = 180_000L,

    /**
     * Orçamento de tempo quando **há** para onde escalar (servidor na LAN ou nuvem).
     *
     * Bem mais curto que [generationTimeoutMs] porque as latências somam: esperar o
     * timeout longo e só então chamar a nuvem entrega uma resposta pior que não ter
     * fallback nenhum. Aqui desistir cedo é a decisão certa — existe alternativa.
     *
     * Offline ou em modo privacidade não há alternativa, e aí vale a paciência do
     * [generationTimeoutMs]: uma resposta lenta ainda é melhor que nenhuma.
     */
    val generationTimeoutWithFallbackMs: Long = 30_000L
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

        /**
         * Segundo modelo da matriz de testes: **Qwen2.5 1.5B instruct Q4_K_M** (~1,1 GB).
         * A matriz é SEQUENCIAL — bateria completa com o Gemma nos 3 aparelhos, depois
         * bateria completa com este. Nunca os dois ao mesmo tempo.
         */
        val QWEN_1_5B_Q4_K_M = LocalModelVariant(
            assetPath = "models/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1120,
            minRamMb = 2048,
            label = "qwen2.5-1.5b-instruct-q4_k_m"
        )

        /**
         * Opção leve do Qwen — e o modelo usado para validar a ponte JNI sem depender
         * de RAM (por isso também serve de smoke test).
         */
        val QWEN_0_5B_Q4_K_M = LocalModelVariant(
            assetPath = "models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 470,
            minRamMb = 1024,
            label = "qwen2.5-0.5b-instruct-q4_k_m"
        )

        /** Alias histórico — o modelo de smoke test é o Qwen 0.5B. */
        val SMOKE_TEST_TINY = QWEN_0_5B_Q4_K_M

        /**
         * Catálogo de modelos locais, indexado pela chave usada em
         * `-Plocal.model=<chave>` (ver `app/build.gradle.kts`).
         *
         * Trocar a bateria da matriz é escolher outra chave no build — sem editar Kotlin.
         */
        val CATALOG: Map<String, LocalModelVariant> = mapOf(
            "gemma4-e2b" to GEMMA_4_E2B_Q4_K_M,
            "gemma3-1b" to GEMMA_3_1B_Q4_K_M,
            "qwen-1.5b" to QWEN_1_5B_Q4_K_M,
            "qwen-0.5b" to QWEN_0_5B_Q4_K_M
        )

        /**
         * Resolve o valor de `-Plocal.model` para uma variante. Aceita duas formas:
         *
         *  - uma **chave** do [CATALOG] (`gemma4-e2b`, `qwen-1.5b`, …)
         *  - o **nome de um arquivo** `.gguf` qualquer
         *
         * A segunda existe para que testar uma LLM nova não exija editar Kotlin: basta
         * empurrar o arquivo para o aparelho e recompilar apontando para ele. A matriz
         * do estudo deve crescer em modelos e em aparelhos, e o catálogo não pode ser
         * um gargalo para isso.
         *
         * Chave desconhecida (nem catálogo, nem `.gguf`) cai no modelo oficial em vez de
         * estourar: um typo não deve derrubar o app no meio de uma coleta.
         */
        fun variantOf(key: String): LocalModelVariant {
            val normalized = key.trim()
            CATALOG[normalized.lowercase()]?.let { return it }

            if (normalized.endsWith(GGUF_EXTENSION, ignoreCase = true)) {
                return LocalModelVariant(
                    assetPath = "models/$normalized",
                    // 0 em ambos: o tamanho real só se sabe com o arquivo em mãos, e um
                    // gate chutado esconderia a falha de carga que o estudo quer medir.
                    sizeMb = 0,
                    minRamMb = 0,
                    label = normalized.substringBeforeLast('.').lowercase()
                )
            }

            return GEMMA_4_E2B_Q4_K_M
        }

        private const val GGUF_EXTENSION = ".gguf"

        const val DEFAULT_MODEL_ASSET_PATH: String = "models/gemma-4-E2B-it-Q4_K_M.gguf"
    }
}
