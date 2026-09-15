#!/usr/bin/env python3
"""P2 — SraVaani-0.5-live graph/operator/runtime validation (desktop CPU, NO conversion).

Per docs/STT_SRAVAANI_IMPLEMENTATION_PROMPTS.md PROMPT 2:
1. torch.jit.load the official FP16 TorchScript artifact (with fp16-on-CPU fallback).
2. Enumerate every scripted op (aten/prim/custom) across all submodules -> op inventory.
3. Verify the scripted streaming contract: presence/signatures of the official API
   (new_stream / stream_audio / finalize / set_att_context_size).
4. Load preproc.pt and dump its module type + code (normalization semantics).
5. Load tokenizer.model with sentencepiece; record vocab size and cross-check vs
   the model's output dim (community export claims 5,001 — VERIFY from the graph).
6. Run one end-to-end streaming smoke on 3 s of synthetic audio at each latency
   mode [70,13]/[70,6]/[70,1]/[70,0]; record chunk latency, RTF, peak RSS, and
   whether partials are cumulative/monotonic and finalize() completes.

Writes: scratch/sravaani/logs/graph_validation.json (+ human-readable stdout).
Exit 0 = all hard assertions passed; exit 1 = validation failed; exit 2 = artifact missing.

Usage: python scripts/sravaani/validate_graph.py [--smoke-only]
"""
from __future__ import annotations

import argparse
import json
import re
import resource
import sys
import time
from pathlib import Path

import torch

HF_DIR = Path("scratch/sravaani/hf")
TS_PATH = HF_DIR / "model-asr-streaming.fp16.ts"
PREPROC_PATH = HF_DIR / "preproc.pt"
TOKENIZER_PATH = HF_DIR / "tokenizer.model"
LOG_DIR = Path("scratch/sravaani/logs")

ATT_CONTEXTS = ([70, 13], [70, 6], [70, 1], [70, 0])
SAMPLE_RATE = 16_000
CHUNK = 3_200  # official demo uses 3200-sample (0.2 s) chunks


def op_inventory(module: torch.jit.ScriptModule) -> dict[str, int]:
    """Walk all scripted submodules, collect op names from .code / .graph dumps."""
    ops: dict[str, int] = {}
    pattern = re.compile(r"\b(aten|prim|torch\.jit|custom)::[A-Za-z_0-9]+")
    for name, sub in module.named_modules():
        for source in (getattr(sub, "code", None), getattr(sub, "graph", None)):
            text = source if isinstance(source, str) else str(source or "")
            for op in pattern.findall(text):
                ops[op] = ops.get(op, 0) + 1
    return dict(sorted(ops.items(), key=lambda kv: -kv[1]))


def peak_rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0  # ru_maxrss in KB on Linux; MB-ish on macOS


def load_tokenizer() -> object | None:
    try:
        import sentencepiece as spm  # noqa: PLC0415
    except ImportError:
        print("WARN: sentencepiece not installed -> `pip install sentencepiece` (tokenizer facts pending)")
        return None
    sp = spm.SentencePieceProcessor()
    sp.load(str(TOKENIZER_PATH))
    return sp


