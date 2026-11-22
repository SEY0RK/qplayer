#!/usr/bin/env python3
"""Fetch Transpose native assets for the QPlayer vocal spike.

Downloads (from joh9911/Transpose @ main, GPL-3.0) into the Android app module:
  app/src/main/cpp/VocalRemovalNative.cpp
  app/src/main/cpp/signalsmith/signalsmith-linear/fft.h
  app/src/main/cpp/third_party/onnxruntime/headers/*.h  (needed subset)
  app/src/main/jniLibs/arm64-v8a/libonnxruntime.so
  app/src/main/assets/vocal_separation_core.bin

Run from repository root in CI. Uses urllib only (stdlib).
"""
import os
import sys
import urllib.request

REF = "main"
RAW = "https://raw.githubusercontent.com/joh9911/Transpose/%s/" % REF
APP = "android-shell/app/src/main"

# (repo-relative source path, local destination path)
FILES = [
    ("audio-effect/src/main/cpp/VocalRemovalNative.cpp",
     APP + "/cpp/VocalRemovalNative.cpp"),
    ("audio-effect/src/main/cpp/signalsmith/signalsmith-linear/fft.h",
     APP + "/cpp/signalsmith/signalsmith-linear/fft.h"),
    ("audio-effect/src/main/cpp/third_party/onnxruntime/headers/onnxruntime_c_api.h",
     APP + "/cpp/third_party/onnxruntime/headers/onnxruntime_c_api.h"),
    ("audio-effect/src/main/cpp/third_party/onnxruntime/headers/nnapi_provider_factory.h",
     APP + "/cpp/third_party/onnxruntime/headers/nnapi_provider_factory.h"),
    ("audio-effect/src/main/cpp/third_party/onnxruntime/jni/arm64-v8a/libonnxruntime.so",
     APP + "/jniLibs/arm64-v8a/libonnxruntime.so"),
    ("media/src/main/assets/vocal_separation_core.bin",
     APP + "/assets/vocal_separation_core.bin"),
]

EXPECTED_MIN = {
    APP + "/jniLibs/arm64-v8a/libonnxruntime.so": 18_000_000,
    APP + "/assets/vocal_separation_core.bin": 29_000_000,
}


def download(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    url = RAW + src
    req = urllib.request.Request(url, headers={"User-Agent": "qplayer-spike"})
    with urllib.request.urlopen(req, timeout=180) as r, open(dst, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    size = os.path.getsize(dst)
    minimum = EXPECTED_MIN.get(dst, 1)
    if size < minimum:
        raise RuntimeError("too small: %s (%d < %d)" % (dst, size, minimum))
    print("ok %8d  %s" % (size, dst))


def main():
    for src, dst in FILES:
        print("fetch", src)
        download(src, dst)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("FETCH FAILED:", e, file=sys.stderr)
        sys.exit(1)
