# Fase 5 — Bring-up do tier servidor (llama.cpp `llama-server`)

Parte **operacional/externa ao app**. Aqui você sobe o `llama-server` numa máquina da
rede local (lab/escola), valida que ele retorna logprobs, confirma o schema da resposta
e calibra os thresholds de confiança. O app (tier servidor) já está pronto — veja
`ServerInferenceService`, `ServerConfig` e o roteamento em `InferenceRouter`.

> **Nada aqui altera o runtime local (MediaPipe) nem o app.** São scripts e docs de apoio.

Índice:
1. [Pré-requisitos](#1-pré-requisitos)
2. [Subir o servidor](#2-subir-o-servidor)
3. [Endpoints e flags relevantes](#3-endpoints-e-flags-relevantes)
4. [Validar (teste de fumaça)](#4-validar-teste-de-fumaça)
5. [Confirmar o schema de `completion_probabilities`](#5-confirmar-o-schema-de-completion_probabilities)
6. [Calibrar os thresholds de confiança](#6-calibrar-os-thresholds-de-confiança)
7. [Apontar o app para o servidor](#7-apontar-o-app-para-o-servidor)

---

## 1. Pré-requisitos

- Uma máquina na **mesma rede local** do dispositivo Android (lab/escola).
- `llama.cpp` compilado (ou binários pré-compilados). Build a partir do fonte:
  ```bash
  git clone https://github.com/ggml-org/llama.cpp
  cd llama.cpp
  cmake -B build
  cmake --build build --config Release -j
  # binário: build/bin/llama-server  (Windows: build/bin/Release/llama-server.exe)
  ```
- Um modelo **GGUF**. Recomendado alinhar com o tier local (Gemma 3 1B):
  ```bash
  # exemplo (HuggingFace CLI ou wget do arquivo .gguf)
  wget https://huggingface.co/lmstudio-community/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf
  ```
- Python 3 (só stdlib) **ou** `curl` para os testes de fumaça.

---

## 2. Subir o servidor

Use os scripts prontos (parametrizáveis por variáveis de ambiente):

**Linux / macOS:**
```bash
MODEL=/caminho/gemma-3-1b-it-Q4_K_M.gguf \
LLAMA_SERVER_BIN=/caminho/llama.cpp/build/bin/llama-server \
scripts/llama-server-up.sh
```

**Windows (PowerShell):**
```powershell
$env:MODEL="C:\models\gemma-3-1b-it-Q4_K_M.gguf"
$env:LLAMA_SERVER_BIN="C:\llama.cpp\build\bin\Release\llama-server.exe"
scripts\llama-server-up.ps1
```

Ou manualmente:
```bash
./llama-server \
  -m gemma-3-1b-it-Q4_K_M.gguf \
  --host 0.0.0.0 \      # ESSENCIAL: aceita conexões da LAN (não só localhost)
  --port 8080 \
  -c 2048 \             # tamanho do contexto
  -t 4                  # threads
```

Descubra o IP LAN da máquina (o app usará `http://<IP>:8080`):
- Linux/macOS: `ip addr` / `ifconfig` (procure `192.168.x.x` ou `10.x.x.x`)
- Windows: `ipconfig` (campo "IPv4")

> **Firewall:** libere a porta **8080/tcp de entrada** na máquina do servidor, senão o
> celular não conecta mesmo com `--host 0.0.0.0`.

---

## 3. Endpoints e flags relevantes

O `llama-server` expõe (entre outros):

| Endpoint | Método | Uso no app |
|---|---|---|
| `/health` | GET | `ServerInferenceService.isServerReachable()` → `{"status":"ok"}` |
| `/completion` | POST | `generateWithConfidence()` → geração + logprobs |

**Logprobs não são um flag do servidor** — são pedidos **por requisição** no corpo JSON
via `n_probs` (o app já envia `n_probs = ServerConfig.nProbs`, default 5). Quando
`n_probs > 0`, a resposta inclui `completion_probabilities`. Não existe (nas versões
atuais) um flag `--n-probs` obrigatório; se a sua versão aceitar, é apenas um default.

Corpo que o app envia (`CompletionRequest`):
```json
{ "prompt": "...", "n_predict": 512, "temperature": 0.7,
  "top_p": 0.9, "top_k": 40, "n_probs": 5 }
```

---

## 4. Validar (teste de fumaça)

Sem depender do app, valide health + completion + parsing de confiança:

```bash
# usa apenas Python stdlib
python scripts/server_smoke_test.py --url http://192.168.1.100:8080
```

Saída esperada (resumo):
```
[health]     GET /health -> status=ok
[completion] POST /completion (n_probs=5) -> 42 tokens
[schema]     variante detectada: probs/tok_str (compatível com o app)
[confidence] média das probs top-1 = 0.731  (método do app: calculateConfidence)
```

Alternativa com `curl` (inspeção crua):
```bash
curl -s http://192.168.1.100:8080/health
curl -s http://192.168.1.100:8080/completion \
  -H "Content-Type: application/json" \
  -d '{"prompt":"O que é fotossíntese?","n_predict":64,"n_probs":5}' | python -m json.tool
```

Há também um **teste de fumaça no próprio app** (opt-in, desligado por padrão) que
exercita a classe real `ServerInferenceService` contra o endpoint:
```bash
# Linux/macOS
LLAMA_SERVER_URL=http://192.168.1.100:8080 \
  ./gradlew :app:testDebugUnitTest --tests "*ServerInferenceServiceSmokeTest"

# Windows (PowerShell)
$env:LLAMA_SERVER_URL="http://192.168.1.100:8080"
./gradlew :app:testDebugUnitTest --tests "*ServerInferenceServiceSmokeTest"
```
Sem a variável `LLAMA_SERVER_URL`, o teste é **pulado** (não quebra a suíte).

---

## 5. Confirmar o schema de `completion_probabilities`

⚠️ **Ponto crítico.** O formato de `completion_probabilities` **mudou entre versões** do
`llama-server`. Os DTOs do app (`TokenProb`/`ProbEntry`) esperam o formato **antigo**:

```json
"completion_probabilities": [
  { "content": "A", "probs": [ { "tok_str": "A", "prob": 0.92 }, ... ] },
  ...
]
```

Versões recentes emitem um formato **novo** (por token escolhido, com `logprob` e
`top_logprobs`):
```json
"completion_probabilities": [
  { "id": 234, "token": "A", "logprob": -0.08, "top_logprobs": [ ... ] },
  ...
]
```

**Como confirmar qual você tem:** o `server_smoke_test.py` imprime a **variante detectada**.
Você também pode olhar o JSON cru (`--raw`) ou o `curl` acima.

- **`probs/tok_str` (antigo)** → compatível. O app calcula confiança normalmente.
- **`logprob/top_logprobs` (novo)** → **incompatível com os DTOs atuais.** O app
  desserializa `completion_probabilities` como `null` (campos não batem) e
  `calculateConfidence` retorna **-1** (degradação graciosa: entrega a resposta como
  SERVER, sem escalar por confiança — foi projetado assim de propósito). Para ativar a
  confiança nesse caso, ajuste `LlamaServerApi.kt` (mapear `token`/`logprob` e converter
  `prob = exp(logprob)`) e `ServerInferenceService.calculateConfidence`. O
  `server_smoke_test.py` já mostra qual seria a confiança nesse formato, para você
  comparar antes de mexer no app.

> Recomendação de reprodutibilidade: **fixe a versão** do `llama-server` que validar
> (anote o commit/tag) e registre-a junto dos dados de pesquisa.

---

## 6. Calibrar os thresholds de confiança

Os thresholds vivem em `ServerConfig` (defaults `confidenceThresholdHigh = 0.7`,
`confidenceThresholdLow = 0.3`) e governam o escalonamento no `InferenceRouter`:

- confiança ≥ **high** → entrega direta (direct-answer)
- confiança < **low** → escala para cloud
- entre low e high → entrega SERVER (zona "scaffolded")

**Procedimento sugerido** (com o mesmo modelo que rodará em produção):

1. Monte um arquivo de prompts rotulados por dificuldade esperada — uma linha por prompt,
   prefixo opcional `easy:` / `hard:`:
   ```
   easy: Quanto é 2 + 2?
   easy: Qual é a capital do Brasil?
   hard: Deduza a fórmula de Bhaskara a partir do completamento de quadrados.
   hard: Explique a interpretação de Copenhague da mecânica quântica.
   ```
2. Rode o modo de calibração:
   ```bash
   python scripts/server_smoke_test.py --url http://192.168.1.100:8080 \
     --calibrate prompts_calibracao.txt
   ```
3. O script imprime a confiança de cada prompt e estatísticas por grupo, além de uma
   **sugestão** de thresholds (heurística de percentis):
   - `high` ≈ percentil 25 das confianças dos `easy` (a maioria dos fáceis fica acima)
   - `low`  ≈ percentil 75 das confianças dos `hard` (a maioria dos difíceis fica abaixo)
4. Ajuste `ServerConfig` em `ServiceModule.provideServerConfig()` (ou futuramente via
   settings) e valide com os testes E2E.

> Os thresholds são **específicos por modelo**. Ao trocar o modelo GGUF do servidor,
> recalibre. Registre `modelId` no log de pesquisa (já feito em `RoutingLogEntry`).

---

## 7. Apontar o app para o servidor

O tier servidor é **desligado por padrão** (`UserSettings.serverTierEnabled = false`),
então nada muda até você ativá-lo. Duas formas:

- **Runtime (recomendado):** via `SettingsRepository`:
  ```kotlin
  settingsRepository.setServerTierEnabled(true)
  settingsRepository.setServerBaseUrl("http://192.168.1.100:8080")
  ```
- **Default de build:** troque `provideServerConfig()` em
  `app/.../di/ServiceModule.kt` para outra `baseUrl` de fallback.

Com isso ligado, o `InferenceRouter` faz o `/health` e, se OK, passa a rotear perguntas
elegíveis para o servidor (com escalonamento por confiança quando o cloud estiver
disponível). Se o servidor cair, o app cai para local/cloud automaticamente.
