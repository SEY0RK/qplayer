cmake_minimum_required(VERSION 3.22)
project(qplayer_vocal_windows CXX)

# Desktop (x64) build of the Transpose vocal-separation JNI library.
#
# Differences from the Android CMakeLists:
#   * no `log` / `android` system libraries (replaced by compat/android/log.h)
#   * NNAPI provider satisfied by a link stub (never called: kUseNnapiHybrid=false)
#   * links the official ONNX Runtime x64 import library instead of a jniLibs .so
#
# Source layout (all placed by .github/scripts/prepare-vocal-windows.py, none committed):
#   native/VocalRemovalNative.cpp
#   native/signalsmith/signalsmith-linear/fft.h
#   native/third_party/onnxruntime/headers/onnxruntime_c_api.h
#   native/ort/                     <- unpacked onnxruntime-win-x64 package

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ORT_DIR)
    message(FATAL_ERROR "ORT_DIR must point at the unpacked onnxruntime-win-x64 package")
endif()

# `compat` must come AFTER the source dir so that the real ONNX Runtime headers win;
# it only supplies the Android-only paths (android/log.h, nnapi_provider_factory.h).
include_directories(${CMAKE_SOURCE_DIR})
include_directories(${CMAKE_SOURCE_DIR}/compat)

# <jni.h> comes from the JDK (setup-java provides JAVA_HOME on CI).
find_package(JNI REQUIRED)

add_library(vocal_removal_native SHARED
    VocalRemovalNative.cpp
    windows_stubs.cpp)

# /fp:fast mirrors the -ffast-math used on Android (the STFT math assumes it).
target_compile_options(vocal_removal_native PRIVATE /O2 /fp:fast)
target_include_directories(vocal_removal_native PRIVATE ${JNI_INCLUDE_DIRS})

target_link_directories(vocal_removal_native PRIVATE ${ORT_DIR}/lib)
target_link_libraries(vocal_removal_native onnxruntime)

# Ship onnxruntime.dll next to the JNI library so System.loadLibrary resolves it.
add_custom_command(TARGET vocal_removal_native POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${ORT_DIR}/lib/onnxruntime.dll
            $<TARGET_FILE_DIR:vocal_removal_native>
    COMMENT "Staging onnxruntime.dll beside vocal_removal_native.dll")
