pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Repositório para Google AI Edge / MediaPipe
        maven { url = uri("https://storage.googleapis.com/mediapipe-maven") }
    }
}

rootProject.name = "VoiceAssistant"
include(":app")
// Runtime local: ponte JNI sobre llama.cpp (substitui o MediaPipe no tier LOCAL).
include(":llama")
