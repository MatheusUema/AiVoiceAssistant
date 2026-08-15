# Modelos locais (GGUF)

O runtime local é o llama.cpp (módulo `:llama`). Formato **GGUF**, quantização
**Q4_K_M**. Os `.task`/`.litertlm` do MediaPipe não servem mais.

Os pesos **não** são versionados no git.

| Chave de build | Arquivo | Tamanho | Papel |
|---|---|---|---|
| `gemma4-e2b` | `gemma-4-E2B-it-Q4_K_M.gguf` | 3,43 GB | **bateria 1** — modelo oficial, os 3 aparelhos |
| `qwen-1.5b` | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | ~1,1 GB | **bateria 2** — segundo modelo da matriz |
| `gemma3-1b` | `gemma-3-1b-it-Q4_K_M.gguf` | ~0,8 GB | fallback do Gemma quando o E2B não carrega (Device 2) |
| `qwen-0.5b` | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | ~470 MB | opção leve do Qwen; também é o smoke test da ponte JNI |

Fonte do primário: [`lmstudio-community/gemma-4-E2B-it-GGUF`](https://huggingface.co/lmstudio-community/gemma-4-E2B-it-GGUF)
(5B parâmetros totais / 2B efetivos). O `mmproj-*.gguf` de visão **não** é necessário.

## Trocar o modelo ativo (matriz sequencial)

A matriz de testes roda **uma bateria completa por modelo**, nunca os dois ao mesmo tempo.
Trocar de bateria é recompilar com outra chave — não se edita Kotlin entre rodadas:

```bash
./gradlew :app:assembleDebug -Plocal.model=gemma4-e2b   # bateria 1 (default)
./gradlew :app:assembleDebug -Plocal.model=qwen-1.5b    # bateria 2
```

Chaves em `LocalModelConfig.CATALOG`: `gemma4-e2b`, `gemma3-1b`, `qwen-1.5b`, `qwen-0.5b`.

**Para uma LLM que não está no catálogo**, passe o nome do arquivo direto — não é
preciso editar Kotlin:

```bash
.\scripts\push-model.ps1 -ModelPath C:\models\phi-4-mini-Q4_K_M.gguf
./gradlew :app:assembleDebug -Plocal.model=phi-4-mini-Q4_K_M.gguf
```

Só vale a pena virar entrada no `CATALOG` um modelo que entra na matriz de verdade
(para ter rótulo estável no `routing_log` e requisitos de RAM declarados).
O fallback sai em `-Plocal.model.fallback` (`none` desliga). Exemplos:

```bash
# só o E2B, sem rede de segurança — para medir a falha de carga no Device 2
./gradlew :app:assembleDebug -Plocal.model=gemma4-e2b -Plocal.model.fallback=none

# bateria do Qwen, com o 0.5B de reserva
./gradlew :app:assembleDebug -Plocal.model=qwen-1.5b -Plocal.model.fallback=qwen-0.5b
```

Se o arquivo baixado tiver outro nome, renomeie ou ajuste a variante em `LocalModelConfig`.

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
