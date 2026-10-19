# CMakeLists.txt — Bonsai (llama.cpp + kernels ternarios PQ2_0/PTQ1_0 de PrismML)
#
# El fork oficial es https://github.com/PrismML-Eng/llama.cpp (rama `prism`).
# Debe estar clonado en app/src/main/cpp/llama.cpp:
#
#   git clone --depth 1 --branch prism https://github.com/PrismML-Eng/llama.cpp.git app/src/main/cpp/llama.cpp

cmake_minimum_required(VERSION 3.22.1)
project("bonsai" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp (fork PrismML-Eng)
# ---------------------------------------------------------------------------
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "No se encontro llama.cpp en ${LLAMA_DIR}.\n"
        "Clona el fork con:\n"
        "  git clone --depth 1 --branch prism https://github.com/PrismML-Eng/llama.cpp.git app/src/main/cpp/llama.cpp")
endif()

# ---------------------------------------------------------------------------
# Optimizaciones ARM
# ---------------------------------------------------------------------------
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8.0-A baseline: funciona en Cortex-A53 (Redmi A2/Helio G36) y posteriores.
    # NO añadir +dotprod ni +fp16: esas CPUs no los implementan y el proceso
    # muere con SIGILL (ILL_ILLOPC) al construir el grafo.
    set(BONSAI_ARCH_FLAGS "-march=armv8-a")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${BONSAI_ARCH_FLAGS} -O3")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${BONSAI_ARCH_FLAGS} -O3")
elseif (ANDROID_ABI STREQUAL "x86_64")
    # Emulador: AVX2; los kernels ternarios usan el fallback escalar.
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3 -mavx2")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -mavx2")
endif()

# ---------------------------------------------------------------------------
# Opciones de build: solo librerías, enlazadas estáticas dentro de bonsai-jni
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# OpenMP no es fiable con el NDK; ggml usa su threadpool interno.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama.cpp-build)

# ---------------------------------------------------------------------------
# Biblioteca JNI
# ---------------------------------------------------------------------------
add_library(bonsai-jni SHARED llama_jni.cpp)

target_link_libraries(bonsai-jni PRIVATE
    llama
    log
    android
)

target_include_directories(bonsai-jni PRIVATE "${LLAMA_DIR}")
