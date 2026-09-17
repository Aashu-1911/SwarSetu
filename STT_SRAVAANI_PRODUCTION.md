# SraVaani 0.5 — Production Document (P12, SIH 26173)

Every claim carries a verification class: **[CODE]** verified by source, **[TEST]** verified
by a runnable test, **[BENCH]** measured by a committed script, **[DOC-EXT]** external claim
(model card / paper), **[NOT MEASURED]**, **[BLOCKED]** with reason. Nothing is presented as
measured that was not measured (master §28).

## 1. Executive Summary

The Vosk-era prototype was removed and replaced with a single-artifact **SraVaani 0.5-live**
implementation (the only permitted model): official fp16 TorchScript → verified ONNX export
→ Android engine ([SravaaniEngine]) executing a **true-streaming** cache-carrying loop via
ONNX Runtime. All 10 required languages are served by the one multilingual model — no
aliasing, no per-language substitution, no runtime downloads. Packaging is hash-verified
(P7), streaming is O(n) over the utterance (P4), the VAD/pause segmentation is
matrix-validated (P5), WER/CER tooling exists and is unit-tested (P8), and an int8 candidate
with a proven-identical token stream exists (P9).

**Not yet done, stated plainly:** corpus WER numbers (no licensed corpus staged — P8
BLOCKED), physical-device benchmarks (no device attached — P11 runner ready), and a
production packaging decision between fp32 and int8 (deferred to device data, per §14).

## 2. SIH 26173 Requirements → Status

| Requirement | Status | Evidence |
|---|---|---|
| 10 required languages | MET (single multilingual model) | §6 below |
| Local, fully offline | MET | assets-only packaging, no download paths [CODE+TEST] |
| Open-source tech | MET | SraVaani MIT [DOC-EXT], ORT MIT, Kotlin port MIT |
| Lightweight for low/mid phones | PARTIAL | 1.77 GB fp32 / 638.7 MB int8 artifact; 2 GB-RAM devices will not run the fp32 path [CODE, known limitation] |
| Minimize WER | TOOLING MET, BASELINE BLOCKED | WER/CER harness + 25 unit tests; corpus WER BLOCKED (no licensed corpus) |
| Low latency | DESKTOP MEASURED, DEVICE NOT MEASURED | desktop RTF 0.62 (fp32) [BENCH]; device → P11 |
| Pause-based sentence formation | MET | VAD + silence-timeout segmentation, matrix-tested [TEST] |
| Streaming / near-real-time | MET (true-streaming architecture; device latency NOT MEASURED) | cache-carrying loop, partials ≤200 ms windows [CODE+TEST]; device RTF → P11 |
| No cloud / proprietary STT / internet | MET | no network dependency in STT path [CODE] |
| Correct integration, preserved architecture | MET | engine behind existing [SttEngine] SPI + Koin DI unchanged [CODE] |

## 3. Actual STT Architecture

```text
Mic → AudioRecord(16 kHz mono PCM16) → PcmCapture ring (AudioCapture seam)
    → VoiceActivityDetector (adaptive floor + steady-noise rejection)
    → SttPipeline push loop (silenceTimeoutMs gates finalization; AUDIO_ONLY never loads engine)
    → SravaaniLiveStream.pushPcm (200 ms windows)
    → SravaaniFeaturizer (incremental mel, O(n) total)
    → ONNX Runtime encoder (cache_last_channel/time carried across chunks)
    → CTC head → greedy argmax → cross-chunk collapse → SravaaniTokenizer (SentencePiece)
    → cumulative PARTIALs → FINAL on pause/silence timeout
```

## 4. Sravaani 0.5 Model Specification

