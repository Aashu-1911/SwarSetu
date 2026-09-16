#!/usr/bin/env python3
"""P3 — generate Android assets + golden test vectors from the OFFICIAL artifact.

Outputs (all small, committed to git — the 1.7 GB graphs stay out):

1. app/src/main/assets/sravaani/tables.bin
   Little-endian f32 blob: magic "SRVT" (u32) + version (u32) + n_fft (u32) +
   win_length (u32) + hop (u32) + mel_bins (u32) + fb_bins (u32) +
   window[win_length] + mel_fb[mel_bins * fb_bins].
   Values are copied byte-for-byte from the official preproc.pt (window, fb) —
   the Kotlin featurizer consumes the same tables the official wrapper uses.

2. app/src/main/assets/sravaani/tokens.txt
   The 5000 SentencePiece pieces, one per line, index == line number.
   (Generated from the official tokenizer.model, identical to scratch output.)

3. app/src/test/resources/sravaani/golden_features.json
   Deterministic probe signal + the OFFICIAL featurization results, computed
   with the exact official math (preemph -> stft -> |X|^2 -> mel -> log, NO
   normalization, then per-feature sample mean/std over all frames):
     - "signal_b64":    f32 LE of the 26880-sample probe (seeded RNG; 26880 = 168*160
                        gives 168 valid frames so BOTH official chunk-0 and the chunk-1
                        cache slice [112-9, 112) are fully materialized)
     - "seq_len":       wav_len // hop  (== 50)
     - "raw_b64":       f32 LE of raw log-mel frames [seq_len * 128]
     - "mean_b64":      f32 LE per-bin mean over valid frames (128)
     - "std_b64":       f32 LE per-bin sample std (+CONSTANT) (128)
     - "chunk0_b64":    f32 LE of the OFFICIAL chunk-0 frame block
                        (pec=9 zero-pad + 112 normalized frames) [121 * 128]
     - "chunk1_cache_b64": f32 LE of official cache frames [103..112) —
                        re-normalized with stats over ALL frames (the official
                        _chunk_with_cache slices the *current* normalized tensor)
   The Kotlin golden test compares against these with 1e-3 absolute tolerance
   (fp32 reassociation headroom; the python equivalence gate measured 4.4e-4).
"""
from __future__ import annotations

import base64
import json
import struct
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import numpy as np
import torch

sys.path.insert(0, str(Path("scratch/sravaani")))
from hf.modeling_sravaani_streaming import SraVaaniStreamingModel  # noqa: E402
from hf.configuration_sravaani_streaming import SraVaaniStreamingConfig  # noqa: E402

import sentencepiece as spm  # noqa: E402

HF_DIR = Path("scratch/sravaani/hf")
ASSETS = Path("app/src/main/assets/sravaani")
TEST_RES = Path("app/src/test/resources/sravaani")
PIECES_OUT = Path("scratch/sravaani/onnx/tokens.txt")

N_FFT, HOP, WIN, MEL, FB = 512, 160, 400, 128, 257
PEC, CHUNK = 9, 112
CONSTANT = 1e-05
GUARD = 5.960464477539063e-08


