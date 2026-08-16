# Módulo `:llama` — runtime local sobre llama.cpp

Ponte JNI entre o app e o [llama.cpp](https://github.com/ggml-org/llama.cpp).
Substitui o MediaPipe no tier LOCAL para que device e `llama-server` usem o **mesmo
motor** — condição para comparar TTFT, tokens/s e timings entre os tiers no estudo de
elasticidade (doc `06-plano-implementacao-hardware.md` §1).

## Estrutura

```
llama/
├── build.gradle.kts                 # AGP library + externalNativeBuild (CMake)
└── src/main/
    ├── cpp/
    │   ├── CMakeLists.txt           # add_subdirectory(llama.cpp) + libllama_bridge.so
    │   ├── llama_bridge.cpp         # ponte JNI (load / generate / stats / free)
    │   └── llama.cpp/               # SUBMÓDULO git, fixado na tag b10235
    └── java/com/voiceassistant/llama/
        ├── LlamaBridge.kt           # declarações `external` (internal)
        ├── LlamaEngine.kt           # fachada com confinamento de thread
        └── LlamaModels.kt           # LlamaParams / LlamaStats / LlamaLoadResult
```

O consumidor é `LlamaCppLocalInferenceService` (módulo `:app`), ligado no Hilt em
`ServiceModule.bindLocalInference`.

## Pré-requisitos de build

| Ferramenta | Versão | Observação |
|---|---|---|
| JDK | 17 | AGP 8.5.2 |
| Android SDK | platform 35 | já usado pelo `:app` |
| NDK | 27.2.12479018 | override: `-Pllama.ndk=<versão>` |
| CMake (do SDK) | 3.22.1 | override: `-Pllama.cmake=<versão>` |

Instalação via `sdkmanager`:

```bash
sdkmanager "platforms;android-35" "ndk;27.2.12479018" "cmake;3.22.1"
```

E o submódulo (o build falha com mensagem explícita se faltar):

```bash
git submodule update --init --recursive
```

O `:app` também exige um `app/google-services.json` (Firebase, tier cloud) — ele é
gitignored, então cada máquina precisa do seu. Para compilar só o tier local, um
placeholder com `package_name` `com.voiceassistant` e `com.voiceassistant.debug` basta.

## Compilar

```bash
# baseline do estudo: CPU-only, arm64-v8a
./gradlew :llama:assembleDebug

# incluir emulador x86_64
./gradlew :llama:assembleDebug -Pllama.abis=arm64-v8a,x86_64

# extensões (medir à parte do baseline — ver riscos no doc 06 §6)
./gradlew :llama:assembleDebug -Pllama.vulkan=true
./gradlew :llama:assembleDebug -Pllama.kleidiai=true
```

**O baseline é CPU-only de propósito.** Vulkan em Adreno/Mali é instável e KleidiAI baixa
fontes em tempo de build (quebra build offline e reprodutibilidade). As duas entram como
extensões medidas separadamente, não como default.

## Telemetria

`LlamaEngine.generate()` devolve texto **e** `LlamaStats`, vindos do
`llama_perf_context` do próprio llama.cpp:

| Campo | Origem | Métrica do plano |
|---|---|---|
| `ttftMs` | relógio da ponte, no 1º token amostrado | H2 |
| `prefillMs` | `t_p_eval_ms` (ingestão do prompt) | H2 |
| `decodeMs` | `t_eval_ms` (geração) | H2 |
| `promptTokens` / `generatedTokens` | `n_p_eval` / `n_eval` | H3 |
| `promptTokensPerSec` / `generatedTokensPerSec` | derivados | H3 |

São os mesmos campos que o `llama-server` reporta em `timings` — daí a comparabilidade
device × servidor. Convenção do projeto: `-1` = indisponível.

> ⚠️ `llama_context_params.no_perf` tem default **`true`** nesta versão do llama.cpp: sem
> `no_perf = false` os timings voltam zerados e H2/H3 somem sem erro nenhum. O
> `LlamaCppSmokeTest` falha de propósito se `prefillMs`/`decodeMs` vierem zero.

### Estado da validação (Fase 1)

Emulador API 29 x86_64, `qwen2.5-0.5b-instruct-q4_k_m.gguf`, build CPU-only:

```
carga 216 ms | backends=CPU | n_ctx=2048
prompt 44 tok, prefill 2341 ms | geração 8 tok, 14,7 tok/s | TTFT 2276 ms
"Qual é a capital do Brasil?" -> "A capital do Brasil é Brasília."
```

Falta validar em hardware ARM real (os 3 aparelhos) e conferir se `prefillMs + decodeMs`
bate com o `totalMs` — no emulador a soma deu ~2,5 % acima, o que precisa de um olhar na
Fase 2 antes de esses números virarem dado do artigo.

## Contratos que a ponte garante

- **Nada aborta o processo por falha esperada.** GGUF ausente, modelo que não cabe na RAM
  ou decode que falha viram código de erro + mensagem (`LlamaLoadResult.Failure`).
  No estudo isso é *resultado* (o limite do Device 2, 4 GB), não crash a esconder.
- **Uma thread só.** O contexto do llama.cpp não é thread-safe; `LlamaEngine` serializa
  tudo no thread `llama-inference`.
- **Sessões independentes.** `generate()` limpa o KV-cache antes do prefill, então não
  há vazamento de contexto entre questões do bloco de medição.

## Atualizar a versão do llama.cpp

```bash
cd llama/src/main/cpp/llama.cpp
git fetch --tags && git checkout <tag>
cd - && git add llama/src/main/cpp/llama.cpp && git commit
```

Fixar a tag é obrigatório: o protocolo (doc 04 §8) exige as mesmas flags e a mesma
versão de motor entre execuções.
