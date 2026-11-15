#!/usr/bin/env python3
"""Fail if upstream sync overwrote any local IME file."""
import hashlib, json, os, sys
root = os.getcwd()
cfg = json.load(open(os.path.join(root, ".github/ime-guard.json"), encoding="utf-8"))
bad = []
for line in open(os.path.join(root, ".github/IME_BASELINE.sha256"), encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"): continue
    want, path = line.split(None, 1)
    f = os.path.join(root, path)
    if not os.path.isfile(f): bad.append("MISSING " + path); continue
    got = hashlib.sha256(open(f, "rb").read()).hexdigest()
    if got != want: bad.append("CHANGED " + path)
for path in cfg["negative"]:
    if os.path.exists(os.path.join(root, path)): bad.append("REINTRODUCED " + path)
for path, needles in cfg["forbidden_content"].items():
    f = os.path.join(root, path)
    if os.path.isfile(f):
        data = open(f, "rb").read()
        for n in needles:
            if n.encode() in data: bad.append("FORBIDDEN " + n + " in " + path)
if bad:
    print("IME guard FAILED://n  " + "\n  ".join(bad), file=sys.stderr); sys.exit(1)
print("IME guard OK:", len(cfg["positive"]), "protected files,", len(cfg["negative"]), "deleted-state checks")
