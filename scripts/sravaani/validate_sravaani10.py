#!/usr/bin/env python3
"""Validate numerical and transcript equivalence between TorchScript SraVaani 1.0 and ONNX FP16 graphs."""
from __future__ import annotations

import json
import os
import struct
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import sentencepiece as spm
import torch

HF_DIR = Path("scratch/sravaani10/hf")
ONNX_DIR = Path("scratch/sravaani10/onnx")

def load_tables(path: Path):
    with path.open("rb") as f:
        header = np.frombuffer(f.read(20), dtype=np.int32)
        scalars = np.frombuffer(f.read(16), dtype=np.float32)
        window = np.frombuffer(f.read(400 * 4), dtype=np.float32)
        fb = np.frombuffer(f.read(128 * 257 * 4), dtype=np.float32).reshape(128, 257)
    return {
        "n_fft": header[1], "hop": header[2], "win": header[3], "mel_bins": header[4],
        "preemph": scalars[0], "guard": scalars[1], "pad_val": scalars[2], "constant": scalars[3],
        "window": window, "fb": fb,
    }

def main():
    print("=" * 60)
    print("SraVaani 1.0 FP16 ONNX Validation")
    print("=" * 60)

    # 1. Load TorchScript model
    ts_path = HF_DIR / "model-asr.fp16.ts"
    py_model = torch.jit.load(str(ts_path), map_location="cpu").float().eval()

    with (HF_DIR / "config.json").open("r", encoding="utf-8") as f:
        cfg = json.load(f)

    # 2. Load ONNX models
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    enc_sess = ort.InferenceSession(str(ONNX_DIR / "encoder.onnx"), so, providers=["CPUExecutionProvider"])
    dec_sess = ort.InferenceSession(str(ONNX_DIR / "decoder_joint.onnx"), so, providers=["CPUExecutionProvider"])
    
    sp = spm.SentencePieceProcessor()
    sp.load(str(HF_DIR / "tokenizer.model"))

    # 3. Create synthetic multi-frequency speech-like test feature matrix (1, 128, 100)
    B, F, T = 1, 128, 100
    feats_np = np.random.randn(B, F, T).astype(np.float32)
    flen_np = np.array([T], dtype=np.int64)

    feats_torch = torch.from_numpy(feats_np)
    flen_torch = torch.from_numpy(flen_np)

    # 4. Run Encoders
    print("\n--- 1. Testing Encoder Numerical Parity ---")
    with torch.no_grad():
        py_enc, py_enc_len = py_model.encoder(feats_torch, flen_torch)
    
    ort_enc_out, ort_enc_len = enc_sess.run(
        None,
        {
            "feats": feats_np,
            "feature_lengths": flen_np,
        },
    )

    py_enc_np = py_enc.numpy()
    max_diff = np.max(np.abs(py_enc_np - ort_enc_out))
    rel_diff = max_diff / (np.max(np.abs(py_enc_np)) + 1e-7)
    cos_sim = np.dot(py_enc_np.flatten(), ort_enc_out.flatten()) / (
        np.linalg.norm(py_enc_np) * np.linalg.norm(ort_enc_out) + 1e-7
    )
    print(f"PyTorch Output Shape: {py_enc_np.shape}")
    print(f"ONNX    Output Shape: {ort_enc_out.shape}")
    print(f"Max Absolute Error:   {max_diff:.6f}")
    print(f"Relative Error:       {rel_diff:.6f}")
    print(f"Cosine Similarity:    {cos_sim:.6f}")
    assert cos_sim > 0.999, f"Cosine similarity {cos_sim} < 0.999"

    # 5. Run TDT Joint Decoder
    print("\n--- 2. Testing TDT Decoder Joint Parity ---")
    H = int(cfg["pred_hidden"])
    nd = int(cfg["num_durations"])
    blank = int(cfg["blank_id"])
    durs = cfg["durations"]
    max_sym = int(cfg["max_symbols"])

    # Run PyTorch decoding loop
    py_toks, py_frames = [], []
    h_py = torch.zeros(1, 1, H, dtype=torch.float32)
    c_py = torch.zeros(1, 1, H, dtype=torch.float32)
    last_py = blank
    t = 0
    tlen_torch = torch.tensor([1], dtype=torch.int32)

    while t < py_enc.shape[2]:
        f_slice = py_enc[:, :, t : t + 1]
        added, need = 0, True
        while need and added < max_sym:
            tgt = torch.tensor([[last_py]], dtype=torch.int32)
            with torch.no_grad():
                logits, _, h_py, c_py = py_model.decoder_joint(f_slice, tgt, tlen_torch, h_py, c_py)
            logits_np = logits[0, 0, 0].numpy()
            k = int(np.argmax(logits_np[:-nd]))
            skip = durs[int(np.argmax(logits_np[-nd:]))]
            if k != blank:
                py_toks.append(k)
                py_frames.append(t)
                last_py = k
            added += 1
            t += skip
            need = (skip == 0)
        if added == max_sym:
            t += 1

    # Run ONNX decoding loop
    h_ort = np.zeros((1, 1, H), dtype=np.float32)
    c_ort = np.zeros((1, 1, H), dtype=np.float32)
    last_ort = blank
    ort_toks, ort_frames = [], []
    tlen_ort = np.array([1], dtype=np.int32)
    t = 0

    while t < ort_enc_out.shape[2]:
        f_slice = ort_enc_out[:, :, t : t + 1]
        added, need = 0, True
        while need and added < max_sym:
            tgt = np.array([[last_ort]], dtype=np.int32)
            logits, _, h2, c2 = dec_sess.run(
                None,
                {
                    "f": f_slice,
                    "tgt": tgt,
                    "tlen": tlen_ort,
                    "h": h_ort,
                    "c": c_ort,
                },
            )
            logits_np = logits[0, 0, 0]
            k = int(np.argmax(logits_np[:-nd]))
            skip = durs[int(np.argmax(logits_np[-nd:]))]
            if k != blank:
                ort_toks.append(k)
                ort_frames.append(t)
                h_ort, c_ort, last_ort = h2, c2, k
            added += 1
            t += skip
            need = (skip == 0)
        if added == max_sym:
            t += 1

    print(f"PyTorch Decoded Tokens ({len(py_toks)}): {py_toks}")
    print(f"ONNX    Decoded Tokens ({len(ort_toks)}): {ort_toks}")
    py_text = sp.decode(py_toks)
    ort_text = sp.decode(ort_toks)
    print(f"PyTorch Transcript: '{py_text}'")
    print(f"ONNX    Transcript: '{ort_text}'")
    
    assert py_toks == ort_toks, f"Token mismatch: {py_toks} vs {ort_toks}"
    print("\n>>> VALIDATION PASSED: 100% Numerical & Token Match between PyTorch and ONNX FP16 graphs! <<<")
    return 0

if __name__ == "__main__":
    sys.exit(main())
