cmake_minimum_required(VERSION 3.18)
project(mleqdsp C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libsoxr (LGPL-2.1+): ricampionatore di alta qualità, senza SIMD né OpenMP
set(SOXR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/soxr)
add_library(soxr STATIC
    ${SOXR_DIR}/soxr.c ${SOXR_DIR}/data-io.c ${SOXR_DIR}/dbesi0.c ${SOXR_DIR}/filter.c
    ${SOXR_DIR}/fft4g64.c ${SOXR_DIR}/fft4g32.c ${SOXR_DIR}/cr.c ${SOXR_DIR}/cr32.c ${SOXR_DIR}/cr64.c)
target_include_directories(soxr PRIVATE ${SOXR_DIR})
target_compile_definitions(soxr PRIVATE SOXR_LIB NDEBUG)
target_compile_options(soxr PRIVATE -O3 -w -fPIC)

add_library(mleqdsp SHARED mleq_dsp_jni.cpp)
target_include_directories(mleqdsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(mleqdsp soxr log)
target_compile_options(mleqdsp PRIVATE -O3 -fno-math-errno)
# Android 15: pagine da 16 KB
target_link_options(mleqdsp PRIVATE -Wl,-z,max-page-size=16384)
