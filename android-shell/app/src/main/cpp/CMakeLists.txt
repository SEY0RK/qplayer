cmake_minimum_required(VERSION 3.22.1)
project("qplayer_vocal")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Third-party sources are downloaded by CI into this directory (not committed):
#   VocalRemovalNative.cpp          (joh9911/Transpose, GPL-3.0)
#   signalsmith/signalsmith-linear/fft.h
#   third_party/onnxruntime/headers/*.h
#   jniLibs/${ANDROID_ABI}/libonnxruntime.so
include_directories(${CMAKE_SOURCE_DIR})

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so)

add_library(vocal_removal_native SHARED VocalRemovalNative.cpp)

target_compile_options(vocal_removal_native PRIVATE -O3 -ffast-math -fsigned-char)

target_link_libraries(vocal_removal_native onnxruntime log android)
