#!/usr/bin/env python3
"""Download the official SraVaani-0.5-live artifacts and emit a provenance manifest.

Usage:
    HF_TOKEN=hf_xxx python scripts/sravaani/download_model.py

Behavior:
- Downloads the full snapshot of ARTPARK-IISc/SraVaani-0.5-live into
  scratch/sravaani/hf/ (gitignored; never committed).
- Writes scratch/sravaani/manifest.json with, per file:
  {filename, size, sha256, hf_oid, hf_commit}
  hf_oid is the git blob id reported by the Hub; sha256 is computed locally.
- Never logs or stores the token.

Pinned commit (verified 2026-09-15 via HF API):
  29a15303a55673367134eee9e1d18cb1a2dcaabf
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path

REPO_ID = "ARTPARK-IISc/SraVaani-0.5-live"
PINNED_REVISION = "29a15303a55673367134eee9e1d18cb1a2dcaabf"
EXPECTED_FILES = {
    "model-asr-streaming.fp16.ts": 898_139_037,
    "preproc.pt": 135_269,
    "tokenizer.model": 325_287,
    "config.json": 870,
    "modeling_sravaani_streaming.py": 26_277,
    "configuration_sravaani_streaming.py": 2_749,
    "example_streaming.py": 2_661,
    "README.md": 10_818,
}
OUT_DIR = Path("scratch/sravaani/hf")
MANIFEST = Path("scratch/sravaani/manifest.json")


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
    token = os.environ.get("HF_TOKEN")
    if not token:
        print("ERROR: HF_TOKEN not set. Export it first (do not pass via file in repo).", file=sys.stderr)
        return 2

    from huggingface_hub import snapshot_download  # local import: fail with clear message only when needed

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {REPO_ID} @ {PINNED_REVISION[:12]} into {OUT_DIR} ...")
    local_dir = snapshot_download(
        repo_id=REPO_ID,
        revision=PINNED_REVISION,
        token=token,
        local_dir=str(OUT_DIR),
    )
    print(f"Snapshot at: {local_dir}")

    # Pull the Hub-side blob oids for cross-checking.
    try:
        from huggingface_hub import HfApi
        info = HfApi().model_info(REPO_ID, revision=PINNED_REVISION, files_metadata=True, token=token)
        hub_sizes = {s.rfilename: s.size for s in info.siblings}
        hub_oids = {s.rfilename: s.lfs.get("oid") if s.lfs else None for s in info.siblings}
    except Exception as exc:  # noqa: BLE001 - record but do not fail the local verification
        print(f"WARN: could not fetch Hub metadata for cross-check: {exc}", file=sys.stderr)
        hub_sizes, hub_oids = {}, {}

    entries = []
    ok = True
    for name, expected_size in EXPECTED_FILES.items():
        p = Path(local_dir) / name
        if not p.exists():
            print(f"MISSING: {name}", file=sys.stderr)
            ok = False
            continue
        actual = p.stat().st_size
        digest = sha256_of(p)
        size_ok = actual == expected_size
        hub_size = hub_sizes.get(name)
        if hub_size is not None and hub_size != actual:
            size_ok = False
        if not size_ok:
            ok = False
        entries.append(
            {
                "filename": name,
                "size": actual,
                "sha256": digest,
                "hf_oid": hub_oids.get(name),
                "hf_commit": PINNED_REVISION,
            }
        )
        print(f"{'OK ' if size_ok else 'BAD'} {name}: {actual} B (expected {expected_size}) sha256={digest[:16]}...")

    manifest = {
        "repo_id": REPO_ID,
        "revision": PINNED_REVISION,
        "downloaded_utc": None,  # filled below
        "files": entries,
    }
    import datetime

    manifest["downloaded_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Manifest written: {MANIFEST}")

    if not ok:
        print("VERIFICATION FAILED — do not proceed to P2 with a bad artifact.", file=sys.stderr)
        return 1
    print("All artifacts verified against pinned sizes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
