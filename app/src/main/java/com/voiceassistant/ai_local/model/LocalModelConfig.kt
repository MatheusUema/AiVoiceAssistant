package com.voiceassistant.ai_local.model

/**
 * Configuração do modelo LLM local executado via MediaPipe LLM Inference.
 *
 * Modelos compatíveis (formato .task / .litertlm do MediaPipe):
 *  - Gemma 3 1B IT INT4     (~900 MB, requer ~2 GB RAM) ← RECOMENDADO
 *  - Gemma 3n E2B           (~1.2 GB, requer ~3 GB RAM)
 *  - Gemma 2 2B IT INT4     (~1.4 GB, requer ~4 GB RAM)
 *
 * Download: https://huggingface.co/litert-community/Gemma3-1B-IT
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  INTEGRAÇÃO:                                                    │
 * │                                                                 │
 * │  1. Baixe o modelo .task do link acima (gemma3-1b-it-int4.task) │
 * │  2. Coloque em: app/src/main/assets/models/                     │
 * │  3. Ajuste [modelAssetPath] se o nome do arquivo for diferente  │
 * │  4. Na primeira execução, o arquivo é copiado de assets para    │
 * │     armazenamento interno (filesDir) automaticamente            │
 * └─────────────────────────────────────────────────────────────────┘
 */
data class LocalModelConfig(
    /**
     * Caminho dentro de `assets/` onde o modelo está no APK (sem o prefixo `assets/`).
     * O [LocalModelManager] copia este ficheiro para `filesDir` na primeira execução.
     *
     * Suporta formatos `.task`, `.litertlm` e `.bin`.
     */
    val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,

    /**
     * Nome do ficheiro em armazenamento interno após cópia (normalmente o último segmento de [modelAssetPath]).
     */
    val modelFileName: String = modelAssetPath.substringAfterLast('/'),

    /**
     * Tamanho total do KV-cache (input + output tokens).
     * Deve ser grande o suficiente para o prompt + resposta gerada.
     */
    val maxTokens: Int = 1024,

    /** Temperatura baixa para respostas factuais e consistentes */
    val temperature: Float = 0.2f,

    /** Top-K reduzido para limitar candidatos e melhorar precisão */
    val topK: Int = 20,

    /** Top-P (nucleus sampling) para controle fino da distribuição */
    val topP: Float = 0.85f,

    /** Seed fixa para reprodutibilidade em testes (0 = aleatório) */
    val randomSeed: Int = 42,

    /**
     * RAM mínima do dispositivo em MB para permitir carregamento do modelo.
     * Gemma 3 1B INT4 precisa de ~2 GB de RAM total no dispositivo.
     */
    val minRamMb: Long = 2048,

    /**
     * Tamanho aproximado do modelo em MB (para verificação de espaço).
     */
    val modelSizeMb: Long = 900,

    /**
     * Se true, executa uma inferência curta após carregar o modelo para
     * pré-aquecer caches internos e reduzir latência na primeira pergunta real.
     */
    val warmupEnabled: Boolean = true,

    /** Prompt usado no warmup — deve ser curto para não desperdiçar tempo */
    val warmupPrompt: String = "Olá"
) {
    companion object {
        /**
         * Caminho padrão relativo a `assets/`:
         * ficheiro físico esperado: `app/src/main/assets/models/gemma3-1b-it-int4.task`
         *
         * Para outro modelo, altera em [ServiceModule.provideLocalModelConfig]
         * ou passa outro [LocalModelConfig.modelAssetPath].
         */
        const val DEFAULT_MODEL_ASSET_PATH: String = "models/gemma3-1b-it-int4.task"
    }
}
