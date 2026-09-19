# SraVaani 0.5 — Graph Validation & Runtime Decision (P2)

**Status:** CONTRACT VERIFIED (official wrapper executed end-to-end on desktop CPU, 2026-09-15)
**Evidence:** `scratch/sravaani/logs/p2_smoke.json`, `scripts/sravaani/p2_smoke.py` (exit 0)

## 1. What the shipped artifact actually is (verified, not inferred)

- `model-asr-streaming.fp16.ts` (898 MB) is a **TorchScript module exposing exactly three callables: `forward`, `encoder`, `ctc_decoder`** (verified by enumerating top-level callables).
- It is **fp16 weights, exported unfrozen** — real parameters; on CPU the official wrapper upcasts to fp32 (no fp16 conv kernel); this was reproduced (io_dtype=fp32).
- `preproc.pt` is **not** a jit module — it is a `torch.load` checkpoint containing `params` (n_fft, hop_length, win_length, preemph, mag_power, log_zero_guard_value, pad_value, CONSTANT) plus `window` and `fb` (mel filterbank) tensors. Verified by reading the official loader code; loading it with `torch.jit.load` fails by design.
- The streaming brain (chunk scheduling, cache handoff, CTC collapse across chunk seams, causal per-window normalization) lives in the **official Python wrapper** `modeling_sravaani_streaming.py` (26 KB, MIT) — small enough to port 1:1 to Kotlin.

## 2. Verified streaming contract (official wrapper semantics)

```
state = new_stream()                        # encoder caches (channel+time) + CTC prev_id
text = stream_audio(state, f32@16k)         # cumulative partial transcript so far
text = finalize(state)                      # flush trailing partial chunk
model.set_att_context_size([70, R])         # R ∈ {13, 6, 1, 0}; chunk = 8*(1+R) frames @10ms
```

Latency modes (one traced graph serves all — lookahead is a runtime chunk-size choice):

| att_context | chunk | lookahead | verified smoke (desktop CPU) |
|---|---|---|---|
| [70,13] | 1.12 s | 1.04 s | ok, RTF 0.90, partials monotonic |
| [70,6] | 0.56 s | 0.48 s | ok, RTF 0.67, partials monotonic |
| [70,1] | 0.16 s | 0.08 s | ok, RTF 2.19 (official live path re-extracts features over the full buffer per call) |
| [70,0] | 0.08 s | 0.00 s | ok, RTF 3.20 (same cause) |

**Production insight (measured):** the official `stream_audio` recomputes mel features over the entire buffered audio on every call (causal whole-utterance normalization). Latency therefore grows with utterance length at small chunk sizes. The Android engine must implement **incremental feature extraction** (mel over a bounded rolling window with the documented per-window normalization) to keep O(1) per-chunk cost. This is an implementation requirement for P3/P4, not a model limitation.

**Cache contract (from wrapper source, verified by execution):**
- Encoder: `cache_last_channel` (B, 17, 70, 1024), `cache_last_time` (B, 17, 1024, 8), `cache_last_channel_len` (B,) — returned by each `encoder(...)` call as `ch_next/t_next/len_next`.
- Pre-encode cache: 9 mel frames prepended to each 112-frame chunk (zero-padded on step 0).
- CTC decode state: `prev_id` (last argmax id) carried across chunk seams for repeat-collapse; blanks dropped.
- Encoder call signature: `(feats[B,128,T], feat_len[B], ch, t, ch_len) -> (enc, enc_len, ch_next, t_next, len_next)`.

## 3. Audio input contract (verified)

- 16 kHz, mono, float32 in [-1, 1] (int16/32768), any block size.
- 128-bin mel, hop 10 ms (subsampling ×8 → one encoder frame per 80 ms).
- Preprocessing performed by the wrapper: pre-emphasis → STFT (window from preproc.pt) → magnitude^power → mel matmul → log → **per-feature-window mean/var normalization** (causal over buffered audio in live mode). Wrong normalization ⇒ silent empty output (community export documented the same failure mode).
- No external VAD/NS/AGC required by the model; external DSP must be justified by WER evidence (P5/P8).

