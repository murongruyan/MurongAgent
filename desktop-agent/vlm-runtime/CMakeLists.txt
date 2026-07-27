cmake_minimum_required(VERSION 3.22)
project(murong_mnn_vlm LANGUAGES C CXX)

if(NOT DEFINED MNN_SOURCE_DIR)
    message(FATAL_ERROR "MNN_SOURCE_DIR is required")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM_OMNI ON CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(LLM_SUPPORT_HTTP_RESOURCE OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TRAIN OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_QUANTOOLS OFF CACHE BOOL "" FORCE)
set(MNN_WIN_RUNTIME_MT ON CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)

add_subdirectory("${MNN_SOURCE_DIR}" mnn EXCLUDE_FROM_ALL)

add_executable(murong-mnn-vlm murong_mnn_vlm.cpp)
target_include_directories(
    murong-mnn-vlm
    PRIVATE
    "${MNN_SOURCE_DIR}/include"
    "${MNN_SOURCE_DIR}/transformers/llm/engine/include"
)
target_link_libraries(murong-mnn-vlm PRIVATE MNN)
if(MSVC)
    set_property(
        TARGET murong-mnn-vlm
        PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
    )
    target_compile_options(murong-mnn-vlm PRIVATE /utf-8 /EHsc)
endif()
