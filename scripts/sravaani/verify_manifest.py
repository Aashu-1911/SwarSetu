#!/usr/bin/env python3
"""Re-verify SraVaani artifacts against scratch/sravaani/manifest.json.

Exit 0 = all sizes and SHA-256 digests match the manifest.
Exit 1 = mismatch or missing file. Run this before every milestone that touches weights.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

MANIFEST = Path("scratch/sravaani/manifest.json")
HF_DIR = Path("scratch/sravaani/hf")


def sha256_of(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            block = f.read(chunk)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


def main() -> int:
    if not MANIFEST.exists():
        print("No manifest found — run download_model.py first.", file=sys.stderr)
        return 1
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    ok = True
    for entry in manifest["files"]:
        p = HF_DIR / entry["filename"]
        if not p.exists():
            print(f"MISSING: {entry['filename']}", file=sys.stderr)
            ok = False
            continue
        size_ok = p.stat().st_size == entry["size"]
        digest = sha256_of(p)
        hash_ok = digest == entry["sha256"]
        if not (size_ok and hash_ok):
            ok = False
        print(f"{'OK ' if size_ok and hash_ok else 'BAD'} {entry['filename']} size={size_ok} sha256={hash_ok}")
    print(f"Revision: {manifest['revision']}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
