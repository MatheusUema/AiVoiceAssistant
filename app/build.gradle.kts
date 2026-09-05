plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

// Modelo local ativo. A matriz de testes é SEQUENCIAL (uma bateria completa por modelo),
// então trocar de bateria é trocar esta propriedade — nada de editar Kotlin entre rodadas:
//   ./gradlew :app:assembleDebug -Plocal.model=qwen-1.5b
// Chaves válidas em LocalModelConfig.CATALOG: gemma4-e2b, gemma3-1b, qwen-1.5b, qwen-0.5b.
// O fallback (usado só quando o primário não carrega) sai em -Plocal.model.fallback;
// "none" desliga o fallback.
val localModel: String = providers.gradleProperty("local.model").getOrElse("gemma4-e2b")
val localModelFallback: String =
    providers.gradleProperty("local.model.fallback").getOrElse("gemma3-1b")

// Teto de tokens por resposta. 1024 serve ao chat; a bateria de medição usa 4, que é o
// protocolo do artigo 1 (só a letra) — medido no Device 1, é a única condição viável:
// com teto alto o Gemma 4 gasta tudo raciocinando e não responde em nenhuma questão.
//   ./gradlew :app:assembleDebug -Plocal.maxtokens=4
val localMaxTokens: String = providers.gradleProperty("local.maxtokens").getOrElse("1024")

// Tempo maximo por geracao no tier local. 180 s serve aos aparelhos que rodam o
// modelo em RAM; o Device 2 (Redmi Note 8, 3,6 GB) pagina os pesos em swap e gasta
// ate 144 s so no prefill, entao com 180 s a maioria das questoes e cancelada antes
// de responder -- o que mede o teto, nao o aparelho.
//   ./gradlew :app:assembleDebug -Plocal.timeout=300000
val localTimeoutMs: String = providers.gradleProperty("local.timeout").getOrElse("180000")

android {
    namespace = "com.voiceassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voiceassistant"
        minSdk = 26   // Android 8.0+ — necessário para MediaPipe e Speech APIs modernas
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Exportar esquema do Room para versionamento de migrações
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        buildConfigField("String", "LOCAL_MODEL", "\"$localModel\"")
        buildConfigField("String", "LOCAL_MODEL_FALLBACK", "\"$localModelFallback\"")
        buildConfigField("int", "LOCAL_MAX_TOKENS", localMaxTokens)
        buildConfigField("long", "LOCAL_TIMEOUT_MS", localTimeoutMs + "L")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // O MigrationTestHelper lê os schemas exportados como assets do APK de teste.
    // Sem isto, o teste de migração não encontra o schema da versão de origem.
    sourceSets.getByName("androidTest") {
        assets.srcDirs("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            // O empacotamento do APK final é decidido aqui, não no módulo :llama.
            // Precisa ser legacy (extrair na instalação) porque o ggml faz `dlopen` das
            // variantes de CPU por caminho absoluto — ver comentário em :llama.
            useLegacyPackaging = true
        }
    }

    // Não comprimir modelos LLM — llama.cpp usa mmap sobre o arquivo, sem descompressão
    androidResources {
        noCompress += listOf("bin", "tflite", "task", "litertlm", "gguf")
    }
}

dependencies {
    // --- Tier local: llama.cpp via JNI ---
    implementation(project(":llama"))

    // --- AndroidX Core ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.google.material)

    // --- Compose BOM (versões gerenciadas centralmente) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // --- Hilt (Injeção de Dependência) ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // --- Room (banco de dados local para histórico) ---
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // --- Serialization ---
    implementation(libs.kotlinx.serialization.json)

    // --- Network (Firebase AI Logic + tier servidor llama.cpp) ---
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // --- Firebase BOM + AI Logic (Gemini) ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.analytics)

    // --- MediaPipe LLM Inference (inferência local) ---
    implementation(libs.mediapipe.tasks.genai)

    // --- DataStore (configurações do usuário, modo privacidade) ---
    implementation(libs.datastore.preferences)

    // --- Testes ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
