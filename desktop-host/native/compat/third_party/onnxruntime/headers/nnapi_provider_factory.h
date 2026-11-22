// Windows stub for ONNX Runtime's NNAPI provider factory (Android-only header).
//
// VocalRemovalNative.cpp includes this path unconditionally, but guards every use
// behind `constexpr bool kUseNnapiHybrid = false`, so the entry point is never
// reached at runtime. Windows must still see the declarations and a definition in
// order to compile and link, which is what this file plus windows_stubs.cpp provide.
#pragma once

#include <cstdint>

#include "third_party/onnxruntime/headers/onnxruntime_c_api.h"

#ifndef NNAPI_FLAG_USE_FP16
#define NNAPI_FLAG_USE_FP16 0x001
#endif
#ifndef NNAPI_FLAG_CPU_DISABLED
#define NNAPI_FLAG_CPU_DISABLED 0x002
#endif

extern "C" {

/// Never called on desktop (kUseNnapiHybrid is false); implemented in windows_stubs.cpp.
OrtStatus* OrtSessionOptionsAppendExecutionProvider_Nnapi(OrtSessionOptions* options,
                                                          uint32_t nnapi_flags);
}