| Property | Value | Class |
|---|---|---|
| Artifact | `model-asr-streaming.fp16.ts` 898 MB + preproc.pt + tokenizer.model | [BENCH] manifest |
| Repo / revision | ARTPARK-IISc/SraVaani-0.5-live @ `29a15303a55673367134eee9e1d18cb1a2dcaabf` | [BENCH] pinned download |
| Architecture | cache-aware streaming FastConformer encoder + CTC head | [DOC-EXT] + executed [BENCH] |
| Vocab | 5000 SentencePiece pieces (+1 CTC blank at index 0 in export) | [BENCH] config + tokens.txt |
| Streaming | encoder caches (17×70×1024 ch, 17×1024×8 time) + CTC prev_id across seams | [BENCH] P2 executed |
| Languages | one multilingual model, no language input | [DOC-EXT] model card |
| License | MIT | [DOC-EXT] card metadata via API |
| Export | ONNX opset pinned; three graphs (featurizer 0.96 MB / encoder 1.77 GB / ctc 20.5 MB) | [BENCH] export_meta.json |
| Export equivalence | feat rel 2.6e-05, enc rel 4.7e-05, collapsed token streams identical on structured trials | [BENCH] P3 |

## 5. Model Provenance and License

Pinned-revision download, size+sha256 verified per file (`verify_manifest.py` exit-0);
token used via env only, never stored. Full chain: HF rev `29a15303` → P3 export
(`export_meta.json` hashes) → P7 packaging pins (`SravaaniPackaging.kt`) → runtime
extraction re-verified each start. See `docs/STT_SRAVAANI_PROVENANCE.md`. [BENCH]

