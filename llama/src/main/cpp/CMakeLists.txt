# Build nativo do módulo :llama — ponte JNI sobre llama.cpp.
#
# Baseado no exemplo oficial `examples/llama.android/lib` do repositório ggml-org/llama.cpp.
# A fonte do llama.cpp vive em `llama.cpp/` (submódulo git fixado por tag — ver README do módulo).
#
# Baseline reprodutível (Fase 1): CPU-only, sem KleidiAI, sem Vulkan.
# Vulkan e KleidiAI entram como extensões medidas à parte (propriedades Gradle).

cmake_minimum_required(VERSION 3.22.1)

project("llama_bridge" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "Fonte do llama.cpp ausente em ${LLAMA_SRC}.\n"
        "Rode: git submodule update --init --recursive")
endif()

# ── Configuração por ABI ─────────────────────────────────────────────────────
# Precisa vir ANTES do add_subdirectory: o ggml lê estas variáveis na configuração.
if(DEFINED ANDROID_ABI)
    message(STATUS "llama_bridge: Android ABI = ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        # Só para validar o build/smoke-test em emulador; não é alvo de medição.
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "ABI não suportada pelo :llama: ${ANDROID_ABI}")
    endif()
endif()

# KleidiAI é opt-in (-Dllama.kleidiai=true): baixa fontes em tempo de build (FetchContent),
# o que quebra builds offline e a reprodutibilidade do baseline.
if(NOT DEFINED GGML_CPU_KLEIDIAI)
    set(GGML_CPU_KLEIDIAI OFF)
endif()

# ── Knobs do llama.cpp ───────────────────────────────────────────────────────
# LLAMA_BUILD_* têm default ${LLAMA_STANDALONE} (=OFF via add_subdirectory);
# só COMMON precisa ser ligado explicitamente (chat templates, tokenizer helpers, sampler).
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE    OFF CACHE BOOL "" FORCE)  # cross-compile: nada de -march=native
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

# ── Ponte JNI ────────────────────────────────────────────────────────────────
add_library(llama_bridge SHARED llama_bridge.cpp)

target_include_directories(llama_bridge PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(llama_bridge
        llama
        llama-common
        android
        log)
