#!/usr/bin/env python3
"""Download the official SraVaani 1.0 FP16 artifacts from Hugging Face.

Repository: ARTPARK-IISc/SraVaani-1.0
Artifacts:
  - model-asr.fp16.ts (~909 MB)
  - preproc.pt (~135 KB)
  - tokenizer.model (~325 KB)
  - config.json (~561 B)
  - model_meta.json (~257 B)
"""
from __future__ import annotations

import hashlib
import json
import os
import ssl
import sys
import urllib.request
from pathlib import Path

REPO_ID = "ARTPARK-IISc/SraVaani-1.0"
OUT_DIR = Path("scratch/sravaani10/hf")
MANIFEST_PATH = Path("scratch/sravaani10/manifest.json")

EXPECTED_FILES = [
    "model-asr.fp16.ts",
    "preproc.pt",
    "tokenizer.model",
    "config.json",
    "model_meta.json",
    "modeling_sravaani.py",
    "configuration_sravaani.py",
    "processing_sravaani.py",
    "example_inference.py",
]

def sha256_of(path: Path, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            block = f.read(chunk)
            if not block:
                break
            h.update(block)
    return h.hexdigest()

def get_token() -> str:
    token = os.environ.get("HF_TOKEN", "").strip()
    if not token:
        p = Path.home() / ".cache" / "huggingface" / "token"
        if p.exists():
            token = p.read_text().strip()
    return token

def main() -> int:
    token = get_token()
    if not token:
        print("ERROR: Hugging Face token not found in env or ~/.cache/huggingface/token", file=sys.stderr)
        return 1

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    ctx = ssl._create_unverified_context()
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Authorization": f"Bearer {token}",
    }

    manifest = {}
    print(f"Downloading official SraVaani 1.0 artifacts from https://huggingface.co/{REPO_ID}...")

    for fname in EXPECTED_FILES:
        target_path = OUT_DIR / fname
        url = f"https://huggingface.co/{REPO_ID}/resolve/main/{fname}"
        
        req = urllib.request.Request(url, headers=headers)
        try:
            print(f"Fetching {fname}...", end="", flush=True)
            with urllib.request.urlopen(req, context=ctx) as resp:
                total_size = resp.headers.get("Content-Length")
                total_size_int = int(total_size) if total_size else 0
                
                # If file already exists and size matches, check hash
                if target_path.exists() and total_size_int > 0 and target_path.stat().st_size == total_size_int:
                    sha = sha256_of(target_path)
                    manifest[fname] = {
                        "size": total_size_int,
                        "sha256": sha,
                    }
                    print(f" (cached: {total_size_int:,} bytes, sha: {sha[:12]}...)")
                    continue

                tmp_path = OUT_DIR / f"{fname}.tmp"
                downloaded = 0
                with tmp_path.open("wb") as out_file:
                    while True:
                        buf = resp.read(1 << 20)  # 1 MB
                        if not buf:
                            break
                        out_file.write(buf)
                        downloaded += len(buf)
                        if total_size_int > 0:
                            pct = int(downloaded * 100 / total_size_int)
                            print(f"\rFetching {fname}... {pct}% ({downloaded // (1024*1024)}MB / {total_size_int // (1024*1024)}MB)", end="", flush=True)
                
                if tmp_path.exists():
                    if target_path.exists():
                        target_path.unlink()
                    tmp_path.rename(target_path)
                
                sha = sha256_of(target_path)
                actual_size = target_path.stat().st_size
                manifest[fname] = {
                    "size": actual_size,
                    "sha256": sha,
                }
                print(f"\rFetching {fname}... DONE ({actual_size:,} bytes, sha256: {sha})")
        except urllib.error.HTTPError as e:
            print(f"\nHTTPError {e.code} for {fname}: {e.read().decode()}", file=sys.stderr)
            return 2
        except Exception as e:
            print(f"\nError downloading {fname}: {e}", file=sys.stderr)
            return 3

    with MANIFEST_PATH.open("w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"\nManifest saved to {MANIFEST_PATH}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