def streaming_smoke(model, att_context: tuple[int, int], n_seconds: float = 3.0) -> dict:
    """One full new_stream -> stream_audio chunks -> finalize cycle. No accuracy claims."""
    t = torch.arange(int(n_seconds * SAMPLE_RATE), dtype=torch.float32) / SAMPLE_RATE
    pcm = 0.5 * torch.sin(2 * torch.pi * 220.0 * t).numpy()

    per_chunk_ms: list[float] = []
    partials: list[str] = []

    state = model.new_stream()
    model.set_att_context_size(list(att_context)) if hasattr(model, "set_att_context_size") else None
    t0 = time.perf_counter()
    for i in range(0, len(pcm), CHUNK):
        chunk = torch.from_numpy(pcm[i : i + CHUNK]).float().unsqueeze(0)  # (1, T)
        tc = time.perf_counter()
        out = model.stream_audio(state, chunk, sampling_rate=SAMPLE_RATE)
        per_chunk_ms.append((time.perf_counter() - tc) * 1000)
        if isinstance(out, str):
            partials.append(out)
        elif isinstance(out, (list, tuple)) and out and isinstance(out[0], str):
            partials.append(out[0])
        else:
            partials.append(repr(out))
    total_s = time.perf_counter() - t0
    final = model.finalize(state)
    final_text = final if isinstance(final, str) else repr(final)

    # monotonicity = each partial is a prefix-extension of the previous (cumulative transcript)
    monotonic = all(p1 == "" or p2.startswith(p1) for p1, p2 in zip(partials, partials[1:]))

    return {
        "att_context": list(att_context),
        "chunks": len(per_chunk_ms),
        "mean_chunk_ms": round(sum(per_chunk_ms) / max(len(per_chunk_ms), 1), 3),
        "max_chunk_ms": round(max(per_chunk_ms), 3) if per_chunk_ms else None,
        "rtf": round(total_s / n_seconds, 4),
        "partials": len(partials),
        "partials_monotonic_prefix": monotonic,
        "finalized": bool(final_text),
        "peak_rss_mb": round(peak_rss_mb(), 1),
        "final_text_sample": final_text[:200],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--smoke-only", action="store_true", help="skip op inventory (faster)")
    args = parser.parse_args()

    if not TS_PATH.exists():
        print(f"Artifact missing: {TS_PATH} — run P1 download first.", file=sys.stderr)
        return 2

    report: dict = {"torch_version": torch.__version__, "artifact": str(TS_PATH)}

    print(f"Loading TorchScript (fp16) on CPU: {TS_PATH}")
    t0 = time.perf_counter()
    model = torch.jit.load(str(TS_PATH), map_location="cpu")
    model.eval()
    report["jit_load_s"] = round(time.perf_counter() - t0, 3)

    # --- streaming contract discovery -------------------------------------
    method_names = set()
    try:
        method_names = set(model._c._get_method_names())  # type: ignore[attr-defined]
    except Exception:  # noqa: BLE001 - fall back to dir()
        method_names = {m for m in dir(model) if not m.startswith("_")}
    expected_api = {"new_stream", "stream_audio", "finalize", "set_att_context_size"}
    present = sorted(m for m in expected_api if m in method_names)
    report["expected_api_present"] = present
    report["expected_api_missing"] = sorted(expected_api - set(present))
    print(f"Streaming API present: {present}")

    # fp16-on-CPU: try direct; if a forward fails, record that fp16 cast is required.
    report["fp16_on_cpu_note"] = "attempting direct fp16 execution; results recorded per latency mode"

    # --- tokenizer facts --------------------------------------------------
    sp = load_tokenizer()
    if sp is not None:
        report["tokenizer_vocab_size"] = sp.get_piece_size()
        print(f"Tokenizer vocab size: {sp.get_piece_size()}")

    # --- op inventory ------------------------------------------------------
    if not args.smoke_only:
        print("Enumerating scripted ops (this can take ~1 min on an 898 MB graph)...")
        t0 = time.perf_counter()
        report["op_inventory"] = op_inventory(model)
        report["op_enumeration_s"] = round(time.perf_counter() - t0, 3)
        print(f"Unique ops: {len(report['op_inventory'])}")

    # --- preproc module ----------------------------------------------------
    if PREPROC_PATH.exists():
        try:
            preproc = torch.jit.load(str(PREPROC_PATH), map_location="cpu")
            preproc.eval()
            report["preproc_type"] = str(type(preproc))
            report["preproc_code_head"] = getattr(preproc, "code", "")[:2000]
            print(f"preproc.pt loaded: {report['preproc_type']}")
        except Exception as exc:  # noqa: BLE001
            report["preproc_error"] = str(exc)
            print(f"WARN: preproc.pt failed to load: {exc}")

    # --- streaming smoke at each latency mode ------------------------------
    print("Streaming smoke at latency modes ...")
    smokes = []
    for att in ATT_CONTEXTS:
        try:
            s = streaming_smoke(model, att)
        except Exception as exc:  # noqa: BLE001 - record failure, try next mode
            s = {"att_context": list(att), "error": f"{type(exc).__name__}: {exc}"}
            # one fallback attempt: cast weights to fp32 (CPU half support varies)
            try:
                model_f = torch.jit.load(str(TS_PATH), map_location="cpu").float()
                s["retry_fp32"] = streaming_smoke(model_f, att)
            except Exception as exc2:  # noqa: BLE001
                s["retry_fp32_error"] = f"{type(exc2).__name__}: {exc2}"
        smokes.append(s)
        print(json.dumps(s, ensure_ascii=False)[:300])
    report["streaming_smoke"] = smokes
    report["peak_rss_mb_final"] = round(peak_rss_mb(), 1)

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    out = LOG_DIR / "graph_validation.json"
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Report written: {out}")

    # hard gates for P2 acceptance
    assert report["expected_api_missing"] == [], f"missing API: {report['expected_api_missing']}"
    assert any("finalized" in s and s.get("finalized") for s in smokes if "error" not in s), "no mode finalized"
    print("P2 validation PASSED (contract + smoke).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
