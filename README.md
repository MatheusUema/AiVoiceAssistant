# Voice Assistant — Tutor Educacional por Voz (Offline-First)

Aplicativo Android nativo em Kotlin + Jetpack Compose para assistência educacional via voz,
com inferência local (MediaPipe LLM) e cloud (Firebase AI Logic / Gemini).

---

## Pré-requisitos de ambiente


| Requisito                              | Versão mínima                   |
| -------------------------------------- | ------------------------------- |
| Android Studio                         | Hedgehog (2023.1.1) ou superior |
| JDK                                    | 17                              |
| Android SDK                            | compileSdk 35, minSdk 26        |
| Gradle                                 | 8.9 (bundled via wrapper)       |
| Kotlin                                 | 2.0.21                          |
| Dispositivo/Emulador                   | Android 8.0+ (API 26)           |
| RAM do dispositivo (para modelo local) | >= 4 GB                         |


---

## Checklist de configuração

### 1. Clone e sync do projeto

```bash
git clone <url-do-repositorio>
cd voiceassistant
```

Abra no Android Studio e aguarde o Gradle Sync completar.
Todos os arquivos de build já estão configurados:


| Arquivo                                    | O que configura                                                        |
| ------------------------------------------ | ---------------------------------------------------------------------- |
| `settings.gradle.kts`                      | Repositórios (Google, Maven Central, MediaPipe Maven)                  |
| `build.gradle.kts` (root)                  | Plugins declarados com `apply false`                                   |
| `app/build.gradle.kts`                     | Plugins aplicados, SDK versions, dependências, noCompress para modelos |
| `gradle/libs.versions.toml`                | Catálogo centralizado de versões e dependências                        |
| `gradle.properties`                        | JVM args (4 GB), parallel builds, configuration cache                  |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.9                                                             |


### 2. Firebase AI Logic (inferência cloud — Gemini)

Sem esta configuração, a inferência cloud fica indisponível. O app continua
funcionando com modelo local apenas (offline-first).

#### 2.1 Criar projeto no Firebase Console

