plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ── Toggles de build (gradle.properties ou -P na linha de comando) ───────────
// llama.abis      : ABIs a compilar. Default arm64-v8a (os 3 devices do estudo).
//                   Use "arm64-v8a,x86_64" para rodar em emulador.
// llama.ndk       : versão do NDK.
// llama.cmake     : versão do CMake do SDK.
// llama.vulkan    : "true" liga o backend Vulkan (extensão medida à parte; baseline é CPU).
// llama.kleidiai  : "true" liga kernels KleidiAI (baixa fontes em tempo de build).
val llamaAbis: List<String> = providers.gradleProperty("llama.abis")
    .getOrElse("arm64-v8a")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
val llamaNdk: String = providers.gradleProperty("llama.ndk").getOrElse("27.2.12479018")
val llamaCmake: String = providers.gradleProperty("llama.cmake").getOrElse("3.22.1")
val llamaVulkan: Boolean = providers.gradleProperty("llama.vulkan").getOrElse("false").toBoolean()
val llamaKleidiai: Boolean = providers.gradleProperty("llama.kleidiai").getOrElse("false").toBoolean()

android {
    namespace = "com.voiceassistant.llama"
    compileSdk = 35
    ndkVersion = llamaNdk

    defaultConfig {
        // Mesmo mínimo do :app. Devices 2 e 3 do estudo são Android 10 (API 29).
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += llamaAbis
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DGGML_VULKAN=${if (llamaVulkan) "ON" else "OFF"}"
                arguments += "-DGGML_CPU_KLEIDIAI=${if (llamaKleidiai) "ON" else "OFF"}"
                // Baseline do protocolo (doc 04 §8): mesmas flags entre execuções.
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = llamaCmake
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Extrair as .so na instalação é obrigatório aqui: com GGML_BACKEND_DL o ggml
            // faz `dlopen` das variantes de CPU por caminho absoluto, e isso exige arquivos
            // reais em disco. Sem extrair, o diretório de libs nem existe e nenhum backend
            // é registrado — o app carrega e não gera nada.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
