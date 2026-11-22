#!/usr/bin/env python3
"""Prepare the Windows x64 build of the vocal-separation JNI library.

Fetches (nothing is committed to the repository):
  desktop-host/native/VocalRemovalNative.cpp            (joh9911/Transpose, GPL-3.0)
  desktop-host/native/signalsmith/signalsmith-linear/fft.h
  desktop-host/native/third_party/onnxruntime/headers/onnxruntime_c_api.h
  desktop-host/native/ort/                              (onnxruntime-win-x64-1.21.0)

The ONNX Runtime version is pinned to 1.21.x because the upstream header declares
ORT_API_VERSION 21; a runtime exposing a lower API would fail to load the library.

Run from the repository root. Stdlib only.
"""
import os
import sys
import urllib.request
import zipfile

REF = "main"
RAW = "https://raw.githubusercontent.com/joh9911/Transpose/%s/" % REF
ORT_VERSION = "1.21.0"
ORT_ZIP = "onnxruntime-win-x64-%s.zip" % ORT_VERSION
# GitHub's release asset path 404s for this repo from some networks; the npmmirror
# binary mirror serves the identical artefact, so try upstream first and fall back.
ORT_URLS = [
    "https://github.com/microsoft/onnxruntime/releases/download/v%s/%s"
    % (ORT_VERSION, ORT_ZIP),
    "https://registry.npmmirror.com/-/binary/onnxruntime/v%s/%s"
    % (ORT_VERSION, ORT_ZIP),
]

NATIVE = "desktop-host/native"

FILES = [
    ("audio-effect/src/main/cpp/VocalRemovalNative.cpp",
     NATIVE + "/VocalRemovalNative.cpp", 20_000),
    ("audio-effect/src/main/cpp/signalsmith/signalsmith-linear/fft.h",
     NATIVE + "/signalsmith/signalsmith-linear/fft.h", 20_000),
    ("audio-effect/src/main/cpp/third_party/onnxruntime/headers/onnxruntime_c_api.h",
     NATIVE + "/third_party/onnxruntime/headers/onnxruntime_c_api.h", 100_000),
]


def fetch(url, dst, minimum=1):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "qplayer-desktop"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dst, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    size = os.path.getsize(dst)
    if size < minimum:
        raise RuntimeError("too small: %s (%d < %d)" % (dst, size, minimum))
    print("ok %10d  %s" % (size, dst))


def patch_for_windows(path):
    """Apply the two minimal platform adaptations the upstream source needs.

    Upstream targets Android only, so it (a) includes <android/log.h> and (b) passes
    a UTF-8 char* model path to OrtCreateSession. On Windows the ONNX Runtime API
    takes a wchar_t* path. Everything else compiles unchanged.
    """
    src = open(path, encoding='utf-8').read()

    if '#include <windows.h>' in src:
        print('already patched:', path)
        return

    anchor_inc = '#include <jni.h>\n#include <android/log.h>'
    if anchor_inc not in src:
        raise RuntimeError('include anchor not found')
    src = src.replace(anchor_inc,
                      '#include <jni.h>\n#include <android/log.h>\n'
                      '#ifdef _WIN32\n'
                      '#ifndef NOMINMAX\n#define NOMINMAX\n#endif\n'
                      '#include <windows.h>\n'
                      '#endif', 1)

    anchor_ns = 'namespace {\n\nconstexpr float kInt16ToFloat'
    if anchor_ns not in src:
        raise RuntimeError('namespace anchor not found')
    src = src.replace(anchor_ns, '''namespace {

#ifdef _WIN32
// Windows ONNX Runtime takes the model path as wchar_t*. The Java side hands us
// UTF-8, and the app path may contain non-ASCII characters (user name), so convert
// through MultiByteToWideChar rather than widening bytes blindly.
static std::wstring qplayerUtf8ToWide(const char* s) {
    if (s == nullptr) return std::wstring();
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 1) return std::wstring();
    std::wstring w(static_cast<size_t>(n), L'\\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n);
    w.resize(static_cast<size_t>(n - 1));
    return w;
}
#endif

constexpr float kInt16ToFloat''', 1)

    anchor_call = 'status = api->CreateSession(env, modelPath, options, &session);'
    if anchor_call not in src:
        raise RuntimeError('CreateSession anchor not found')
    src = src.replace(anchor_call, '''#ifdef _WIN32
        // Windows builds of ONNX Runtime take a wide-character model path.
        const std::wstring wideModelPath = qplayerUtf8ToWide(modelPath);
        status = api->CreateSession(env, wideModelPath.c_str(), options, &session);
#else
        status = api->CreateSession(env, modelPath, options, &session);
#endif''', 1)

    open(path, 'w', encoding='utf-8', newline='\n').write(src)
    print('patched for windows:', path)


def main():
    for src, dst, minimum in FILES:
        print("fetch", src)
        fetch(RAW + src, dst, minimum)

    patch_for_windows(NATIVE + "/VocalRemovalNative.cpp")

    ort_dir = NATIVE + "/ort"
    marker = ort_dir + "/include/onnxruntime_c_api.h"
    if os.path.isfile(marker):
        print("ORT already unpacked, skipping:", ort_dir)
    else:
        tmp_zip = NATIVE + "/" + ORT_ZIP
        last = None
        for url in ORT_URLS:
            try:
                print("fetch", url)
                fetch(url, tmp_zip, 20_000_000)
                last = None
                break
            except Exception as e:
                print("  failed:", e)
                last = e
        if last is not None:
            raise RuntimeError("all ONNX Runtime mirrors failed: %s" % last)
        with zipfile.ZipFile(tmp_zip) as z:
            z.extractall(NATIVE)
        os.replace(NATIVE + "/onnxruntime-win-x64-" + ORT_VERSION, ort_dir)
        os.remove(tmp_zip)
        print("unpacked ->", ort_dir)

    for required in (ort_dir + "/include/onnxruntime_c_api.h",
                     ort_dir + "/lib/onnxruntime.lib",
                     ort_dir + "/lib/onnxruntime.dll"):
        if not os.path.isfile(required):
            raise RuntimeError("missing after unpack: " + required)
    print("ORT layout OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("PREPARE FAILED:", e, file=sys.stderr)
        sys.exit(1)