1. Acesse [Firebase Console](https://console.firebase.google.com)
2. Crie um novo projeto (ou use um existente)
3. Vá em **Build → AI Logic** (menu lateral)
4. Ative o **Gemini Developer API** (plano Spark, gratuito)

#### 2.2 Registrar o aplicativo Android

No Firebase Console → Configurações do projeto → Seus apps → **Adicionar app Android**:


| Campo                  | Valor                      |
| ---------------------- | -------------------------- |
| Package name (debug)   | `com.voiceassistant.debug` |
| Package name (release) | `com.voiceassistant`       |


Registre **ambos** os application IDs para que funcione nos dois build types.

#### 2.3 Baixar e posicionar o google-services.json

1. Baixe o `google-services.json` do Firebase Console
2. Coloque em:

```
voiceassistant/
└── app/
    └── google-services.json   ← AQUI
```

**Importante:** este arquivo NÃO deve ser commitado no git (adicione ao `.gitignore`).

#### 2.4 Verificar a configuração

O `FirebaseCloudInferenceService` inicializa o modelo de forma lazy:

- Se `google-services.json` estiver presente → `isAvailable = true` após primeira chamada
- Se ausente → `isAvailable = false`, o `InferenceRouter` usa apenas o modelo local

Configuração do modelo (editável em `ServiceModule.kt` → `provideCloudModelConfig()`):


| Parâmetro              | Default              | Descrição                                                  |
| ---------------------- | -------------------- | ---------------------------------------------------------- |
| `modelName`            | `"gemini-2.0-flash"` | Modelo Gemini (`gemini-1.5-pro`, `gemini-1.5-flash`, etc.) |
| `maxOutputTokens`      | `1024`               | Máximo de tokens na resposta                               |
| `temperature`          | `0.7`                | 0.0 = determinístico, 1.0 = criativo                       |
| `topP`                 | `0.95`               | Nucleus sampling                                           |
| `safetyBlockThreshold` | `MEDIUM_AND_ABOVE`   | Nível de filtro de segurança                               |
| `backend`              | `GOOGLE_AI`          | `GOOGLE_AI` (Spark) ou `VERTEX_AI` (Blaze)                 |


Arquivo: `ai_cloud/model/CloudModelConfig.kt`

### 3. Modelo local (inferência offline — MediaPipe LLM)

Sem esta configuração, a inferência local fica indisponível. O app continua
funcionando com cloud apenas (quando online).

#### 3.1 Baixar o modelo

Baixe um modelo compatível com MediaPipe LLM Inference (formato `.task`):


| Modelo                | Tamanho | RAM requerida | Link                                                                                       |
| --------------------- | ------- | ------------- | ------------------------------------------------------------------------------------------ |
| Gemma 3 1B IT INT4    | ~900 MB | ~2 GB         | [HuggingFace](https://huggingface.co/litert-community/Gemma3-1B-IT) ← **RECOMENDADO**     |
| Gemma 3n E2B          | ~1.2 GB | ~3 GB         | [HuggingFace](https://huggingface.co/litert-community)                                     |
| Gemma 3n E4B          | ~2.5 GB | ~6 GB         | [HuggingFace](https://huggingface.co/litert-community)                                     |


#### 3.2 Posicionar o arquivo do modelo

Coloque o arquivo `.task` baixado em:

```
voiceassistant/
└── app/
    └── src/
        └── main/
            └── assets/
                └── models/
                    └── gemma3-1b-it-int4.task   ← AQUI
```

Crie as pastas `assets/models/` se não existirem.

**Importante:** arquivos em `assets/` são empacotados no APK. O build inclui
`noCompress += listOf("bin", "tflite", "task", "litertlm")` para que o AAPT não comprima
modelos grandes.

#### 3.3 Ajustar o nome do arquivo (se diferente)

Se o arquivo baixado tiver nome diferente de `gemma3-1b-it-int4.task`,
edite `LocalModelConfig` em `ServiceModule.kt`:

```kotlin
@Provides
fun provideLocalModelConfig(): LocalModelConfig = LocalModelConfig(
    modelAssetPath = "models/NOME_DO_SEU_ARQUIVO.task"
)
```

Arquivo: `ai_local/model/LocalModelConfig.kt`

#### 3.4 Pipeline de carregamento automático

Na inicialização do app (`VoiceAssistantApp.onCreate()`), o `LocalModelManager`
executa automaticamente:

```
NotLoaded → Checking → Loading → WarmingUp → Ready
                ↓           ↓           ↓
              Error       Error       Error
```

1. **Checking**: verifica RAM (>= 3 GB), API level (>= 26), espaço em disco
2. **Loading**: copia modelo de `assets/` para `filesDir/` (primeira vez), carrega em memória
3. **WarmingUp**: inferência descartável para pré-aquecer caches (desativável via `warmupEnabled = false`)
4. **Ready**: modelo pronto, `InferenceRouter` pode rotear para local

Se qualquer etapa falhar, o estado vai para `Error` e o app continua apenas com cloud.

Configuração do modelo local (editável em `ServiceModule.kt` → `provideLocalModelConfig()`):


| Parâmetro        | Default                             | Descrição                            |
| ---------------- | ----------------------------------- | ------------------------------------ |
| `modelAssetPath` | `"models/gemma3-1b-it-int4.task"`   | Caminho dentro de `assets/`          |
| `maxTokens`      | `1024`                              | Tamanho do KV-cache (input + output) |
| `temperature`    | `0.2`                               | Baixa para respostas factuais        |
| `topK`           | `20`                                | Candidatos por passo de sampling     |
| `topP`           | `0.85`                              | Nucleus sampling                     |
| `randomSeed`     | `42`                                | Seed fixa para reprodutibilidade     |
| `minRamMb`       | `2048`                              | RAM mínima do dispositivo (MB)       |
| `warmupEnabled`  | `true`                              | Executar inferência de warmup        |
| `warmupPrompt`   | `"Olá"`                             | Texto usado no warmup                |


Arquivo: `ai_local/model/LocalModelConfig.kt`

### 4. Permissões (já configuradas)

O `AndroidManifest.xml` já declara todas as permissões necessárias:


| Permissão              | Uso                                | Obrigatória?                                    |
| ---------------------- | ---------------------------------- | ----------------------------------------------- |
| `RECORD_AUDIO`         | Captura de voz (STT)               | Runtime permission — solicitada na MainActivity |
| `INTERNET`             | Inferência cloud (Firebase/Gemini) | Automática                                      |
| `ACCESS_NETWORK_STATE` | NetworkMonitor (InferenceRouter)   | Automática                                      |
| `VIBRATE`              | Feedback háptico opcional          | Automática                                      |


O microfone é declarado como `android:required="false"` — o app funciona
no modo texto mesmo sem microfone.

### 5. Segurança de rede (já configurada)

O `network_security_config.xml` bloqueia tráfego cleartext (HTTP):

```xml
<base-config cleartextTrafficPermitted="false">
    <trust-anchors>
        <certificates src="system" />
    </trust-anchors>
</base-config>
```

Todo o tráfego (Firebase, Gemini) usa HTTPS.

### 6. ProGuard (já configurado)

O `proguard-rules.pro` preserva classes críticas para:


| Biblioteca           | Regra                                      |
| -------------------- | ------------------------------------------ |
| Hilt                 | `@HiltViewModel` constructors              |
| Room                 | `@Entity`, `@Dao`, RoomDatabase subclasses |
| Kotlin Serialization | Companions, serializers                    |
| MediaPipe            | `com.google.mediapipe.**`                  |
| Firebase             | `com.google.firebase.**`                   |


### 7. Banco de dados Room (já configurado)

- Entidade: `ChatMessage` (histórico de conversas)
- DAO: `ChatMessageDao` (CRUD de mensagens)
- Database: `AppDatabase` (versão 1)
- Schema exportado para `app/schemas/` (versionamento de migrações)

**Atenção (produção):** o `DatabaseModule` usa `fallbackToDestructiveMigration(true)`.
Ao incrementar a versão do banco, substitua por uma `Migration` real para
preservar dados do usuário.

### 8. Injeção de dependência Hilt (já configurado)

Três módulos em `di/`:


| Módulo             | Bindings                                                                    |
| ------------------ | --------------------------------------------------------------------------- |
| `DatabaseModule`   | `AppDatabase`, `ChatMessageDao`                                             |
| `ServiceModule`    | STT, TTS, LocalInference, CloudInference + configs                          |
| `RepositoryModule` | ChatRepository, SettingsRepository, InferenceRepository (→ InferenceRouter) |


Para alterar configs de modelo, edite os `@Provides` em `ServiceModule.companion`.

---

## Arquitetura

```
app/src/main/java/com/voiceassistant/
├── core/
│   ├── common/          # Resource<T>, Extensions
│   ├── model/           # ChatMessage, InferenceSource, UserSettings, TutorMode,
│   │                    # InferenceRequest, InferenceResult, PromptComplexity
│   ├── network/         # NetworkMonitor
│   └── storage/         # AppDatabase, ChatMessageDao, Converters, UserSettingsDataStore
├── domain/
│   ├── repository/      # ChatRepository, InferenceRepository, SettingsRepository
│   └── usecase/         # SendMessageUseCase, GetChatHistoryUseCase, TranscribeSpeechUseCase
├── data/
│   └── repository/      # ChatRepositoryImpl, SettingsRepositoryImpl
├── ai_local/
│   ├── model/           # LocalModelConfig
│   ├── service/         # LocalInferenceService ↔ MediaPipeLocalInferenceService
│   └── manager/         # LocalModelManager, DeviceCapabilityChecker, ModelState
├── ai_cloud/
│   ├── model/           # CloudModelConfig, SafetyBlockThreshold, CloudBackend
│   └── service/         # CloudInferenceService ↔ FirebaseCloudInferenceService
├── feature_chat/
│   ├── ui/              # ChatScreen, ChatComponents (TutorModeSelector, MicButton, etc.)
│   └── viewmodel/       # ChatViewModel, ChatUiState, ListeningState
├── feature_voice/
│   └── service/         # SpeechToTextService ↔ AndroidSpeechToTextService
│                        # TextToSpeechService ↔ AndroidTextToSpeechService
├── feature_tutor/
│   ├── prompt/          # TutorPromptBuilder (prompts por modo + local/cloud)
│   └── policy/          # InferenceRouter, RoutingDecision, PromptComplexityAnalyzer
├── di/                  # DatabaseModule, RepositoryModule, ServiceModule
├── ui/theme/            # Theme.kt, Typography.kt
├── MainActivity.kt
└── VoiceAssistantApp.kt
```

---

## Fluxo de dados

```
Usuário fala / digita + seleciona TutorMode (EXPLAIN, HINT, SUMMARY, REVIEW)
    ↓
SpeechToTextService (STT)
    ↓ texto transcrito
ChatViewModel.sendMessage()
    ↓
SendMessageUseCase(text, sessionId, tutorMode)
    ├── salva mensagem do usuário (Room)
    ├── busca histórico recente (Room)
    ├── analisa complexidade (PromptComplexityAnalyzer)
    └── InferenceRepository.infer(InferenceRequest)
            ↓
        InferenceRouter
            ├── resolveRoute() → RoutingDecision (puro, testável)
            ├── TutorPromptBuilder.build(mode, compact=targetsLocal)
            │     ├── LOCAL  → prompt compacto (2 frases + 4 msgs histórico)
            │     └── CLOUD  → prompt rico (instruções detalhadas + 10 msgs)
            ├── LOCAL  → MediaPipeLocalInferenceService
            ├── CLOUD  → FirebaseCloudInferenceService
            └── FALLBACK (local falhou → cloud)
    ↓ InferenceResult (text + source + latency)
ChatViewModel
    ├── salva resposta do assistente (Room)
    ├── atualiza UI via StateFlow<ChatUiState>
    └── TextToSpeechService.speak() (se TTS ativo)
```

---

## Regras do InferenceRouter (offline-first)


| #   | Condição                              | Decisão                     |
| --- | ------------------------------------- | --------------------------- |
| 1   | Privacidade ON + local disponível     | `LOCAL`                     |
| 2   | Privacidade ON + sem local            | `ERROR_PRIVACY`             |
| 3   | Offline + local disponível            | `LOCAL`                     |
| 4   | Offline + sem local                   | `ERROR_OFFLINE`             |
| 5   | Online + complexa + cloud disponível  | `CLOUD`                     |
| 6   | Online + local disponível             | `LOCAL_WITH_CLOUD_FALLBACK` |
| 7   | Online + sem local + cloud disponível | `CLOUD`                     |
| 8   | Nada disponível                       | `ERROR_UNAVAILABLE`         |


Todas as regras são testadas em `InferenceRouterResolveRouteTest` (15 testes)
e `InferenceRouterExecutionTest` (11 testes).

---

## Modos pedagógicos (TutorMode)


| Modo        | Prompt local (compacto)     | Prompt cloud (rico)                  |
| ----------- | --------------------------- | ------------------------------------ |
| **EXPLAIN** | Definição + 1 exemplo curto | Analogias, exemplos, subtópicos      |
| **HINT**    | 1 dica/pergunta-guia        | Perguntas socráticas progressivas    |
| **SUMMARY** | 2-3 bullet points           | Tópicos, relações, conclusão         |
| **REVIEW**  | Erros principais + correção | Acertos, erros, sugestões, avaliação |


---

## Testes

Executar testes unitários:

```bash
./gradlew testDebugUnitTest
```


| Classe de teste                   | Cobertura                                     |
| --------------------------------- | --------------------------------------------- |
| `InferenceRouterResolveRouteTest` | Todas as 8 regras de roteamento (função pura) |
| `InferenceRouterExecutionTest`    | Fallback, source, exceções tipadas            |
| `PromptComplexityAnalyzerTest`    | SIMPLE, MODERATE, COMPLEX                     |


Configurações de teste em `app/build.gradle.kts`:

- `testOptions.unitTests.isReturnDefaultValues = true` — permite `android.util.Log` em JVM tests
- `testImplementation(libs.kotlinx.coroutines.test)` — `runTest` para coroutines

---

## Resumo: o que você precisa fazer

### Obrigatório para cloud funcionar

- Criar projeto no [Firebase Console](https://console.firebase.google.com)
- Ativar **Firebase AI Logic** (Build → AI Logic → Gemini Developer API)
- Registrar app Android com IDs `com.voiceassistant.debug` e `com.voiceassistant`
- Baixar `google-services.json` → colocar em `app/`

### Obrigatório para modelo local funcionar

- Baixar modelo `.task` compatível (ex: Gemma 3 1B IT INT4, ~900 MB)
- Criar pasta `app/src/main/assets/models/`
- Copiar `.task` para `assets/models/`
- Se nome ≠ `gemma3-1b-it-int4.task`, ajustar `modelAssetPath` em `ServiceModule`

### Já configurado (nenhuma ação necessária)

- Gradle, plugins, dependências (`libs.versions.toml`)
- AndroidManifest (permissões, features, network security)
- ProGuard (Hilt, Room, Serialization, MediaPipe, Firebase)
- Room database + schema export
- Hilt DI modules
- Splash screen
- STT / TTS nativos
- Modos pedagógicos (EXPLAIN, HINT, SUMMARY, REVIEW)
- InferenceRouter com routing testado
- UI: ChatScreen, MicButton, TutorModeSelector, ModeStatusBar

### Para produção (futuro)

- Substituir `fallbackToDestructiveMigration(true)` por `Migration` real no Room
- Implementar `NavHost` para múltiplas telas (configurações, histórico)
- Adicionar `google-services.json` ao `.gitignore`
- Configurar signing config para build de release
- Testar em dispositivos com RAM baixa (~3 GB) para validar `DeviceCapabilityChecker`

---

## Dependências principais


| Biblioteca                  | Versão         | Uso                              |
| --------------------------- | -------------- | -------------------------------- |
| Jetpack Compose + Material3 | BOM 2024.12.01 | UI declarativa                   |
| Hilt                        | 2.52           | Injeção de dependência           |
| Room                        | 2.6.1          | Banco de dados local (histórico) |
| DataStore Preferences       | 1.1.1          | Configurações do usuário         |
| Firebase BOM                | 34.12.0        | Gerencia versões Firebase        |
| Firebase AI Logic           | via BOM        | Inferência cloud (Gemini)        |
| MediaPipe Tasks GenAI       | 0.10.14        | Inferência local (LLM on-device) |
| Kotlin Coroutines           | 1.9.0          | Programação assíncrona           |
| Android SpeechRecognizer    | SDK            | STT nativo                       |
| Android TextToSpeech        | SDK            | TTS nativo                       |


