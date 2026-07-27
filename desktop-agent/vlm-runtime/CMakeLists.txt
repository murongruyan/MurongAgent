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

# CMake's Visual Studio generator sends MNN's preprocessed .S sources through
# ClCompile with /TP, which makes clang-cl parse ".macro" as C++. Compile those
# files directly as AArch64 assembly and add the resulting COFF objects to the
# final MNN static library. MNNARM64 keeps compiling its C++ NEON dispatcher.
if(MSVC AND TARGET MNNARM64)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        message(FATAL_ERROR "Windows ARM64 MNN assembly requires the ClangCL toolset")
    endif()

    get_target_property(MURONG_MNN_ARM64_SOURCES MNNARM64 SOURCES)
    set(MURONG_MNN_ARM64_ASSEMBLY ${MURONG_MNN_ARM64_SOURCES})
    list(FILTER MURONG_MNN_ARM64_ASSEMBLY INCLUDE REGEX "\\.[sS]$")
    set(MURONG_MNN_ARM64_COMPILED_SOURCES ${MURONG_MNN_ARM64_SOURCES})
    list(FILTER MURONG_MNN_ARM64_COMPILED_SOURCES EXCLUDE REGEX "\\.[sS]$")

    if(MURONG_MNN_ARM64_ASSEMBLY)
        set(MURONG_MNN_ARM_INCLUDE_DIR "${MNN_SOURCE_DIR}/source/backend/cpu/arm")
        set(MURONG_MNN_ARM64_OBJECT_DIR "${CMAKE_CURRENT_BINARY_DIR}/mnn-arm64-asm")
        set(MURONG_MNN_ARM64_OBJECTS)
        set(MURONG_MNN_ARM64_DEFINITIONS /D__aarch64__)
        if(MNN_SUPPORT_BF16)
            list(APPEND MURONG_MNN_ARM64_DEFINITIONS /DMNN_SUPPORT_BF16)
        endif()

        foreach(MURONG_MNN_ARM64_SOURCE IN LISTS MURONG_MNN_ARM64_ASSEMBLY)
            get_filename_component(MURONG_MNN_ARM64_NAME "${MURONG_MNN_ARM64_SOURCE}" NAME_WE)
            string(MD5 MURONG_MNN_ARM64_SOURCE_ID "${MURONG_MNN_ARM64_SOURCE}")
            set(
                MURONG_MNN_ARM64_OBJECT
                "${MURONG_MNN_ARM64_OBJECT_DIR}/${MURONG_MNN_ARM64_NAME}-${MURONG_MNN_ARM64_SOURCE_ID}.obj"
            )
            add_custom_command(
                OUTPUT "${MURONG_MNN_ARM64_OBJECT}"
                COMMAND
                    "${CMAKE_C_COMPILER}"
                    --target=arm64-pc-windows-msvc
                    /nologo
                    /c
                    ${MURONG_MNN_ARM64_DEFINITIONS}
                    "/I${MURONG_MNN_ARM_INCLUDE_DIR}"
                    "${MURONG_MNN_ARM64_SOURCE}"
                    "/Fo${MURONG_MNN_ARM64_OBJECT}"
                DEPENDS
                    "${MURONG_MNN_ARM64_SOURCE}"
                    "${MURONG_MNN_ARM_INCLUDE_DIR}/MNNAsmGlobal.h"
                COMMENT "Compiling MNN AArch64 assembly ${MURONG_MNN_ARM64_NAME}"
                VERBATIM
            )
            list(APPEND MURONG_MNN_ARM64_OBJECTS "${MURONG_MNN_ARM64_OBJECT}")
        endforeach()

        add_custom_target(murong-mnn-arm64-assembly DEPENDS ${MURONG_MNN_ARM64_OBJECTS})
        add_dependencies(MNNARM64 murong-mnn-arm64-assembly)
        add_dependencies(MNN murong-mnn-arm64-assembly)
        set_property(
            TARGET MNNARM64
            PROPERTY SOURCES ${MURONG_MNN_ARM64_COMPILED_SOURCES}
        )
        target_sources(MNN PRIVATE ${MURONG_MNN_ARM64_OBJECTS})
        set_source_files_properties(
            ${MURONG_MNN_ARM64_OBJECTS}
            TARGET_DIRECTORY MNN
            PROPERTIES GENERATED TRUE EXTERNAL_OBJECT TRUE
        )
    endif()
endif()

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
