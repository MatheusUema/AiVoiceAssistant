# Tutorial — Como rodar o tier servidor de ponta a ponta

Guia **didático e autocontido**: do zero até coletar os dados de pesquisa. Cobre subir o
`llama-server`, validar, calibrar os thresholds de confiança, ligar o tier no app pelas
settings, e coletar/exportar o log de pesquisa.

> Complementa o `docs/05-tier-servidor-setup.md` (referência técnica) e os documentos
> conceituais `01-conceito-classificacao-elasticidade.md` / `02-plano-implementacao-migracao.md`
> do repositório `classificador-questões`. Aqui é o passo a passo operacional.

**Arquitetura em uma frase:** três tiers — **local** (MediaPipe, offline, sem confiança) →
**servidor** (llama.cpp na LAN, **com** logprobs/confiança) → **cloud** (Firebase/Gemini). O
`InferenceRouter` escolhe o tier por privacidade, conectividade, complexidade e confiança.

Índice:
- [Passo 0 — Visão geral do fluxo](#passo-0--visão-geral-do-fluxo)
- [Passo 1 — Subir o llama-server](#passo-1--subir-o-llama-server)
- [Passo 2 — Smoke test (validar o servidor)](#passo-2--smoke-test-validar-o-servidor)
- [Passo 3 — Confirmar o schema de logprobs](#passo-3--confirmar-o-schema-de-logprobs)
- [Passo 4 — Calibrar os thresholds de confiança](#passo-4--calibrar-os-thresholds-de-confiança)
- [Passo 5 — Ligar o tier servidor no app](#passo-5--ligar-o-tier-servidor-no-app)
- [Passo 6 — Rodar o app e verificar o roteamento](#passo-6--rodar-o-app-e-verificar-o-roteamento)
- [Passo 7 — Coletar e exportar o log de pesquisa](#passo-7--coletar-e-exportar-o-log-de-pesquisa)
- [Referência rápida — matriz de decisão de rota](#referência-rápida--matriz-de-decisão-de-rota)
- [Solução de problemas](#solução-de-problemas)

---

## Passo 0 — Visão geral do fluxo

```
[1] sobe llama-server na LAN  ->  [2] smoke test valida  ->  [3] confirma schema logprobs
        ->  [4] calibra thresholds  ->  [5] liga tier via settings  ->  [6] usa o app
        ->  [7] exporta o log de pesquisa (CSV)
```

Você precisa de:
- Uma **máquina na mesma rede Wi-Fi** do celular (o "servidor").
- O **celular Android** com o app instalado (tier local via MediaPipe já funciona).
- `llama.cpp` compilado + um modelo **GGUF** (idealmente o mesmo do tier local, Gemma 3 1B).
- Python 3 (só stdlib) para os scripts de validação/calibração.

---

## Passo 1 — Subir o llama-server

1. Compile o `llama.cpp` (uma vez):
   ```bash
   git clone https://github.com/ggml-org/llama.cpp
   cd llama.cpp && cmake -B build && cmake --build build --config Release -j
   ```
2. Baixe um modelo GGUF (exemplo Gemma 3 1B quantizado):
   ```bash
   wget https://huggingface.co/lmstudio-community/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf
   ```
3. Suba o servidor com o script pronto:

   **Linux/macOS:**
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
4. **Anote o IP LAN** que o script imprime (ex.: `192.168.1.100`). O celular usará
   `http://192.168.1.100:8080`.
5. **Libere a porta 8080/tcp** no firewall da máquina do servidor (senão o celular não
   conecta, mesmo com `--host 0.0.0.0`).

> Por que esses flags? `--host 0.0.0.0` expõe na LAN; `/health` e `/completion` já vêm
> habilitados. Os **logprobs** não são um flag do servidor — são pedidos por requisição
> via `n_probs`, e o app já envia isso.

---

## Passo 2 — Smoke test (validar o servidor)

Do seu computador (na mesma rede), valide sem precisar do app:

```bash
python scripts/server_smoke_test.py --url http://192.168.1.100:8080
```

Saída saudável:
```
[health]     GET /health -> status='ok' OK
[completion] POST /completion (n_probs=5) -> 42 tokens
[schema]     variante detectada: probs/tok_str (compativel com o app)  (42 tokens com prob)
[confidence] media das probs top-1 = 0.731  (metodo do app: calculateConfidence)
[resposta]   A fotossíntese é o processo pelo qual...
```

Opcionalmente, valide também a **classe real do app** contra o servidor (teste opt-in):
```bash
# Linux/macOS
LLAMA_SERVER_URL=http://192.168.1.100:8080 \
  ./gradlew :app:testDebugUnitTest --tests "*ServerInferenceServiceSmokeTest"
# Windows
$env:LLAMA_SERVER_URL="http://192.168.1.100:8080"; ./gradlew :app:testDebugUnitTest --tests "*ServerInferenceServiceSmokeTest"
```

---

## Passo 3 — Confirmar o schema de logprobs

⚠️ **Importante.** O formato de `completion_probabilities` mudou entre versões do
`llama-server`. O script (Passo 2) informa a variante:

- **`probs/tok_str (compativel com o app)`** → tudo certo, a confiança funciona.
- **`logprob/top_logprobs (NOVO ...)`** → o app entrega a resposta mas com
  `confidence = -1` (não escala por confiança). Para ativar a confiança nesse caso, siga
  a seção 5 do `docs/05-tier-servidor-setup.md` (ajustar `LlamaServerApi.kt` e
  `calculateConfidence`). Veja o JSON cru com:
  ```bash
  python scripts/server_smoke_test.py --url http://192.168.1.100:8080 --raw
  ```

> **Reprodutibilidade:** anote a versão/tag do `llama-server` que validou e registre junto
> dos dados de pesquisa.

---

## Passo 4 — Calibrar os thresholds de confiança

Os thresholds (`ServerConfig.confidenceThresholdHigh = 0.7`, `...Low = 0.3`) definem:
- confiança **≥ high** → entrega direta (direct-answer);
- confiança **< low** → escala para cloud;
- **entre** low e high → entrega servidor (zona scaffolded).

Eles são **específicos por modelo** — recalibre ao trocar o GGUF.

1. Crie `prompts_calibracao.txt` com prompts rotulados por dificuldade esperada:
   ```
   easy: Quanto é 2 + 2?
   easy: Qual é a capital do Brasil?
   hard: Deduza a fórmula de Bhaskara completando o quadrado.
   hard: Explique a interpretação de Copenhague da mecânica quântica.
   ```
2. Rode:
   ```bash
   python scripts/server_smoke_test.py --url http://192.168.1.100:8080 \
     --calibrate prompts_calibracao.txt
   ```
3. O script mostra a confiança por prompt, estatísticas por grupo e **sugere** thresholds
   (p25 dos `easy` para `high`, p75 dos `hard` para `low`). Se avisar "low ≥ high", o
   modelo pequeno não separa bem fácil/difícil por confiança — use valores conservadores.
4. Aplique os valores em `app/src/main/java/com/voiceassistant/di/ServiceModule.kt`:
   ```kotlin
   fun provideServerConfig(): ServerConfig = ServerConfig(
       confidenceThresholdHigh = 0.68f,
       confidenceThresholdLow  = 0.35f
   )
   ```
   Recompile o app.

---

## Passo 5 — Ligar o tier servidor no app

O tier servidor vem **desligado por padrão** (`serverTierEnabled = false`) — sem isso, o
app funciona só com local/cloud, como antes. Ligue via `SettingsRepository` (persistido em
DataStore):

```kotlin
// injete SettingsRepository e chame (ex.: num ViewModel/tela de configuração, ou
// temporariamente no onCreate para testes):
settingsRepository.setServerTierEnabled(true)
settingsRepository.setServerBaseUrl("http://192.168.1.100:8080")
```

Ainda **não há tela de settings** para isso (fora do escopo atual — os docs marcam a UI
como "não muda"). Para testar rápido sem UI, chame os setters acima num ponto de
inicialização. Alternativamente, mude o default de build em `provideServerConfig()`
(`baseUrl`) e a fonte do gate — mas o caminho recomendado é via settings.

> **Modo privacidade** (`privacyModeEnabled`) tem precedência: com ele ligado, o app usa
> **apenas** o tier local — nunca servidor nem cloud (dados não saem do device).

---

## Passo 6 — Rodar o app e verificar o roteamento

1. Conecte o celular na **mesma Wi-Fi** do servidor.
2. Com o tier ligado (Passo 5), faça uma pergunta simples no chat.
3. Observe o **badge da resposta**:
   - `LOCAL` — MediaPipe on-device
   - `SERVER` — llama-server (mostra que a confiança está em uso)
   - `CLOUD` — Firebase/Gemini
   - `FALLBACK` — tier primário falhou e caiu para outro
4. Acompanhe o `logcat` (tag `InferenceRouter` / `LlamaServer`):
   ```
   Rota: SERVER_WITH_CLOUD_ESCALATION | ... server=true cloud=true ...
   SERVER: 40 tokens, confidence=0.731, 820ms
   ```
   Se a confiança ficar baixa e houver cloud, você verá o log de escalonamento.

Cenários para exercitar a **elasticidade**:
- **Só local:** desligue o servidor/Wi-Fi → respostas vão para `LOCAL`.
- **LAN sem internet:** servidor de pé, sem internet → `SERVER`.
- **Internet completa:** tudo ligado → `SERVER_WITH_CLOUD_ESCALATION` (escala se inseguro),
  e perguntas complexas vão direto para `CLOUD`.

---

## Passo 7 — Coletar e exportar o log de pesquisa

Cada interação bem-sucedida grava uma linha na tabela Room `routing_log`
(`RoutingLogEntry`): pergunta, complexidade, rota, confiança, método, tier final, modo,
latência, `modelId` e conectividade.

Para exportar em CSV, chame `RoutingLogger.exportCsv()` (injete o `RoutingLogger`):
```kotlin
val csv: String = routingLogger.exportCsv()
// grave em arquivo, ex.: context.filesDir/"routing_log.csv", ou compartilhe via Intent
```

Colunas do CSV:
```
timestamp,session_id,question,complexity,route,confidence,method,tier,mode,latency_ms,model,connectivity
```

- `confidence = -1` e `method = none` → tier sem logprobs (local/cloud) ou schema novo.
- `method = logprobs_mean` → confiança real do servidor (o dado central da pesquisa).
- `connectivity` ∈ {offline, lan, internet}.

Esses dados alimentam a análise: comparar roteamento com confiança (servidor) vs.
heurística (local), verificar acertos de rota e recalibrar thresholds.

> Ainda não há botão de export na UI (fora do escopo). Chame `exportCsv()` a partir de um
> ViewModel/ação temporária, ou adicione um item de menu quando construir a tela de
> configuração.

---

## Referência rápida — matriz de decisão de rota

Prioridade (de cima para baixo) em `InferenceRouter.resolveRoute`:

| Condição | Decisão |
|---|---|
| Privacidade + local | `LOCAL` |
| Privacidade sem local | `ERROR_PRIVACY` |
| Offline total (sem internet e sem servidor) + local | `LOCAL` |
| Offline total sem local | `ERROR_OFFLINE` |
| Complexa + cloud | `CLOUD` |
| Servidor (LAN) + cloud | `SERVER_WITH_CLOUD_ESCALATION` |
| Servidor (LAN) sem cloud | `SERVER` |
| Local + cloud | `LOCAL_WITH_CLOUD_FALLBACK` |
| Só local | `LOCAL` |
| Só cloud | `CLOUD` |
| Nada | `ERROR_UNAVAILABLE` |

Confiança (no tier servidor): `≥ high` entrega SERVER · `< low` (e ≥ 0) escala p/ cloud ·
`-1` (sem logprobs) entrega SERVER · caso contrário entrega SERVER (zona média).

---

## Solução de problemas

| Sintoma | Causa provável | Ação |
|---|---|---|
| Respostas sempre `LOCAL`, nunca `SERVER` | tier desligado, ou health falhou | confirme `setServerTierEnabled(true)` + URL; rode o smoke test; cheque firewall/porta 8080 |
| `confidence = -1` no `SERVER` | schema novo de logprobs | Passo 3; ajuste DTOs se quiser confiança |
| App demora e cai para local/cloud | timeout do servidor | verifique carga/rede; timeouts em `ServerConfig` (connect 5s, read 30s) |
| Nada conecta ao servidor | `--host` errado ou firewall | use `--host 0.0.0.0`; libere 8080/tcp; teste `curl http://IP:8080/health` de outra máquina |
| `SERVER` some quando ativo modo privacidade | comportamento correto | privacidade força só local |
