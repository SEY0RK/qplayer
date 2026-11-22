// Windows compatibility shim for <android/log.h>.
//
// VocalRemovalNative.cpp is shared verbatim with the Android build (it is fetched
// from upstream at build time and never patched), so the desktop build satisfies its
// Android-only includes through this directory instead of editing the source.
//
// GPL-3.0 note: this shim is original glue, not derived from the upstream file.
#pragma once

#include <cstdarg>
#include <cstdio>

#ifndef ANDROID_LOG_INFO
#define ANDROID_LOG_INFO 4
#endif
#ifndef ANDROID_LOG_ERROR
#define ANDROID_LOG_ERROR 6
#endif

inline int __android_log_print(int /*prio*/, const char* tag, const char* fmt, ...) {
    std::fprintf(stderr, "[%s] ", tag ? tag : "native");
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(stderr, fmt, ap);
    va_end(ap);
    std::fprintf(stderr, "\n");
    return 0;
}
