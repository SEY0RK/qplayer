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
ORT_URL = ("https://github.com/microsoft/onnxruntime/releases/download/v%s/%s"
           % (ORT_VERSION, ORT_ZIP))

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


def main():
    for src, dst, minimum in FILES:
        print("fetch", src)
        fetch(RAW + src, dst, minimum)

    ort_dir = NATIVE + "/ort"
    marker = ort_dir + "/include/onnxruntime_c_api.h"
    if os.path.isfile(marker):
        print("ORT already unpacked, skipping:", ort_dir)
    else:
        tmp_zip = NATIVE + "/" + ORT_ZIP
        print("fetch", ORT_URL)
        fetch(ORT_URL, tmp_zip, 20_000_000)
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