## 6. Ten-Language Coverage

Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English —
all served by the single multilingual graph (no language input exists to condition on;
transcripts emerge in the spoken language's script). Vocab coverage measured per script:
kn=424, ml=290, or=270 native pieces; all 10 scripts covered
(`docs/STT_SRAVAANI_LANGUAGE_COVERAGE.md`, `SravaaniLanguageCoverageTest` 5/5).
The historical Kannada/Malayalam/Odia → Hindi aliasing is **removed** and guarded by
tests. Auto-detection is explicitly **not claimed**. [BENCH+TEST]

## 7. Audio Pipeline

16 kHz mono PCM16 → float32/32768 (model contract verified in P2). No resampling, no NS,
no AGC — preprocessing additions require WER evidence (none available without corpus;
documented decision in `docs/STT_SRAVAANI_AUDIO_CONTRACT.md` §preprocessing). [CODE]

## 8. VAD / Pause Detection

Energy VAD with noise-floor-adaptive thresholds (constants are floors; adaptation raises
them in steady noise), 3-frame trigger, hangover, steady-tone rejection via RMS spread.
Matrix-validated across short/long speech, intra-sentence pauses, fan noise, quiet/loud —
no word clipping; two-sentence pipeline integration test proves zero lost audio.
`VoiceActivityDetectorMatrixTest` 12/12, `SttPipelineSentenceSegmentationTest` 2/2. [TEST]

## 9. Streaming Architecture

True streaming: each audio sample transcribed exactly once (O(n) total — replaced the
O(n²) whole-buffer retranscription; spy-asserted by `SttPipelineStreamingTest`).
Partials are cumulative within a 200 ms push window; finalization flushes trailing chunks
(official finalize semantics). Unlike the official live path, featurization is incremental. [CODE+TEST]

## 10. Android Runtime

All native calls serialize on the engine mutex; live pushes bounded to 200 ms; 4 intra-op
threads (P11 tunes); lazy session build (cold app init never maps 1.8 GB unless STT is
used). [CODE]

## 11. Model Packaging

Assets → hash-verified extraction to app-private storage (SHA-256 pins before marker
write; re-verified each start) → ORT mmap. `noCompress("onnx")`. Fresh install + no
internet = working STT (no download paths exist). `checkSravaaniModel` Gradle gate
verifies pins at build time. APK/AAB size delta: NOT MEASURED (artifact staging on device
build machine pending). [CODE+TEST]

## 12. Memory Usage

Encoder mmap'd from filesDir (pageable file-backed). Per-stream native caches ≈ 5.4 MB;
abandoned streams now freed deterministically (P10 closer). Device PSS: **NOT MEASURED**
(P11 runner collects it). Low-end reality: fp32 path requires RAM beyond 2 GB-class
devices; recorded limitation. [CODE + NOT MEASURED]

## 13. Quantization / Optimization

Weight-only int8 (per-output-channel symmetric, in-graph DequantizeLinear; 154/205
MatMuls, 378.8 M params). Static/dynamic activation int8 REJECTED without a real
calibration corpus (fabrication-adjacent); FP16 rejected (no CPU win). Full rationale:
`docs/STT_SRAVAANI_OPTIMIZATION.md`. [BENCH]

## 14. Baseline vs Optimized Model

| Metric | Baseline fp32 | int8 candidate | Class |
|---|---:|---:|---|
| Encoder artifact | 1,774,013,452 B | 638,695,714 B (2.778×) | [BENCH] |
| Equivalence | — | collapsed token streams identical 10/10 probes; frame agreement 1.0 | [BENCH] |
| Desktop RTF (30 s stream) | 0.6226 | 0.7322 (slower on desktop ORT — dequant overhead; honest) | [BENCH] |
| Device RTF | NOT MEASURED | NOT MEASURED | P11 |
| Device peak RAM | NOT MEASURED | NOT MEASURED | P11 |
| WER | BLOCKED (corpus) | token-stream equality proven; corpus WER BLOCKED | P8 |

Production packaging decision (fp32+mmap vs int8) is **deferred to P11 device data** —
deliberate, per master §14.

## 15. WER/CER Results

Harness: `eval/WerCalculator` + `eval/CerCalculator` (Levenshtein S/D/I; NFC → casefold →
punctuation-strip; danda-aware; aggregate = Σ(S+D+I)/ΣN, never mean-of-WERs), 25/25
hand-computed tests. Corpus-driven results: **BLOCKED — no license-verified corpus staged**
(candidates: VAANI, IndicVoices, Kathbath, FLEURS; license verification is the gate).
`scripts/sravaani/eval_wer.py` ready to produce per-language × per-condition tables the
moment a corpus lands. [TEST + BLOCKED]

## 16. Latency / RTF

Desktop CPU (4 threads, 30 s stream, 1.28 s chunks): fp32 RTF 0.6226, int8 0.7322 [BENCH].
First-partial latency: ≤ ~200 ms + chunk time by design [CODE]; measured value:
NOT MEASURED (device). P2 also measured the official wrapper: chunked RTF 0.90/0.67/2.19/3.20
by lookahead mode — the O(n²) whole-buffer refeature cost the Kotlin port removes. [BENCH]

## 17. Low-End Device Results

**NOT MEASURED ON PHYSICAL DEVICE.** No device attached (`scratch/sravaani/logs/device_bench/
summary_20260917T132751Z.json`, status NOT_MEASURED with reason). Structural expectation:
2 GB-RAM devices will not run the fp32 path (1.77 GB mapped graph + working set) —
recorded as a known limitation, not hidden.

## 18. Mid-Range Device Results

**NOT MEASURED ON PHYSICAL DEVICE.** Runner ready:
`adb`-attached device + `python scripts/sravaani/run_device_bench.py` produces
per-device JSON (cold/warm init, first-partial latency, RTF cold+warm, PSS delta,
device class stamped from /proc/meminfo).

## 19. Offline Verification

No runtime downloads exist in the STT path (download logic removed in P4; `SttModelManager`
checks packaged assets or previously-extracted files only). Extraction is hash-verified
before first use; `checkSravaaniModel` fails the build on pin mismatch. [CODE+TEST]

## 20. Failure Handling

Missing/corrupt asset → `SttException.ModelLoadError` at initialize (factory falls back to
`DefaultSttEngine` empty-results path — loud diagnostics, no crash, no substitute model).
Failed init leaves no half-initialized state; push-before-init fails loudly (P10 tests).
Mic loss → pipeline idle-reset (guarded by test). OOM → `SttException.OutOfMemory`. [CODE+TEST]

## 21. Security / Integrity

SHA-256 pins: HF manifest → export meta → packaging verifier → runtime re-verification
each start. Gated repo accessed with user token via env; no secrets in repo. No network
in STT path. [CODE+TEST]

## 22. Known Limitations

1. fp32 artifact 1.77 GB (storage) — int8 candidate 638.7 MB exists but packaging decision awaits device data.
2. Low-end (2 GB RAM) devices: not viable on fp32 path.
3. Desktop int8 is slower than fp32 on ORT CPU — device kernel advantage unproven until P11.
4. Corpus WER unknown (P8 BLOCKED) — no accuracy claims are made anywhere.
5. APK size delta and extraction/load time on device: NOT MEASURED.
6. `autoDetectLanguage` is a documented no-op (model is language-unconditioned) — detection not claimed.

## 23. Production Acceptance Matrix

| Gate | Status |
|---|---|
| SraVaani 0.5 is the only STT model | MET (Vosk + substitutes removed, guarded by tests) |
| Exact artifact + provenance + license + runtime verified | MET [BENCH] |
| 10 languages, no aliasing | MET [BENCH+TEST]; per-language accuracy unmeasured (corpus BLOCKED) |
| Fresh install, offline, no downloads/cloud | MET [CODE] |
| Audio contract, VAD, sentence segmentation, no clipping | MET [TEST] |
| True streaming, partials+finals, cache correct, no O(n²) | MET [CODE+TEST] |
| WER/CER implemented | MET [TEST]; baseline numbers BLOCKED (corpus) |
| Optimization implemented + artifact validated | MET [BENCH] (equivalence PASS); WER parity on corpus BLOCKED |
| Device matrix (low/mid), lifecycle, memory | NOT MEASURED ON PHYSICAL DEVICE (runner ready) |
| Every milestone committed on `stt-sravaani` | MET (P1–P12, see ledger) |

## 24. Evidence Index

| Document | Content |
|---|---|
| docs/STT_SRAVAANI_PROVENANCE.md | P1 artifact hashes, license, reproducibility |
| docs/STT_SRAVAANI_GRAPH_VALIDATION.md | P2 contract, latency modes, op reality |
| docs/STT_SRAVAANI_AUDIO_CONTRACT.md | P5 input contract, VAD params + matrix |
| docs/STT_SRAVAANI_LANGUAGE_COVERAGE.md | P6 per-language vocab measurement |
| docs/STT_SRAVAANI_OPTIMIZATION.md | P9 int8 method, equivalence gate, honest RTF |
| scratch/sravaani/logs/*.json | machine-readable bench/equivalence results |
| STT_SRAVAANI_TASK_LEDGER.md | per-milestone status, tests, commits |

Tests: 161 JVM tests green across `app.swarsetu.stt.*` (P10 final regression), incl.
streaming (10), VAD matrix (12), segmentation (2), packaging (5), coverage (5), eval (25),
lifecycle (5), golden featurizer/decoder (16).

## 25. Remaining Blockers

1. **Corpus WER (P8)** — BLOCKED: no license-verified corpus staged. Unblocks: stage corpus, run `eval_wer.py`.
2. **Device matrix (P11)** — BLOCKED: no physical device attached. Unblocks: attach device, run `run_device_bench.py`; then decide fp32 vs int8 packaging (§14).
3. **APK/AAB size delta** — NOT MEASURED: requires a device build with staged graphs.

Final question (master §28): *Is SraVaani 0.5 genuinely implemented as an offline,
multilingual, low-latency Android STT system for all 10 SIH languages, with validated
optimization?* — **Implementation: YES, with evidence above. Validated device optimization
+ WER: PARTIALLY — exactly two blockers remain, both hardware/corpus-availability, both
instrumented and one-command runnable once available.**
