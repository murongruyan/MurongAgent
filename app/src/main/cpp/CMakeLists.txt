cmake_minimum_required(VERSION 3.22.1)
project(murong_vlm LANGUAGES C CXX)

if(NOT DEFINED MNN_RUNTIME_ROOT OR NOT DEFINED MNN_SOURCE_ROOT)
    message(FATAL_ERROR "MNN_RUNTIME_ROOT and MNN_SOURCE_ROOT are required")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

function(import_mnn_library target file_name)
    add_library(${target} SHARED IMPORTED)
    set_target_properties(
        ${target}
        PROPERTIES IMPORTED_LOCATION
        "${MNN_RUNTIME_ROOT}/${ANDROID_ABI}/${file_name}"
    )
endfunction()

import_mnn_library(mnn_llm libllm.so)
import_mnn_library(mnn_core libMNN.so)
import_mnn_library(mnn_express libMNN_Express.so)

add_library(murong_vlm SHARED murong_vlm_jni.cpp)
target_include_directories(
    murong_vlm
    PRIVATE
    "${MNN_SOURCE_ROOT}/include"
    "${MNN_SOURCE_ROOT}/transformers/llm/engine/include"
)
target_compile_options(murong_vlm PRIVATE -fexceptions -frtti)
target_link_options(murong_vlm PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(
    murong_vlm
    PRIVATE
    mnn_llm
    mnn_express
    mnn_core
    android
    log
)
