#!/usr/bin/env python3
"""P2 smoke — run the OFFICIAL SraVaaniStreamingModel wrapper (no reimplementation).

Loads the downloaded remote-code module directly (no Hub re-download), verifies the
streaming API contract end-to-end on synthetic audio at every trained latency mode,
and records the model facts needed for the Android runtime decision.

Writes: scratch/sravaani/logs/p2_smoke.json
Exit 0 = contract + finalize verified; exit 1 = failure (BLOCKED evidence).
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):  # Windows cp1252 console vs Indic-script output
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import numpy as np
import torch

sys.path.insert(0, str(Path("scratch/sravaani")))
from hf.modeling_sravaani_streaming import SraVaaniStreamingModel  # noqa: E402
from hf.configuration_sravaani_streaming import SraVaaniStreamingConfig  # noqa: E402

HF_DIR = Path("scratch/sravaani/hf")
LOG = Path("scratch/sravaani/logs/p2_smoke.json")
ATT_CONTEXTS = ([70, 13], [70, 6], [70, 1], [70, 0])


def peak_rss_mb() -> float | None:
    try:
        import resource
        return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0
    except ImportError:
        pass
    try:
        import ctypes
        # Modern Windows moved psapi functions into kernel32 (K32* aliases); try both.
        for dll_name, fn_name in (("psapi", "GetProcessMemoryInfo"), ("kernel32", "K32GetProcessMemoryInfo")):
            try:
                dll = ctypes.WinDLL(dll_name)
                fn = getattr(dll, fn_name)

                class PMC(ctypes.Structure):
                    _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong),
                                ("PeakWorkingSetSize", ctypes.c_size_t), ("WorkingSetSize", ctypes.c_size_t),
                                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                                ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]

                pmc = PMC()
                pmc.cb = ctypes.sizeof(pmc)
                h = dll.GetCurrentProcess() if hasattr(dll, "GetCurrentProcess") else ctypes.windll.kernel32.GetCurrentProcess()
                if fn(h, ctypes.byref(pmc), ctypes.sizeof(pmc)):
                    return pmc.PeakWorkingSetSize / (1024.0 * 1024.0)
            except Exception:  # noqa: BLE001 - try next candidate
                continue
    except Exception:  # noqa: BLE001
        pass
    return None


def main() -> int:
    if not (HF_DIR / "model-asr-streaming.fp16.ts").exists():
        print("Artifact missing — run P1 first.", file=sys.stderr)
        return 2

    report: dict = {"torch": torch.__version__}
    cfg = SraVaaniStreamingConfig.from_pretrained(str(HF_DIR), local_files_only=True)
    model = SraVaaniStreamingModel(cfg)
    model._artifacts_dir = str(HF_DIR)
    model.eval()

    api = ["new_stream", "stream_audio", "finalize", "set_att_context_size", "set_lookahead_ms"]
    report["api_present"] = [m for m in api if hasattr(model, m)]
    report["api_missing"] = [m for m in api if not hasattr(model, m)]
    print(f"API present: {report['api_present']}")

    t0 = time.perf_counter()
    model._ensure_loaded()
    report["load_s"] = round(time.perf_counter() - t0, 2)
    report["io_dtype"] = str(model._io_dtype)
    rss0 = peak_rss_mb()
    report["rss_after_load_mb"] = round(rss0, 1) if rss0 else None
    print(f"Loaded in {report['load_s']}s, io_dtype={report['io_dtype']}, RSS={report['rss_after_load_mb']}MB")

    # encoder sub-structure facts (for the runtime decision)
    report["graph_top_modules"] = sorted(dict(model.encoder.named_children()).keys()) if model.encoder else []
    n_params = sum(p.numel() for p in model.encoder.parameters()) + sum(
        p.numel() for p in model.ctc_decoder.parameters())
    report["graph_param_count"] = n_params
    report["io_features"] = {
        "mel_bins": cfg.feat_in, "sample_rate": cfg.sample_rate,
        "subsampling_factor": cfg.subsampling_factor, "decoder": cfg.decoder_type,
        "vocab": cfg.vocab_size, "blank_id": cfg.blank_id,
        "chunk_frames": cfg.chunk_size, "shift_frames": cfg.shift_size,
        "pre_encode_cache": cfg.pre_encode_cache_size,
    }
    print(json.dumps(report["io_features"]))

    rng = np.random.default_rng(7)
    smokes = []
    for att in ATT_CONTEXTS:
        model.set_att_context_size(list(att))
        chunk_s = cfg.chunk_size * 0.01
        n_sec = max(3.0, chunk_s * 3)
        t = np.arange(int(n_sec * 16000), dtype=np.float32) / 16000
        # tone + bursts: deterministic, structured (not pure noise)
        pcm = 0.3 * np.sin(2 * np.pi * 220.0 * t).astype(np.float32)
        pcm *= (np.sin(2 * np.pi * 1.5 * t) > 0).astype(np.float32)
        pcm += 0.01 * rng.standard_normal(len(t)).astype(np.float32)

        state = model.new_stream()
        step = int(0.25 * 16000)  # 250 ms mic-style blocks
        per_call_ms, partials = [], []
        t0 = time.perf_counter()
        try:
            for i in range(0, len(pcm), step):
                tc = time.perf_counter()
                txt = model.stream_audio(state, pcm[i : i + step], sampling_rate=16000)
                per_call_ms.append((time.perf_counter() - tc) * 1000)
                partials.append(txt)
            final = model.finalize(state)
            ok = True
        except Exception as exc:  # noqa: BLE001
            final, ok = f"ERROR {type(exc).__name__}: {exc}", False
        total_s = time.perf_counter() - t0
        mono = all(p1 == "" or p2.startswith(p1) for p1, p2 in zip(partials, partials[1:]))
        s = {
            "att_context": list(att), "chunk_s": round(chunk_s, 3),
            "ok": ok,
            "mean_stream_call_ms": round(sum(per_call_ms) / max(len(per_call_ms), 1), 1),
            "max_stream_call_ms": round(max(per_call_ms), 1) if per_call_ms else None,
            "rtf": round(total_s / n_sec, 4),
            "partials": len(partials), "partials_prefix_monotonic": mono,
            "finalized": ok,  # synthetic tone audio may legitimately decode to '' (no speech)
            "final_text_len": len(final) if ok else None,
            "rss_mb": (lambda r: round(r, 1) if r else None)(peak_rss_mb()),
            "final_text_repr": repr(final)[:120],
        }
        smokes.append(s)
        print(json.dumps(s, ensure_ascii=False))
    report["smoke"] = smokes

    LOG.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Report: {LOG}")

    assert report["api_missing"] == [], f"API missing: {report['api_missing']}"
    assert all(s["ok"] for s in smokes), f"a latency mode failed: {[(s['att_context'], s.get('final_text_repr')) for s in smokes if not s['ok']]}"
    assert all(s["partials_prefix_monotonic"] for s in smokes), "partials not monotonic in some mode"
    print("P2 SMOKE PASSED — streaming contract verified end-to-end (all 4 latency modes).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
