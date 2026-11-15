#!/usr/bin/env python3
"""Backport upstream netease plugin's `searchPlaylists` into a QPlayer data dir.

Why this exists
---------------
QPlayer 1.9.8 routes playlist search exclusively through plugins that declare
`searchPlaylists` (docs/plugins.md: "requires QPlayer 1.9.7"). Upstream's
netease plugin already implements it on `main` (plugin.json version 0.5.8),
but the newest published GitHub Release is still v0.5.7, so the plugin shipped
in the portable build has no such capability and the UI says
"当前音源不支持歌单搜索".

This script applies the *minimal* upstream change to the installed 0.5.7
plugin (one handler + one capability) and rebuilds the install receipt so the
host's integrity check (PluginManager.verifyInstalledFiles) still passes.

Idempotent: re-running it recomputes the same hashes and changes nothing.

Usage:
    python patch-netease-playlist-search.py <data-dir> [backup-dir]

<data-dir> is the directory containing `config/plugins.json` and
`plugins/netease/<activeVersion>/`.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

CAPABILITY = "searchPlaylists"
RECEIPT_NAME = ".qplayer-install.json"

SEARCH_FN = """function searchPlaylists(args) {
  return searchPage(args, 1000, "playlists", playlistDto);
}

"""

# Upstream 0.5.8 also guards against an empty page that would keep advancing
# the cursor forever. Apply the same guard to the shared helper.
OLD_NEXTCURSOR = """    return {items: items.map(mapper), nextCursor: offset + items.length < total
      ? String(offset + items.length) : ""};"""
NEW_NEXTCURSOR = """    return {items: items.map(mapper), nextCursor: items.length > 0 && offset + items.length < total
      ? String(offset + items.length) : ""};"""


def read_bytes(path: Path) -> bytes:
    return path.read_bytes()


def read_text(path: Path) -> str:
    # decode manually so CRLF is preserved verbatim in the string
    return read_bytes(path).decode("utf-8")


def write_text(path: Path, text: str) -> None:
    path.write_bytes(text.encode("utf-8"))


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def backup(paths: list[Path], root: Path, backup_root: Path) -> None:
    for path in paths:
        rel = path.relative_to(root)
        target = backup_root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(read_bytes(path))


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    data_dir = Path(sys.argv[1]).resolve()
    root_parent = data_dir.parent
    backup_root = (Path(sys.argv[2]).resolve() if len(sys.argv) > 2
                   else root_parent / "_backup-netease-playlist-search")

    registry_path = data_dir / "config" / "plugins.json"
    if not registry_path.is_file():
        print(f"ERROR: registry not found: {registry_path}")
        return 1
    registry = json.loads(read_text(registry_path))
    entry = registry.get("plugins", {}).get("netease")
    if not entry:
        print("ERROR: netease is not installed in this data dir")
        return 1
    version = entry.get("activeVersion")
    plugin_root = data_dir / "plugins" / "netease" / version
    if not plugin_root.is_dir():
        print(f"ERROR: plugin dir not found: {plugin_root}")
        return 1

    manifest_path = plugin_root / "plugin.json"
    main_path = plugin_root / "src" / "main.js"
    receipt_path = plugin_root / RECEIPT_NAME
    for path in (manifest_path, main_path, receipt_path):
        if not path.is_file():
            print(f"ERROR: missing {path}")
            return 1
    backup([manifest_path, main_path, receipt_path, registry_path],
           data_dir, backup_root)

    # ---- plugin.json: advertise the capability -------------------------
    manifest = json.loads(read_text(manifest_path))
    caps = manifest.setdefault("capabilities", [])
    changed = []
    if CAPABILITY not in caps:
        # Keep upstream's ordering: right after searchArtists.
        anchor = "searchArtists"
        pos = caps.index(anchor) + 1 if anchor in caps else len(caps)
        caps.insert(pos, CAPABILITY)
        changed.append(f"plugin.json: +capability {CAPABILITY}")
    # docs/plugins.md: a plugin advertising this capability must require >= 1.9.7.
    if _version_tuple(manifest.get("minHostVersion", "0")) < (1, 9, 7):
        manifest["minHostVersion"] = "1.9.7"
        changed.append("plugin.json: minHostVersion -> 1.9.7")
    if changed:
        write_text(manifest_path, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

    # ---- main.js: one handler + one export + empty-page guard ----------
    js = read_text(main_path)
    eol = "\r\n" if "\r\n" in js else "\n"
    fn = SEARCH_FN.replace("\n", eol)
    if "function searchPlaylists(" not in js:
        anchor = "function searchAlbums(args) {"
        if js.count(anchor) != 1:
            print("ERROR: cannot find a unique searchAlbums anchor")
            return 1
        js = js.replace(anchor, fn + anchor, 1)
        changed.append("main.js: +searchPlaylists handler (cloudsearch type=1000)")
    if "searchPlaylists: searchPlaylists" not in js:
        anchor = "  searchArtists: searchArtists,"
        if js.count(anchor) != 1:
            print("ERROR: cannot find a unique handlers anchor")
            return 1
        js = js.replace(anchor, anchor + eol + "  searchPlaylists: searchPlaylists,", 1)
        changed.append("main.js: +handler registration")
    old_cursor = OLD_NEXTCURSOR.replace("\n", eol)
    new_cursor = NEW_NEXTCURSOR.replace("\n", eol)
    if old_cursor in js:
        js = js.replace(old_cursor, new_cursor, 1)
        changed.append("main.js: empty page must not advance nextCursor (upstream 0.5.8)")
    if any(item.startswith("main.js:") for item in changed):
        write_text(main_path, js)

    # ---- install receipt + registry digest -----------------------------
    files = sorted(
        (p for p in plugin_root.rglob("*") if p.is_file() and p.name != RECEIPT_NAME),
        key=lambda p: p.relative_to(plugin_root).as_posix(),
    )
    hashes = {
        p.relative_to(plugin_root).as_posix(): sha256(read_bytes(p))
        for p in files
    }
    digest = sha256(
        "".join(f"{name}:{value}\n" for name, value in hashes.items()).encode("utf-8")
    )
    receipt = json.loads(read_text(receipt_path))
    receipt["schemaVersion"] = 1
    receipt["packageDigest"] = digest
    receipt["hashes"] = hashes
    write_text(receipt_path, json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")

    if entry.get("packageDigest") != digest:
        entry["packageDigest"] = digest
        changed.append("config/plugins.json: packageDigest refreshed")
    write_text(registry_path, json.dumps(registry, ensure_ascii=False, indent=2) + "\n")

    print(f"netease {version} @ {plugin_root}")
    for item in changed or ["already patched; hashes recomputed (no content change)"]:
        print("  - " + item)
    print(f"  - {len(hashes)} files hashed; packageDigest={digest[:16]}...")
    print(f"  - backup: {backup_root}")
    return 0


def _version_tuple(value: str) -> tuple[int, ...]:
    parts = []
    for chunk in str(value).split("."):
        digits = "".join(c for c in chunk if c.isdigit())
        parts.append(int(digits) if digits else 0)
    return tuple(parts)


if __name__ == "__main__":
    raise SystemExit(main())
