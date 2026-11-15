#!/usr/bin/env python3
import os, sys
d = sys.argv[1]
need = ["QPlayer-android-perf.apk", "QPlayer-windows-x64-portable.zip"]
for n in need:
    p = os.path.join(d, n)
    if not os.path.isfile(p) or os.path.getsize(p) < 100000:
        print("missing or empty asset", n, file=sys.stderr); sys.exit(1)
print("release assets OK:", [(n, os.path.getsize(os.path.join(d,n))) for n in need])
