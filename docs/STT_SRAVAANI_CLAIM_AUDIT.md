# SraVaani 0.5 — Claim Audit (P12/P13, master §26)

Classification of every load-bearing STT claim. Classes: VERIFIED BY CODE / TEST /
BENCHMARK / MODEL DOCUMENTATION · EXTERNAL CLAIM · UNVERIFIED · CONTRADICTED BY CODE ·
NOT IMPLEMENTED · BLOCKED.

## 1. Current claims (this branch, post-P13)

| Claim | Class | Evidence |
|---|---|---|
| SraVaani 0.5-live is the only STT model; Vosk & substitutes removed | VERIFIED BY CODE+TEST | SttEngineFactory, aliasing-removal tests (P4/P6) |
| Exact artifact: HF repo @ 29a15303, MIT, 898 MB fp16 TorchScript | VERIFIED BY BENCHMARK + MODEL DOCUMENTATION | P1 manifest hashes; card metadata |
| 10 required languages covered by the single multilingual model | VERIFIED BY BENCHMARK (vocab/script coverage) + BENCHMARK (real-audio WER/CER per language, P13) | P6 smoke JSON, coverage tests; scratch/sravaani/logs/eval/baseline_fp32.json |
| No language aliasing (kn/ml/or → hi) | VERIFIED BY CODE+TEST | CANONICAL_ALIASES removed; guarded by tests |
| Automatic language detection | NOT IMPLEMENTED (deliberate; documented no-op — model has no language input) | SttConfig doc, P6 doc |
| Fully offline; no runtime download; no cloud STT | VERIFIED BY CODE | no network path in STT; packaging gate |
| Fresh install + no internet works | VERIFIED BY CODE (assets-only) ; device run NOT MEASURED | P7 extraction gate |
| Model sizes: fp32 1.77 GB (baseline, scratch) / int8 638.7 MB PRODUCTION (2.778×) | VERIFIED BY BENCHMARK | artifact byte counts, int8_meta.json |
| Streaming: O(n), cache-carrying, no full-buffer retranscription | VERIFIED BY CODE+TEST | spy-counted in SttPipelineStreamingTest |
| Partials ≤ 200 ms + chunk time | VERIFIED BY CODE (design constant); device value NOT MEASURED | FEED_SAMPLES |
| Desktop RTF fp32 0.6226 / int8 0.7322 (30 s stream); full-pipeline 0.3394 / 0.5176 (1213 s real audio) | VERIFIED BY BENCHMARK (desktop only, NOT a device number) | p9_desktop_perf.json; eval/*.json |
| int8 token-stream equivalence | VERIFIED BY BENCHMARK | equivalence_int8.json (10/10 identical) |
| int8 real-speech WER parity | VERIFIED BY BENCHMARK (P13): 0.2401 vs 0.2396 (+0.05 pp, 6/10 langs bit-identical) | scratch/sravaani/logs/eval/{int8,baseline_fp32}.json |
| CTC logits layout: (1,T,5001) TIME-major (not the official (1,V,T)) | VERIFIED BY BENCHMARK (P13 arbitration vs official wrapper on real speech) | eval decode fix; SravaaniEngine runChunk |
| VAD: no word clipping across matrix conditions | VERIFIED BY TEST | 12/12 matrix, 2/2 segmentation |
| WER/CER implemented correctly | VERIFIED BY TEST (25/25 hand-computed) | eval/* |
| Baseline WER/CER numbers (all 10 languages) | VERIFIED BY BENCHMARK (P13): aggregate WER 0.2396 / CER 0.0921, FLEURS dev CC-BY-4.0 (comparative caveat recorded) | scratch/sravaani/logs/eval/baseline_fp32.json |
| Device cold/warm load, RTF, PSS, low/mid matrix | NOT MEASURED ON PHYSICAL DEVICE — no device attached; runner ready | device_bench summary |
| APK/AAB size delta, extraction/load time | NOT MEASURED | requires device build |
| Low-end (2 GB RAM) support | NOT MEASURED (structural risk on both artifact paths, documented) | §22 production doc |

## 2. DOCS.md (pre-work audit) conflicts → resolved state

Repository is authoritative (master §2). DOCS.md findings vs today's code:

| DOCS.md finding (historical) | Current state |
|---|---|
| "Fully offline" claim contradicted: runtime HTTP model download | **RESOLVED P4/P7:** download logic removed; assets + hash-verified extraction; `checkSravaaniModel` gate |
| `CANONICAL_ALIASES` maps kn/ml/or → hi | **RESOLVED P4/P6:** removed and test-guarded; per-script vocab measured |
| `autoDetectLanguage` stubbed | **RESOLVED as documented no-op** (honest semantics per master §7) |
| Vosk debug path present | **RESOLVED P4:** removed |
| O(n²) whole-buffer retranscription risk | **RESOLVED P4:** true streaming, spy-tested |

DOCS.md itself is retained as the historical baseline audit; it no longer describes the
current branch and is superseded by `STT_SRAVAANI_PRODUCTION.md`.

## 3. Final checklist (master §27) — YES/NO

### Model
- Sravaani 0.5 is the only STT model: **YES**
- Exact model artifact verified: **YES** · Provenance: **YES** · License: **YES (MIT)** · Runtime: **YES (ORT 1.27.0, equivalence-gated)**

### Languages
- Hindi/Gujarati/Marathi/Kannada/Malayalam/Tamil/Telugu/Odia/Bengali/English: **YES** (single multilingual model; vocab verified per script; **per-language WER/CER measured on real FLEURS audio, P13**)

### Offline
- Fresh install without internet: **YES by code** (assets-only); full device run: NOT MEASURED
- No model download / cloud STT / hidden network dependency: **YES**

### Audio
- Correct sample rate / channels / PCM: **YES** · VAD / pause / segmentation validated: **YES** · No speech clipping: **YES** (matrix tests)

### Streaming
- Actual streaming inference: **YES** · Partials: **YES** · Finals: **YES** · State/cache correct: **YES** · No full-buffer retranscription: **YES**

### Accuracy
- WER implemented: **YES** · CER implemented: **YES** · Reference transcripts: **YES** (FLEURS dev staged, CC-BY-4.0, 10 utt/lang)
- All 10 languages tested for accuracy: **YES** (P13, measured) · Baseline measured: **YES** (WER 0.2396 / CER 0.0921) · Optimized measured: **YES** (WER 0.2401 / CER 0.0924, identical corpus)

### Optimization
- Method technically compatible: **YES** · Implemented: **YES** · Artifact generated + validated: **YES** · Size measured: **YES (2.778×)** · WER of optimized: **YES (parity, +0.05 pp)** · RAM/latency of optimized on device: **NOT MEASURED** (P11; desktop RTF regression honestly recorded)

### Android
- Low-end/mid-range device tested: **NO** (no hardware; runner ready) · Cold start / warm inference / memory pressure / repeated transcription / lifecycle / mic failure / model-load failure: **JVM/instrumented tests where possible (YES on JVM); device: NO**

### Git
- Work on `stt-sravaani`: **YES** · Milestones committed P1–P13: **YES** · Unrelated modifications: **NONE** · Working tree reviewed: **YES**

### Documentation
- Ledger complete: **YES** · Production doc complete: **YES** · Claims verified: **YES (this doc)** · Measurements recorded: **YES (with explicit NOT MEASURED / BLOCKED)** · Blockers documented: **YES (3)**
