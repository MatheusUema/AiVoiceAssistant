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

## Captura realizada — 03/10/2026

**Aparelho: Device 1.** `ro.product.model` = `23088PND5R`, `ro.product.marketname` =
**Xiaomi 13T Pro**, plataforma `mt6985`, 11.616 MB de RAM, Android 15 (HyperOS 2).
Dois identificadores independentes (código de modelo e SoC) casam com o Device 1 da
tabela canônica, então os prints estão **dentro** do conjunto caracterizado.

> *Nota de nomenclatura:* `analise-quatro-aparelhos.md` escreve "Redmi (23088PND5R)",
> mas o aparelho se identifica como **Xiaomi 13T Pro**. Mesmo aparelho, nome comercial
> errado naquele arquivo.

Modelo local `qwen2.5-1.5b-instruct-q4_k_m` (1,1 GB), carregado em 2.613 ms, backend CPU,
4 threads, ctx 2048. Cenário Unplugged por modo privacidade (ver `CenarioUnpluggedSetup`),
desligado ao final. **Cortes em vigor em todas as tentativas: 0,2852 / 0,6341.**

| # | área | pergunta | score | modo | tempo | tokens |
|---|---|---|---|---|---|---|
| 1 | MT | cone invertido, volume a 2 m de altura | **0,1796** | **MEDIAR** | 11.549 ms | 201→146 |
| 2 | CH | Revolução Francesa e suas causas | **0,5826** | **RESSALVA** | 5.686 ms | 153→49 |
| 3 | CN | o que é fotossíntese | **0,6015** | RESSALVA | 6.734 ms | 151→86 |
| 4 | MT | quanto é 15% de 200 | **0,9890** | **DIRETO** | 4.891 ms | 147→17 |

Prints em `prints-d1/aparelho/`: `dev1-mt1-mediar.png`, `dev1-ch1-ressalva.png`,
`dev1-cn1-ressalva.png`, `dev1-mt2-direto.png`. **Os quatro são de questão real** — a faixa
veio do score da cascata, não de estado forçado.

**Os três modos saíram em quatro perguntas**, e a mediação na primeira.

### Advertência de legenda: as duas MT ficaram nos dois extremos

A busca começou por matemática porque é onde a pesquisa mede o pior desempenho, e
funcionou — 0,1796 está bem dentro do decil 10. **Mas a quarta tentativa também é de
matemática e deu 0,9890, o maior score do conjunto.** Os dois extremos vieram da mesma
área.

Portanto: **o sinal responde à pergunta, não à área.** Uma legenda que dissesse
"matemática cai em mediação" estaria generalizando de um caso — e seria uma afirmação mais
forte do que qualquer coisa medida aqui, porque quatro perguntas não estabelecem padrão
nenhum.

O que distingue as duas não é o rótulo de área: a que mediou é a única com **raciocínio de
múltiplos passos** (semelhança de triângulos *mais* volume de cone); a que foi direto
resolve-se numa conta.

**Isto vale para além do print.** O documento trata MT como ponto cego do roteamento, e
está certo — mas aquilo é **propriedade agregada**, medida sobre centenas de questões, e
não regra por item. Um sistema que escalasse "toda questão de MT" agiria sobre a média e
erraria nos dois sentidos: mandaria à nuvem perguntas como "15% de 200", que o modelo local
acerta sem hesitar, e não é por ser de matemática que uma pergunta é difícil. O par
0,1796 / 0,9890 é o lembrete mais curto disso que a coleta produziu.

### Nota metodológica: fotografar achou o que a suíte não pegava

Dois defeitos foram encontrados **pela captura**, não pelos testes — e nenhum dos dois era
do Bloco D parte 1 em si:

- **"Levar ao professor" cortado ao meio.** Com os dois selos presentes, a linha estourava
  o `widthIn(max = 300.dp)` da coluna e o rótulo quebrava sob a altura do botão. Nenhum
  teste mede largura de texto renderizado; só se vê olhando.
- **As pílulas de privacidade e offline sumiam ao abrir conversa nova.**
  `ChatViewModel.startNewSession` substituía o estado inteiro por `ChatUiState()` padrão,
  zerando `privacyModeEnabled` e `isOffline`; como `observeSettings` só reemite quando o
  valor **muda**, elas não voltavam. É **pré-existente**, não do D1.

O segundo é o mais instrutivo, e é por isso que está aqui. Ele **só aparece na interseção
de duas condições** — privacidade ligada **e** alguém tocando em "nova conversa" — e
nenhum teste fazia as duas: os de migração não abrem a UI, os de política não têm estado de
tela, e os `@Preview` constroem o estado à mão, já com as flags no valor certo, de modo que
nunca passam por `startNewSession`. A suíte estava verde e continuaria verde.

Quem o encontrou foi o roteiro de captura, porque ele toca em "nova conversa" antes de cada
pergunta para que o print saia limpo — exatamente o gesto que nenhum teste fazia. E o efeito
não é cosmético para quem usa: **o aluno veria o indicador de privacidade desaparecer sem
ter desligado nada**, o que é a interface mentindo sobre o estado do sistema.

Fica como argumento a favor do próprio Bloco D parte 1: expressar a política na tela não é
só produzir figura: é um **modo de verificação** que a bateria de medição não substitui.

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
