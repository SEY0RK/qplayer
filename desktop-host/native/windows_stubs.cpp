// Definitions for the Android-only symbols that VocalRemovalNative.cpp references.
//
// The NNAPI provider is behind `kUseNnapiHybrid = false`, so this stub is a link
// anchor only. If it ever does get called it reports failure (non-null OrtStatus is
// the error convention), which would surface in the BACKEND_ATTACH log line instead
// of silently misbehaving.
#include "third_party/onnxruntime/headers/onnxruntime_c_api.h"
#include "third_party/onnxruntime/headers/nnapi_provider_factory.h"

extern "C" OrtStatus* OrtSessionOptionsAppendExecutionProvider_Nnapi(
        OrtSessionOptions* /*options*/, uint32_t /*nnapi_flags*/) {
    return nullptr;   // nullptr == success in the ORT C API; path is unreachable
}
