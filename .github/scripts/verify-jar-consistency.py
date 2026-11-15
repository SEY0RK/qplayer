#!/usr/bin/env python3
import os, sys
root = os.path.join(os.getcwd(), "desktop-host", "target", "QPlayer", "app", "lib")
if not os.path.isdir(root):
    print("missing app/lib", file=sys.stderr); sys.exit(1)
jars = [f for f in os.listdir(root) if f.endswith(".jar")]
if len(jars) < 30:
    print("too few jars", len(jars), file=sys.stderr); sys.exit(1)
for key in ("qml4j-core-v0.2.38.jar", "player-core-0.1.0-SNAPSHOT.jar"):
    if key not in jars:
        print("missing critical jar", key, file=sys.stderr); sys.exit(1)
print("jar consistency OK:", len(jars), "jars")
