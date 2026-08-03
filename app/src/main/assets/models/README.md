# Modelos locais (GGUF)

O runtime local é o llama.cpp (módulo `:llama`). Formato **GGUF**, quantização
**Q4_K_M**. Os `.task`/`.litertlm` do MediaPipe não servem mais.

Os pesos **não** são versionados no git.

| Papel | Arquivo | Tamanho | Onde é usado |
|---|---|---|---|
| Primário | `gemma-4-E2B-it-Q4_K_M.gguf` | 3,43 GB | os 3 aparelhos do estudo |
| Fallback | `gemma-3-1b-it-Q4_K_M.gguf` | ~0,8 GB | só quando o primário não carrega (Device 2, 4 GB) |
| Smoke test | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | ~400 MB | validar a ponte JNI sem depender de RAM |

Fonte do primário: [`lmstudio-community/gemma-4-E2B-it-GGUF`](https://huggingface.co/lmstudio-community/gemma-4-E2B-it-GGUF)
(5B parâmetros totais / 2B efetivos). O `mmproj-*.gguf` de visão **não** é necessário.

Os nomes vêm de `LocalModelConfig` (`GEMMA_4_E2B_Q4_K_M`, `GEMMA_3_1B_Q4_K_M`,
`SMOKE_TEST_TINY`). Se o arquivo baixado tiver outro nome, ajuste lá — ou renomeie.

## Como colocar o modelo no aparelho

**Não ponha o E2B nesta pasta.** 3,43 GB em `assets/` viram ~7 GB no aparelho (APK +
cópia para `filesDir`), o que é inviável nos aparelhos de 64 GB. Use `adb push`:

```powershell
.\scripts\push-model.ps1 -ModelPath C:\models\gemma-4-E2B-it-Q4_K_M.gguf
```

Isso deposita o arquivo em `/sdcard/Android/data/<pkg>/files/models/`, de onde o
llama.cpp faz `mmap` direto — sem cópia e sem permissão de armazenamento.

O `LocalModelManager` procura nesta ordem: `filesDir` → `externalFilesDir/models`
(o `adb push`) → `assets/models` (copiado para `filesDir`). A pasta `assets/` só faz
sentido para o modelo de smoke test.

## Validar o build primeiro

Para conferir que a ponte JNI compila e gera texto antes de mexer com 3,43 GB, troque o
`primary` em `ServiceModule.provideLocalModelConfig()` por `LocalModelConfig.SMOKE_TEST_TINY`
e use o Qwen 0.5B.

## Por que o fallback existe

O Device 2 tem 4 GB de RAM; 3,43 GB de pesos mais o KV-cache quase certamente não cabem.
Se o primário não carregar lá, isso é um **resultado do estudo** (limite de elasticidade
do aparelho), registrado em `LocalModelManager.loadAttempts` com o motivo — e só então o
modelo menor entra. O fallback vem do Gemma 3 porque o E2B é o **menor** Gemma 4 que existe.
