# Modelos locais (GGUF)

Os pesos **não** são versionados no git (centenas de MB). Baixe e coloque os arquivos
aqui antes de compilar — o `LocalModelManager` os copia para `filesDir` na primeira
execução, porque o llama.cpp precisa de um caminho real no filesystem.

O runtime local é o llama.cpp (módulo `:llama`). Formato: **GGUF**, quantização
**Q4_K_M**. Os `.task`/`.litertlm` do MediaPipe não servem mais.

| Papel | Arquivo esperado | Tamanho | Onde é usado |
|---|---|---|---|
| Primário | `gemma-2-2b-it-Q4_K_M.gguf` | ~1,6 GB | os 3 aparelhos do estudo |
| Fallback | `gemma-3-1b-it-Q4_K_M.gguf` | ~0,8 GB | só quando o primário não carrega (Device 2, 4 GB) |
| Smoke test | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | ~400 MB | validar a ponte JNI sem depender de RAM |

Os nomes vêm de `LocalModelConfig` (`GEMMA_2B_Q4_K_M`, `GEMMA_1B_Q4_K_M`,
`SMOKE_TEST_TINY`). Se o arquivo baixado tiver outro nome, ajuste lá — ou renomeie.

## Validar o build primeiro

Para conferir que a ponte JNI compila e gera texto antes de mexer com modelos grandes,
troque o `primary` em `ServiceModule.provideLocalModelConfig()` por
`LocalModelConfig.SMOKE_TEST_TINY` e use o Qwen 0.5B.

## Por que o fallback existe

O Device 2 tem 4 GB de RAM. Se o modelo primário não carregar lá, isso é um
**resultado do estudo** (limite de elasticidade do aparelho), registrado em
`LocalModelManager.loadAttempts` com o motivo — e só então o modelo menor entra.