## 4. Op inventory (measured)

Naive `.graph` enumeration of the 898 MB fp16 graph yields only 2 top-level ops (the module is a thin container; real ops live inside the scripted encoder/decoder submodules, which torch does not fully expose for traced graphs). **Ground truth for runtime compatibility therefore comes from the architecture + successful execution**, not from a complete static op dump:
- Architecture: FastConformer encoder (conv subsampling + attention with limited-context caching + Squeeze-FeedForward) + CTC head — all standard ops (conv1d, matmul, softmax, layernorm, gelu/relu).
- Verified execution: full streaming pass ran successfully on stock torch CPU — no exotic ops encountered at runtime.

## 5. Tokenizer facts (measured)

- SentencePiece model, **vocab 5000** (+ blank_id 5000 → output dim 5001), loaded successfully with `sentencepiece`.
- Decode path: token-id list → `sp.decode()` → surface text (native scripts; model code-switches to Latin for real English loanwords).

## 6. Android runtime decision (evidence-based, per user directive)

Candidates evaluated against the verified facts:

| Criterion | ONNX Runtime Mobile | sherpa-onnx (runtime only) | ExecuTorch | TorchScript on-device |
|---|---|---|---|---|
| Op coverage for FastConformer+CTC | Full (standard ops) | Full (standard ops; NeMo exports are its core use case) | Full | Full (native) |
| Streaming cache ergonomics | Manual (we port the wrapper loop; explicit cache tensors per call) | Built-in `OnlineRecognizer` streaming for exactly this 5-in/5-out cache contract | Manual port needed | Manual port needed (must embed wrapper logic in Kotlin/JNI) |
| int8 path for these ops | MatMul/weight-only int8 (community recipe measured: 1.79 GB→659 MB, WER parity ±0.1) | Same int8 recipe consumed directly | int8 via PT2E — unproven for this graph | fp16 only (int8 not supported in jit runtime) |
| APK/native size | ~15–25 MB JNI | ~10–20 MB JNI | ~20–40 MB | torch mobile ~30–60 MB |
| License / maintenance | MIT, active | Apache-2.0, active | BSD, active | same as torch |
| Provenance cleanliness | weights untouched in conversion; equivalence provable | same | same | no conversion at all |

**Decision: primary = ONNX Runtime Mobile** executing a conversion of the official graph (fp16 first, int8 only after P9 equivalence proof), with the wrapper's streaming loop ported to Kotlin (incremental features included).
**Fallback = sherpa-onnx runtime** (its `OnlineRecognizer` already implements the identical cache contract; if our Kotlin port or ONNX export hits an op wall, sherpa consumes the same ONNX artifact with metadata).
**Rejected for now:** ExecuTorch (heavier toolchain, no proven path for this exact graph); raw TorchScript-on-device (fp16-only, larger runtime, no int8 path, and we'd still have to port the wrapper loop).

Rationale: lowest total risk while keeping SraVaani 0.5 as the only model; the conversion is a format change of the official weights (no retraining, no distillation), validated by numeric-equivalence proof before any quantization (P9 gate).

## 7. Desktop baseline (recorded; NOT Android numbers)

| Metric | [70,13] | [70,6] | [70,1] | [70,0] |
|---|---|---|---|---|
| mean stream-call ms | 217.0 | 131.7 | 519.0 | 777.1 |
| RTF (official live path) | 0.90 | 0.67 | 2.19 | 3.20 |
| partials prefix-monotonic | yes | yes | yes | yes |

Model load (cold, desktop): ~2.5–3.8 s. Peak RSS: NOT MEASURED (Windows probe returned None in this run — to be captured in P8/P10 runs; desktop RSS for this model class is expected ~1.5 GB per community measurements, to be verified).

Android numbers: NOT MEASURED ON PHYSICAL DEVICE (P11).
