package com.voiceassistant.llama

/** Convenção do projeto: -1 significa "métrica indisponível" (igual a `confidence`). */
const val LLAMA_UNAVAILABLE: Double = -1.0

/**
 * Parâmetros de runtime + sampling de uma sessão llama.cpp.
 *
 * Os defaults de sampling replicam os do `LocalModelConfig` que o MediaPipe usava,
 * para que a troca de runtime não se confunda com troca de sampling na comparação.
 */
data class LlamaParams(
    /** Janela de contexto. Limitada ao `n_ctx_train` do modelo pelo lado nativo. */
    val contextSize: Int = 2048,
    /** 0 = heurística (núcleos - 2, entre 2 e 4). */
    val threads: Int = 0,
    val batchSize: Int = 256,
    val maxTokens: Int = 256,
    val temperature: Float = 0.2f,
    val topK: Int = 20,
    val topP: Float = 0.85f,
    /** 0 = aleatório; qualquer outro valor fixa a seed (reprodutibilidade nos blocos). */
    val seed: Int = 42,

    /**
     * Flash Attention. **Explícito de propósito** — o llama.cpp tem um modo AUTO que
     * decide em runtime, e uma decisão diferente entre aparelhos ou execuções quebraria
     * a comparabilidade exigida pelo protocolo. Ligado por padrão porque é o que o AUTO
     * escolheu nos aparelhos-alvo; desligar é uma condição a ser medida, não um acidente.
     */
    val flashAttention: Boolean = true,

    /**
     * Raciocínio (canal de "pensamento") em modelos que o suportam, como o Gemma 4.
     *
     * **Ligado por padrão**: é o comportamento real do modelo, e o custo de pensar é
     * parte honesta do custo de rodá-lo no aparelho. Desligar é uma *condição
     * experimental* a ser declarada no protocolo, não um default silencioso.
     */
    val enableThinking: Boolean = true,

    /**
     * Tempo máximo de geração antes de desistir. 0 = sem limite.
     *
     * Existe por dois motivos que se somam: um aluno não espera minutos por uma
     * resposta, e uma geração que estoura o limite é **resultado** — mostra que aquele
     * aparelho não sustenta aquele modelo. Cortar também evita que a coleta fique horas
     * presa num caso perdido.
     *
     * Atenção ao calibrar: no Device 1 (o mais rápido) o Gemma 4 E2B levou 74 s numa
     * resposta. Um limite curto demais transforma a bateria inteira em timeouts e
     * destrói os dados; a latência completa fica registrada de qualquer forma, então
     * limiares mais rígidos ("o aluno teria esperado?") podem ser aplicados na análise.
     */
    val generationTimeoutMs: Long = 0L
)

/** Por que a geração terminou — vira coluna do `routing_log`. */
enum class LlamaStopReason {
    /** Terminou sozinha no token de fim: resposta completa. */
    END_OF_GENERATION,

    /** Bateu no teto de `maxTokens`: resposta truncada. */
    MAX_TOKENS,

    /** Estourou [LlamaParams.generationTimeoutMs]: o aparelho não sustenta o modelo. */
    TIMEOUT
}

/** Ficha do modelo carregado — alimenta a tabela `model_load_log` (Fase 3). */
data class LlamaModelInfo(
    val description: String,
    val sizeBytes: Long,
    val contextSize: Int,
    val backends: String
)

/**
 * Métricas de uma geração, direto do `llama_perf_context` + relógio da ponte.
 * Cobre H2 (TTFT, ingestão vs geração) e H3 (tokens/s) do plano de hardware.
 */
data class LlamaStats(
    /** Tempo até o primeiro token, medido do início de `generate` (inclui o prefill). */
    val ttftMs: Double = LLAMA_UNAVAILABLE,
    /** `t_p_eval_ms`: ingestão do prompt. */
    val prefillMs: Double = LLAMA_UNAVAILABLE,
    /** `t_eval_ms`: geração token-a-token. */
    val decodeMs: Double = LLAMA_UNAVAILABLE,
    val totalMs: Double = LLAMA_UNAVAILABLE,
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,

    /**
     * Tokens gastos no canal de raciocínio (Gemma 4). 0 quando o modelo não raciocina.
     * Sem separar isto, comparar tokens/s entre um modelo que raciocina e um que não
     * raciocina mede coisas diferentes.
     */
    val reasoningTokens: Int = 0,

    /** True se o chat template do modelo declara suporte a raciocínio. */
    val supportsThinking: Boolean = false,

    /**
     * True se a geração parou no token de fim; false se bateu no teto de `maxTokens`.
     *
     * Uma resposta truncada é um dado de natureza diferente: mede o teto configurado,
     * não a capacidade do aparelho. Precisa ser filtrável na análise, não invisível.
     */
    val stoppedAtEog: Boolean = false,

    /** True se a geração foi interrompida por timeout. */
    val cancelled: Boolean = false
) {
    /** Por que a geração terminou. */
    val stopReason: LlamaStopReason
        get() = when {
            cancelled -> LlamaStopReason.TIMEOUT
            stoppedAtEog -> LlamaStopReason.END_OF_GENERATION
            else -> LlamaStopReason.MAX_TOKENS
        }

    /** True quando a resposta não saiu inteira (teto de tokens ou timeout). */
    val truncated: Boolean get() = stopReason != LlamaStopReason.END_OF_GENERATION
    val promptTokensPerSec: Double
        get() = if (prefillMs > 0) promptTokens * 1000.0 / prefillMs else LLAMA_UNAVAILABLE

    val generatedTokensPerSec: Double
        get() = if (decodeMs > 0) generatedTokens * 1000.0 / decodeMs else LLAMA_UNAVAILABLE

    /** Tokens que sobraram para a resposta depois do raciocínio. */
    val answerTokens: Int
        get() = (generatedTokens - reasoningTokens).coerceAtLeast(0)

    internal companion object {
        fun fromArray(values: DoubleArray?): LlamaStats {
            if (values == null || values.size < 10) return LlamaStats()
            return LlamaStats(
                ttftMs = values[0],
                prefillMs = values[1],
                decodeMs = values[2],
                totalMs = values[3],
                promptTokens = values[4].toInt(),
                generatedTokens = values[5].toInt(),
                reasoningTokens = values[6].toInt().coerceAtLeast(0),
                supportsThinking = values[7] > 0,
                stoppedAtEog = values[8] > 0,
                cancelled = values[9] > 0
            )
        }
    }
}

/**
 * Resultado de uma geração: a **resposta** (contrato atual) + telemetria em paralelo.
 *
 * [text] já vem sem o canal de raciocínio — é o que se mostra ao aluno. O pensamento
 * fica em [reasoning], disponível para análise mas fora da resposta.
 */
data class LlamaGeneration(
    val text: String,
    val stats: LlamaStats,
    val reasoning: String = ""
)

/**
 * Resultado do carregamento.
 *
 * [Failure] existe porque, na pesquisa, um modelo que não cabe no aparelho é um
 * **resultado a registrar** — não uma exceção a engolir. É o caso esperado do
 * Device 2 (4 GB de RAM).
 */
sealed interface LlamaLoadResult {
    data class Success(val info: LlamaModelInfo, val loadMs: Long) : LlamaLoadResult
    data class Failure(val reason: String, val loadMs: Long) : LlamaLoadResult
}
