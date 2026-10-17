cmake_minimum_required(VERSION 3.22.1)
project(despejo C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# whisper.cpp é baixado pelo GitHub Actions nesta pasta (veja .github/workflows/android.yml).
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
# Celulares Android recentes (incluindo o S25) têm estas instruções; deixam o Whisper bem mais rápido.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper)

add_library(despejo SHARED despejo_jni.c)
target_link_libraries(despejo PRIVATE whisper log)
