# Prints do Bloco D parte 1 — os três modos de resposta

Roteiro de captura. Dois tipos de imagem, que **respondem a perguntas diferentes** e por
isso são os dois necessários.

| | O que demonstra | Onde |
|---|---|---|
| **Estado forçado** | que a **interface** sabe expressar os três modos | emulador ou JVM, sem modelo |
| **Questão real** | que o **sinal** escolhe entre eles | aparelho físico, Qwen2.5-1.5B |

A §16 exige que a legenda de cada figura diga de qual tipo ela é. Um print de estado
forçado apresentado como comportamento do roteador é o que a §15 chama de medição que
engana.

---

## 1. Estado forçado (não precisa de aparelho nem de modelo)

Duas rotas para a mesma imagem.

**No Android Studio** — abrir `ChatScreen.kt` e usar o painel de preview. Os três previews
são `D1 — 1. Responder direto`, `D1 — 2. Responder com ressalva` e
`D1 — 3. Mediar ao professor`.

**Por linha de comando**, com um emulador em pé:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pllama.abis=x86_64 \
  --tests "com.voiceassistant.feature_chat.ui.ResponseModeScreenshotTest"

adb pull /sdcard/Android/data/com.voiceassistant/files/prints-d1 .
```

Saem quatro PNG: os três modos mais `d1-3b-mediar-revelado`, que é o modo mediado depois
de tocar em *"Ver a resposta mesmo assim"*. Esse quarto existe porque o recolhimento é a
parte do desenho que uma imagem só não mostra — sem ele o leitor poderia concluir que o
sistema **descartou** a resposta, quando ele só deixou de entregá-la diretamente.

O `-Pllama.abis=x86_64` é necessário porque o default compila só `arm64-v8a` e a APK não
instalaria no emulador.

## 2. Questão real (aparelho físico — Device 2)

```bash
./gradlew :app:assembleDebug -Plocal.model=qwen-1.5b
```

**O `-Plocal.model=qwen-1.5b` não é opcional.** O default é `gemma4-e2b`, e a política
`cascata-v1` foi treinada sobre os logprobs do **Qwen2.5-1.5B** — o campo `alvo` do asset
diz isso. Pontuar a distribuição do Gemma com coeficientes do Qwen daria um número
plausível e sem sentido. (No Gemma-4-E2B a leitura exploratória (b2) dá 0,4861 com apenas
11 erros classificáveis: 72% de sem-resposta.)

Depois:

1. Empurrar o GGUF: `scripts/push-model.ps1` (ou `adb push` para o diretório de modelos).
2. **Ligar o modo privacidade** — é o que fixa o cenário Unplugged sem derrubar a
   depuração por Wi-Fi, como o `BenchmarkEntryPoint` já documenta. O modo avião mataria o
   `adb`.
3. Perguntar, fotografar, e **anotar o score de cada print**:

```bash
adb logcat -s InferenceRouter | grep "modo pedagógico"
```

A linha traz o modo, o score e os dois cortes em vigor. A tela **não** mostra número de
propósito: uma cifra ali convidaria o aluno a interpretá-la, e o D1 é demonstração, não
medida. Mas a legenda precisa poder citá-lo, e é daí que ele sai.

### Mediação é rara por construção — conte com isso

O corte de mediação é o **quantil 0,1** do treino. Sob corte por orçamento cerca de **uma
em dez** perguntas cai nessa faixa, e isso vale *independentemente de o dia ter sido bom ou
ruim*: o corte é um quantil da distribuição de treino, não um juízo absoluto sobre a
resposta.

Duas consequências práticas:

- Pode ser preciso varrer várias perguntas até cair uma em `MEDIAR`. É esperado, não
  defeito.
- **A legenda não pode dizer que "o sistema desiste quando a resposta é ruim".** O que a
  AUC de 0,735 [0,731; 0,742] sustenta é que a *ordenação* é informativa — o sistema
  reserva uma fatia do orçamento para mediação e manda para lá as de **menor score
  relativo**.

Se a varredura ficar custosa, o caminho honesto já está autorizado pela §16: usar o print
de estado forçado e dizer isso na legenda.

---

## O que registrar na §16 quando terminar

A seção pede três coisas nominalmente:

1. **Qual sinal ficou ligado à bandeira** — a cascata recalibrada, AUC **0,735**
   [0,731; 0,742] fora da dobra, sobre 17 estatísticas da distribuição de logprobs.
   **Não** a confiança crua do aplicativo, que a §17 revoga (AUC 0,420, IC
   [0,360; 0,480], e cujo gatilho nunca dispara).
2. **Onde os prints foram obtidos** — aparelho físico (qual) ou emulador. O emulador
   **não** serve para o caminho de questão real: o AVD deste projeto é x86_64 com 1,5 GB,
   que não sustenta um GGUF de ~1,1 GB mais o runtime.
3. **Quais telas vêm de questão real e quais de estado forçado** — por figura, na legenda.

E um quarto que não está na lista mas decorre do desenho: **os cortes são por orçamento**
(quantis 0,5 e 0,1 do treino), não um classificador de três vias medido.