def official_features(wav: torch.Tensor, p: dict, window: torch.Tensor, fb: torch.Tensor):
    """The official featurization WITHOUT normalization (extract_features minus _normalize)."""
    tmask = torch.arange(wav.shape[1]).unsqueeze(0) < wav.shape[1]
    x = torch.cat((wav[:, :1], wav[:, 1:] - p["preemph"] * wav[:, :-1]), dim=1).masked_fill(~tmask, 0.0)
    # The OFFICIAL call verbatim: center=True self-pads n_fft//2 with pad_mode.
    # (The exporter's conv formulation compensates with pad=200 + k=400 — same geometry,
    # but this generator must replicate the reference, not the fused form.)
    spec = torch.stft(
        x, n_fft=N_FFT, hop_length=HOP, win_length=WIN, window=window,
        center=True, pad_mode="constant", return_complex=True,
    )  # (1, 257, T)
    power = spec.abs() ** p["mag_power"]
    mel = torch.matmul(fb, power)  # (1,128,T)
    logmel = torch.log(mel + GUARD)
    seq_len = torch.tensor([wav.shape[1] // HOP])
    return logmel[0], seq_len  # (128, T), (1,)


def normalize_per_feature(x: torch.Tensor, seq_len: int, constant: float):
    """Exact port of the official _normalize_per_feature (sample variance, denom-1)."""
    valid_frames = seq_len
    denom = valid_frames
    mean = x[:, :valid_frames].sum(dim=1) / denom
    var = ((x[:, :valid_frames] - mean.unsqueeze(1)) ** 2).sum(dim=1) / (denom - 1.0)
    std = torch.sqrt(var)
    std = torch.where(torch.isnan(std), torch.zeros_like(std), std) + constant
    return (x - mean.unsqueeze(1)) / std.unsqueeze(1), mean, std


def f32_b64(t: torch.Tensor) -> str:
    return base64.b64encode(t.detach().to(torch.float32).contiguous().numpy().tobytes()).decode()


def main() -> int:
    cfg = SraVaaniStreamingConfig.from_pretrained(str(HF_DIR), local_files_only=True)
    model = SraVaaniStreamingModel(cfg)
    model._artifacts_dir = str(HF_DIR)
    model.eval()
    model._ensure_loaded()
    p = model._p
    window = model._window.float()
    fb = model._fb.float().reshape(MEL, FB)
    assert p["n_fft"] == N_FFT and p["hop_length"] == HOP and p["win_length"] == WIN
    assert p["CONSTANT"] == CONSTANT and p["log_zero_guard_value"] == GUARD

    # ---------------- 1. tables.bin ----------------
    ASSETS.mkdir(parents=True, exist_ok=True)
    header = struct.pack(
        "<IIIIIII", 0x54565253, 1, N_FFT, WIN, HOP, MEL, FB
    )  # magic "SRVT", version
    blob = header + window.numpy().tobytes() + fb.numpy().tobytes()
    tables = ASSETS / "tables.bin"
    tables.write_bytes(blob)
    print(f"wrote {tables} ({len(blob)} bytes)")
    # Same bytes for the JVM golden test (unit tests read from test resources).
    (TEST_RES / "tables.bin").write_bytes(blob)

    # ---------------- 2. tokens.txt asset ----------------
    sp = spm.SentencePieceProcessor(model_file=str(HF_DIR / "tokenizer.model"))
    lines = [sp.id_to_piece(i) for i in range(sp.get_piece_size())]
    assert len(lines) == 5000 and lines[0] == "<unk>"
    tokens_asset = ASSETS / "tokens.txt"
    tokens_asset.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {tokens_asset} ({len(lines)} pieces)")

    # ---------------- 3. golden vectors ----------------
    rng = np.random.default_rng(26173)
    n = 26_880  # 168 frames * hop 160: covers pec(9)+chunk(112) + chunk-1 cache [103,112)
    t = np.arange(n) / 16000.0
    sig = (
        0.5 * np.sin(2 * np.pi * 220 * t)
        + 0.3 * np.sin(2 * np.pi * 733 * t + 0.7)
        + 0.2 * np.sin(2 * np.pi * 2100 * t * (1 + 0.3 * t))
        + 0.05 * rng.standard_normal(n)
    ).astype(np.float32)
    wav = torch.from_numpy(sig).unsqueeze(0)

    raw, seq_len = official_features(wav, p, window, fb)  # (128, T) log-mel, T frames
    T = raw.shape[1]
    assert T == n // HOP + 1 and seq_len.item() == n // HOP, (T, seq_len.item())
    norm, mean, std = normalize_per_feature(raw, int(seq_len), CONSTANT)

    # official chunk-0 block: pec zero-pad (pad_value AFTER normalization) + 112 frames
    cache = torch.full((MEL, PEC - 0), float(p["pad_value"]))  # step-0: all 9 padded
    chunk0 = torch.cat((cache, norm[:, :CHUNK]), dim=1)  # (128, 121)

    # official chunk-1 cache: frames [112-9, 112) re-sliced from the CURRENT
    # normalized tensor (stats over all seq_len frames)
    chunk1_cache = norm[:, CHUNK - PEC : CHUNK]

    golden = {
        "signal_b64": f32_b64(wav[0]),
        "seq_len": int(seq_len),
        "raw_b64": f32_b64(raw[:, : int(seq_len)].transpose(0, 1).reshape(-1)),  # (T,128)
        "mean_b64": f32_b64(mean),
        "std_b64": f32_b64(std),
        "chunk0_b64": f32_b64(chunk0.transpose(0, 1).reshape(-1)),  # (121,128)
        "chunk1_cache_b64": f32_b64(chunk1_cache.transpose(0, 1).reshape(-1)),  # (9,128)
    }
    TEST_RES.mkdir(parents=True, exist_ok=True)
    out = TEST_RES / "golden_features.json"
    out.write_text(json.dumps(golden), encoding="utf-8")
    print(f"wrote {out} (seq_len={int(seq_len)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
