# SraVaani 0.5 — Optimization & Equivalence (P9)

Status: P9 evidence. Every number below is a measured artifact of a script in
`scripts/sravaani/` (reproducible); on-device numbers are P11 and labeled NOT MEASURED.

## 1. Method selection (constraint-driven, per master §5)

The exported graphs (`scratch/sravaani/onnx/export_meta.json`, P2 op inventory) contain
Conv / Attention / LayerNorm / Squeeze-Excite in fp32 and MatMuls with 2-D constant
weights. Weight-only int8 (per-output-channel symmetric, `DequantizeLinear` in-graph) is
the quantization family compatible with this graph without a calibration set:

| Technique | Decision | Reason (measured/structural) |
|---|---|---|
| Weight-only int8 (MatMul weights) | **Implemented** | No calibration needed; activations stay fp32; storage 2.78× |
| Static int8 (activations) | Rejected | Requires calibration over real mel distribution; no licensed corpus staged yet → would be fabrication-adjacent |
| FP16 | Rejected | Halves storage but many ORT CPU kernels upcast fp16→fp32; no CPU speedup, added conversion risk |
| INT4 / QAT | Rejected | Not supported by the export path; accuracy risk unmeasurable without a corpus |
| Graph optimization | Already applied by ORT at session level; no further offline wins measured |

## 2. Scripts (reproducible pipeline)

```text
scripts/sravaani/optimize_int8.py     # fp32 encoder → int8 candidate + provenance meta
scripts/sravaani/prove_equivalence.py # end-task equivalence gate (required before packaging)
```

Provenance chain: fp32 graph sha256 (`int8_meta.json: source_sha256`) → operator
(weight-only MatMul int8, 154/205 MatMuls quantized, 378,798,080 params moved) → int8
graph sha256 (`output_sha256`).

## 3. Measured results (desktop CPU, 4 threads — NOT a device measurement)

| Metric | Baseline (fp32) | int8 candidate | Difference |
|---|---:|---:|---:|
| Encoder artifact size | 1,774,013,452 B | 638,695,714 B | **2.778× smaller** |
| Equivalence (collapsed CTC token streams, 10 structured probes) | — | **identical on 10/10** | — |
| Frame argmax agreement (same probes) | — | 1.00000 (min) | — |
| Encoder max rel diff | — | 3.076e+03 (near-zero frames only) | informational, not gated |
| Desktop RTF (30 s stream, 1.28 s chunks) | 0.6226 | 0.7322 | int8 **slower on desktop ORT** |

Raw logs: `scratch/sravaani/logs/equivalence_int8.json`, `scratch/sravaani/logs/p9_desktop_perf.json`.

### Honest interpretation of the RTF row

Weight-only int8 inserts in-graph `DequantizeLinear` ops; desktop ORT's fp32 MatMul kernels
are already cache-optimal, so the dequant overhead dominates and the candidate is ~18%
slower **on this desktop runtime**. The storage win (2.78×) is real and runtime-independent.
Device-side speedup (QNN / XNNPACK int8 kernels, mmap'd weights) is the P11 measurement and
is NOT MEASURED; if device RTF shows no win, the production recommendation is fp32+mmap and
the int8 artifact remains a validated, provenance-pinned alternative.

## 4. Equivalence gate definition (P3 methodology, tightened)

The encoder is consumed ONLY by the CTC head, so the gate that matters is end-task:
greedy CTC argmax → collapse repeats → drop blank (id 0) must yield an **identical token
sequence** for fp32 and int8 on every probe. Encoder-level max-rel-diff is reported as
evidence but not gated: int8 weight noise legitimately moves near-tie frames (P3 measured
the same effect on the fp32 export itself, `export_meta.json: tie_evidence`), which is
acceptable iff the decoded stream is unchanged — and it is, on all 10 probes.

## 5. Gate status

```text
EQUIVALENCE: PASS (10/10 probes, collapsed streams identical)
STORAGE:     1.77 GB → 638.7 MB (2.778x)
DEVICE RTF:  NOT MEASURED ON PHYSICAL DEVICE (P11)
WER int8:    NOT MEASURED (token-stream equality proven on structured probes; corpus WER is P8-blocked)
```

Production packaging decision is deliberately **deferred to P11 device data** per master §14
(no accuracy-relevant decision without measurement).
