# SraVaani 0.5 STT — Implementation Prompts (SIH 26173)

Generated from the PHASE 0 forensic audit (branch `stt-sravaani`, cut from `origin/main`).
Every prompt is dependency-ordered, individually executable, testable, and commit-ready.

## Governing facts established by audit (do not re-litigate per prompt)

| Fact | Evidence |
|---|---|
| Model = `ARTPARK-IISc/SraVaani-0.5-live`, MIT, HF sha `29a15303a55673367134eee9e1d18cb1a2dcaabf` | HF API, verified 2026-09-15 |
| Ships: `model-asr-streaming.fp16.ts` (898,139,037 B), `preproc.pt` (135,269 B), `tokenizer.model` (325,287 B), `config.json`, `modeling_sravaani_streaming.py`, `example_streaming.py` | HF tree API with blobs |
| Architecture: cache-aware streaming FastConformer + TDT (`SraVaaniStreamingModel`, `sravaani_streaming_tdt`) | HF config metadata |
| Language tags include all 10 required: hi, gu, mr, kn, ml, ta, te, or, bn, en (21 total) | HF card data |
| Streaming API: `new_stream()` → `stream_audio(state, f32@16k)` → cumulative partials → `finalize(state)`; lookahead via `set_att_context_size([70,13|6|1|0])` ≈ 1040/480/80/0 ms | Official demo `app.py` |
| Repo is HF-gated (`auto`): downloads require an accepted account token | HF API `gated` field |
| Current repo STT is Vosk-based (forbidden substitute); kn/ml/or aliased to Hindi; models runtime-downloaded; pipeline re-transcribes full buffer; no WER/CER; no quantization | PHASE 0 audit |
| Prior `stt-sherpa-testing` branch used IndicConformer — forbidden model; usable only as runtime API reference | git history |
| Runtime path (ONNX Runtime / sherpa-onnx / ExecuTorch) must be chosen ONLY after graph/operator validation (P2) and numeric-equivalence proof (P9). No conversion before proof. | User decision 2026-09-15 |

Standing constraints for every prompt:
- SraVaani 0.5 remains the ONLY STT model. Never substitute Vosk/Whisper/IndicConformer/sherpa models/cloud.
- Do not modify TTS, translation, mesh, crypto, or UI beyond STT wiring seams.
- Never fabricate measurements. `NOT MEASURED` is a valid result. `BLOCKED` + reason is a valid status.
- All work on `stt-sravaani`. Milestone commit after tests pass. Inspect `git diff` before each commit.
- 5 unrelated TTS/mesh WIP files are stashed (`stash@{0}`) from `stt-translate-tts` — never commit them here.

---

## PROMPT 1 — Artifact Acquisition + Provenance Vault

### Objective
Obtain the 4 official SraVaani-0.5-live artifacts reproducibly, record cryptographic hashes and provenance, and make the layout git-safe (large binaries never committed).

### Current Repository State
No SraVaani artifacts anywhere. `scratch/` exists (gitignored). HF token available from user but not yet in env. Python 3.11 + torch 2.14 + onnx 1.22 + hf_hub 1.31 present.

### Dependencies
None (first milestone).

### Files To Inspect
- `scratch/` (ignore rules), `.gitignore`
- `gradle/libs.versions.toml` (no changes needed yet)

### Implementation
1. Add `scripts/sravaani/download_model.py`: uses `huggingface_hub.snapshot_download("ARTPARK-IISc/SraVaani-0.5-live", token=os.environ["HF_TOKEN"])` into `scratch/sravaani/hf/`, then emits `scratch/sravaani/manifest.json` with `{filename, size, sha256, hf_oid, hf_commit}` for each artifact.
2. Add `scratch/sravaani/` (and `*.ts`, `*.pt` under it) to `.gitignore`.
3. Write `docs/STT_SRAVAANI_PROVENANCE.md`: repo id, commit sha, gate policy, MIT license text reference, file table (name/size/sha256), download date, redistribution statement (MIT permits redistribution; keep attribution + license copy bundled with the model).
4. Record `config.json` contents verbatim (model_type, architectures) in the doc.
5. HF token handling: user sets `HF_TOKEN` in shell env; script MUST NOT read tokens from files inside the repo and MUST NOT log it.

### Constraints
Do not commit any model binary. Do not hardcode or commit the token. Do not modify app code.

### Tests
- `scripts/sravaani/verify_manifest.py` (self-check): recomputes SHA-256 of each file vs manifest; exit non-zero on mismatch. Run it as the test.

### Benchmarks
- File sizes + sha256 table (these ARE the deliverable).

### Acceptance Criteria
- All 4 files present in `scratch/sravaani/hf/` with exact expected sizes (`.ts` = 898,139,037 B ±0).
- `verify_manifest.py` passes. `docs/STT_SRAVAANI_PROVENANCE.md` complete. `git status` shows no new tracked binaries.

### Evidence To Record
- `docs/STT_SRAVAANI_PROVENANCE.md` (hashes, dates, commit sha).

### Commit
`feat(stt): acquire SraVaani-0.5-live artifacts with reproducible provenance manifest`

### Rollback/Safety
Delete `scratch/sravaani/` and the two new files; revert docs. No app code touched.

---

## PROMPT 2 — Graph/Operator/Runtime Compatibility Validation (no conversion)

### Objective
Prove what is actually inside the TorchScript graph (ops, dtypes, streaming entry points, cache contract) on desktop CPU, produce a reference numerics baseline, and shortlist Android runtimes by evidence — without converting anything.

### Current Repository State
Artifacts in `scratch/sravaani/hf/` (after P1). Official `modeling_sravaani_streaming.py` + `example_streaming.py` available as reference semantics. No validation code exists.

### Dependencies
P1.

### Files To Inspect
- `scratch/sravaani/hf/modeling_sravaani_streaming.py`
- `scratch/sravaani/hf/example_streaming.py`
- `scratch/sravaani/hf/config.json`

### Implementation
1. `scripts/sravaani/validate_graph.py`:
   - `torch.jit.load("model-asr-streaming.fp16.ts", map_location="cpu")` (FP16 on CPU may require `.float()` casts or running via `bfloat16` — record what works).
   - Dump `model.code` for the scripted module; enumerate all ATen/prim ops; write `docs/` op inventory (dedup list + counts). Flag any op absent from candidate runtimes' opsets.
   - Discover the scripted streaming contract (expected: NeMo-style ConformerEncoder 5-in/5-out cache-aware API plus ctc/tdt decoder; per community export notes). Record exact method signatures for: stream creation, per-chunk audio ingest, partial text, finalize, attention-context setter.
   - Load `preproc.pt`, identify its module type and normalization semantics (expected per-window feature normalization; document exact behavior — wrong normalization silently yields empty output).
   - Load `tokenizer.model` with sentencepiece; record vocab size, expected to equal model output dim (5,001 per community notes — VERIFY from the actual graph, don't trust the note).
2. `scripts/sravaani/benchmark_reference.py`: desktop CPU baseline — synthetic + recorded-sample ingest through the official streaming API at each att_context `[70,13]`,`[70,6]`,`[70,1]`,`[70,0]`; measure chunk latency, RTF, peak RSS; verify partials are cumulative/monotonic and `finalize()` completes.
3. `docs/STT_SRAVAANI_GRAPH_VALIDATION.md`: op inventory, cache contract, preproc semantics, tokenizer facts, CPU baseline table, and a runtime shortlist verdict: ONNX Runtime Mobile vs sherpa-onnx (runtime only) vs ExecuTorch vs TorchScript-on-Android — each scored against: op coverage, FP16/int8 support for those ops, streaming cache ergonomics, APK/native size, license, maintenance. CHOOSE the primary candidate + fallback. If none support the ops: mark BLOCKED with the blocking op list.

### Constraints
No model conversion. No app code changes. Do not trust community-export claims as verification — re-derive from the actual graph.

### Tests
- `validate_graph.py` asserts: model loads, streaming methods exist with expected signatures, tokenizer vocab == output dim, one full stream→finalize cycle on 3 s of audio returns non-empty cumulative text (content-free assertion: non-empty + script-consistent, no accuracy claim).

### Benchmarks
- Desktop CPU: per-chunk latency, RTF, peak RSS at each latency mode (record as BASELINE-DESKTOP; Android numbers come later).

### Acceptance Criteria
- Op inventory + cache contract documented with method signatures.
- One runtime primary + one fallback selected WITH recorded reasons.
- Baseline numerics harness runs end-to-end.

### Evidence To Record
- `docs/STT_SRAVAANI_GRAPH_VALIDATION.md`, benchmark stdout saved to `scratch/sravaani/logs/`.

### Commit
`feat(stt): validate SraVaani-0.5 graph, streaming contract, and select Android runtime candidate`

### Rollback/Safety
Docs + scripts only; delete and revert. No app impact.

---

## PROMPT 3 — Android Engine Integration (SravaaniEngine)

### Objective
Implement `SravaaniEngine : SttEngine` executing SraVaani-0.5 weights offline on-device via the P2-selected runtime; single shared multilingual artifact; correct lifecycle/threading.

### Current Repository State
`SttEngine`/`SttConfig`/`SttResult` contracts exist (verified). `VoskEngine` + `SttModelManager` (download/alias logic) exist and must be superseded, not entangled. DI via Koin; project pattern: Mutex + runCatching graceful degradation.

### Dependencies
P2 (runtime must be selected).

### Files To Inspect
- `app/src/main/java/app/swarsetu/stt/SttEngine.kt`, `SttConfig.kt`, `SttResult.kt`, `SttException.kt`, `SttEngineFactory.kt`
- `app/src/main/java/app/swarsetu/stt/VoskEngine.kt` (pattern reference only)
- `app/src/main/java/app/swarsetu/stt/SttModelManager.kt`
- `app/src/main/java/app/swarsetu/mesh/...` call sites that construct engines via factory

### Implementation
1. Add selected runtime dependency to `gradle/libs.versions.toml` + `app/build.gradle.kts` (version catalog only; no detekt-suppressed shortcuts).
2. `SravaaniEngine`: loads model files extracted to `context.filesDir/stt/sravaani/` (idempotent, SHA-256-verified extraction — integrity hook formalized in P7); FP16/int8 per P2 finding; per-stream state object for streaming; `transcribe()` = stream over chunked audio → finalize; `transcribeStream()` = true cache-based streaming emitting cumulative partials then final; Mutex serialization; `setLanguage` = config update only (single model) — document that language is NOT a model input.
3. Replace `SttEngineFactory` wiring to build `SravaaniEngine` (Koin module update). Keep `DefaultSttEngine` as fallback when artifacts absent (graceful degradation per contract).
4. `SttModelManager`: strip Vosk download/alias paths that Sravaani obsoletes; keep packaging utilities reused by P7. If removal is entangled, isolate Vosk behind the factory and mark deletion for P7.
5. `SttConfig.autoDetectLanguage`: implement honest semantics — model is unconditioned multilingual (verified: no language argument in official API). The field, when true, MUST NOT claim LID; it permits the single-model path and exposes transcript language inference separately. If cleaner: deprecate the field with a documented note. Either way, no fake auto-detection claims.

### Constraints
Do not break `SttEngine` ABI for voice/mesh call sites. Do not remove `DefaultSttEngine`. No TTS/translation/mesh changes.

### Tests
- JVM: `SravaaniEngineTest` with fake runtime seam (engine logic: chunking, partial monotonicity, finalize, release idempotence, error paths → empty results).
- Robolectric: extraction + factory wiring.
- Existing STT suite must stay green.

### Benchmarks
- Instrumented (if device present) or NOT MEASURED: cold initialize, first partial latency.

### Acceptance Criteria
- `SttEngineFactory` produces `SravaaniEngine`; unit tests pass; no Vosk inference path reachable from app flows.
- `transcribeStream` emits ≥1 partial + 1 FINAL for any non-empty buffer.

### Evidence To Record
- Test logs; new/changed files list; dependency versions added.

### Commit
`feat(stt): integrate SraVaani 0.5 runtime engine behind SttEngine contract`

### Rollback/Safety
Revert commit restores Vosk wiring; `SttEngine` untouched ensures call-site safety.

---

## PROMPT 4 — True Streaming Pipeline (kill O(n²))

### Objective
Rewire `SttPipeline` to stream via the engine's cache-based path: one pass, cumulative partials, VAD-driven finalization. No full-buffer retranscription.

### Current Repository State
`SttPipeline.kt` re-transcribes the entire accumulated buffer every ~1 s (`partialPcm = ShortArray(allSamples.size)` pattern) — O(n²). Silence-timeout finalization exists.

### Dependencies
P3.

### Files To Inspect
- `app/src/main/java/app/swarsetu/stt/SttPipeline.kt`
- `app/src/main/java/app/swarsetu/stt/PcmCapture.kt`
- `app/src/main/java/app/swarsetu/stt/VoiceActivityDetector.kt`
- `app/src/test/java/app/swarsetu/stt/*` existing pipeline tests

### Implementation
1. Pipeline feeds fixed chunks (e.g. 100–320 ms) into `engine.transcribeStream`; UI amplitude meter unchanged.
2. Partials = engine's cumulative emissions (no re-assembly in pipeline). Final emission on: VAD silence timeout (existing 2 s default) or capture stop → engine finalize.
3. Bound audio accumulation (ring buffer) consistent with `maxAudioDurationMs`; streaming state — not buffer size — carries context.
4. Preserve public pipeline API (flows/state) so UI/mesh call sites don't change.

### Constraints
No UI changes. `PcmCapture` format unchanged (16k/mono/16-bit). Existing tests updated, not deleted, unless they encode the O(n²) behavior (then replace with equivalent streaming assertions and note it).

### Tests
- `SttPipelineStreamingTest`: partial monotonic growth; exactly one FINAL; silence → finalize; stop → finalize; no code path calls full-buffer transcribe (assert via engine spy counters).

### Benchmarks
- JVM: pipeline overhead per 10 s utterance (should be ~O(n)); device numbers in P11.

### Acceptance Criteria
- Engine full-buffer `transcribe()` count == 0 during a streaming capture; partials arrive ≥ every 500 ms of speech (synthetic clock).

### Evidence To Record
- Test output; diff summary.

### Commit
`feat(stt): stream SraVaani inference in pipeline with single-pass partials and VAD finalization`

### Rollback/Safety
Single-commit revert restores old pipeline; engine seam unchanged.

---

## PROMPT 5 — Audio Pipeline + VAD Validation for SraVaani

### Objective
Verify the capture/preprocess/VAD chain against SraVaani's verified input requirements; tune VAD for pause-based sentence formation without clipping; justify or reject each preprocessing stage.

### Current Repository State
`PcmCapture`: 16 kHz/mono/PCM16, VOICE_RECOGNITION source, 2× min buffer (matches model input). `VoiceActivityDetector`: RMS energy, silence=200/speech=400, 20-frame hangover, 3-frame trigger, ~30 ms frames. No preprocessing (no NS/AGC/filter) — VOICE_RECOGNITION disables platform AGC/NS.

### Dependencies
P4.

### Files To Inspect
- `PcmCapture.kt`, `VoiceActivityDetector.kt`, `SttPipeline.kt`
- `app/src/test/java/app/swarsetu/stt/VoiceActivityDetectorTest.kt`
- P2's preproc findings (per-window normalization is IN-model; external normalization may be harmful)

### Implementation
1. Matrix-test VAD with synthetic fixtures: short speech (<1 s), long (30 s), intra-sentence pauses (0.5/1/1.5 s), final pause, multi-sentence, steady noise (fan/traffic-like spectra), quiet (-30 dBFS), loud (-3 dBFS). Fix: no word clipped at utterance end (hangover validation), no missed start (trigger validation).
2. Preprocessing evaluation: for each candidate (resample—N/A, mono—done, normalization, NS, AGC, bandpass, trimming) mark REQUIRED/REJECTED/TEST-LATER with one-line reason grounded in P2 preproc semantics. Default posture: feed raw 16k PCM (model normalizes internally); only add stages if P8 WER shows measurable gain.
3. If thresholds need adaptation: implement noise-floor-adaptive thresholds with tests, keep constants as fallback.

### Constraints
Do not add DSP stages "because they sound useful" — evidence-only adoption (master §8).

### Tests
- `VoiceActivityDetectorMatrixTest` (fixtures generated in-test, deterministic).
- Pipeline integration: speech→pause→speech produces two finalized sentences.

### Benchmarks
- VAD decision accuracy per condition (speech/non-speech frame labels from fixture ground truth).

### Acceptance Criteria
- Zero clipped-word cases in matrix; two-sentence segmentation passes; preprocessing decisions documented.

### Evidence To Record
- Matrix results table in `docs/STT_SRAVAANI_PRODUCTION.md` §8.

### Commit
`feat(stt): validate VAD sentence segmentation and lock SraVaani audio input contract`

### Rollback/Safety
VAD changes are parameter-level; revert restores defaults.

---

## PROMPT 6 — Ten-Language Support Verification (aliasing removal)

### Objective
Prove all 10 languages work through the single multilingual model with correct-script output; eliminate the kn/ml/or→Hindi alias concept; state honest language-handling semantics.

### Current Repository State
SraVaani card tags cover all 10 (VERIFIED BY MODEL METADATA). Engine is unconditioned multilingual: model emits the spoken language's script with NO language input (verified from official demo API). `SttLanguage` has per-language `assetDir` entries; `SttModelManager` has `CANONICAL_ALIASES` (kn/ml/or→hi) — Vosk-era concept.

### Dependencies
P4 (streaming engine), P1/P3 (artifact + engine).

### Files To Inspect
- `SttLanguage.kt`, `SttModelManager.kt`, `SravaaniEngine.kt`
- `docs/STT_SRAVAANI_PROVENANCE.md`

### Implementation
1. Packaging note: one shared artifact serves all languages; per-language `assetDir` entries collapse to the shared model path (or are retained as UI-compat aliases to the SAME artifact — document that this is packaging aliasing, NOT model aliasing, which is now meaningless).
2. Remove `CANONICAL_ALIASES` logic entirely.
3. Language-handling documentation (production doc §6): user-selected language = UI/routing/metrics only; model conditioning = none (unconditioned); automatic LID = NOT CLAIMED; transcript language inference = observable output-script property; script detection (utility only) + code-switch behavior documented (model code-switches to Latin for real English loanwords — do not gate on script purity).
4. Verification harness `scripts/sravaani/language_smoke.py` (desktop) + instrumented smoke (device): one short fixture utterance per language through the engine; assert output script belongs to the expected Unicode block for ≥9/10 languages (kn/ml/or MUST be native script, not Devanagari — this kills the old alias failure mode).

### Constraints
No fake auto-detect. No per-language model copies.

### Tests
- Script-block assertions per language; `SttLanguage` regression tests stay green.

### Benchmarks
- Per-language smoke pass/fail (accuracy numbers come from P8).

### Acceptance Criteria
- kn/ml/or produce Kannada/Malayalam/Odia scripts on smoke fixtures; alias code deleted.

### Evidence To Record
- Smoke outputs saved to `scratch/sravaani/logs/language_smoke/`.

### Commit
`feat(stt): verify 10-language coverage via single SraVaani model; remove Hindi aliasing`

### Rollback/Safety
Revert restores aliases (harmless but wrong); no data migration.

---

## PROMPT 7 — Offline Packaging + Integrity

### Objective
Guarantee: fresh install + airplane mode + STT works. Decide packaging strategy; bundle the artifact; SHA-256-verify at extraction; remove all STT runtime-download paths.

### Current Repository State
`assets/` has only `moderation/`. Old Vosk manager downloaded from alphacephei.com. Model artifact is ~660–900 MB (size depends on P9 candidate) — far above Play's 200 MB base APK guidance but installable via AAB asset packs.

### Dependencies
P3 (engine extraction seam). Final artifact pinned by P9; this prompt implements mechanism + ships current-best artifact.

### Files To Inspect
- `app/build.gradle.kts` (asset config, `noCompress` list, ABI splits)
- `SttModelManager.kt` (download code), `AndroidManifest.xml`
- `.gitattributes` (LFS policy: repo already tracks `*.onnx` via LFS)

### Implementation
1. Decide + document: install-time asset pack (AAB) for the model, with APK-asset fallback for sideload/debug builds. Register extension in `androidComponents { noCompress }` (e.g. `.onnx`/`.pte`/`.ts`) so it memory-maps instead of inflating.
2. Extraction: `assets → filesDir/stt/sravaani/` streaming copy with SHA-256 verification against a build-time-generated manifest; mmap-friendly file placement.
3. Delete STT runtime download path + alphacephei URLs; delete/retire `INTERNET`-dependent STT code paths (keep INTERNET permission if other features need it — verify translation does).
4. Build check task (pattern exists: `checkModerationModels`): `checkSravaaniModel` asserts presence + size + hash in CI.
5. Update `.gitattributes` LFS rule for the chosen model extension if the artifact is committed to LFS (alternative: keep artifact out of git, fetched by script — document choice).

### Constraints
No Cloud/CDN dependency for STT. Play Asset Delivery config must not break existing AAB.

### Tests
- Robolectric: extraction integrity (corrupt fixture → load fails → `DefaultSttEngine` fallback, no crash).
- Instrumented (or NOT MEASURED): airplane-mode fresh install smoke.

### Benchmarks
- APK/AAB size delta; extraction time; model load time (cold).

### Acceptance Criteria
- `checkSravaaniModel` passes in build; no HTTP client references remain in STT code; size delta recorded.

### Evidence To Record
- Sizes, hashes, build task output.

### Commit
`feat(stt): bundle SraVaani model offline with integrity-verified extraction`

### Rollback/Safety
Revert restores download path; extraction manifest versioned separately from weights.

---

## PROMPT 8 — WER/CER Evaluation Harness + SraVaani Baseline

### Objective
Implement genuine WER=(S+D+I)/N and CER; establish a licensed, reproducible multilingual corpus; measure the UNOPTIMIZED SraVaani baseline per language (this is the gate for P9).

### Current Repository State
`SttBenchmark.kt` measures latency/RTF only, on 440 Hz sine waves. No WER/CER, no reference transcripts, no corpus.

### Dependencies
P3 (engine), P6 (language smoke sanity).

### Files To Inspect
- `SttBenchmark.kt`, `SttResult.kt`
- Corpus candidates: VAANI (data source of this model; license/access on application), IndicVoices, Kathbath, IndicTTS-phrase, FLEURS (Google, CC-BY), OpenSLR Indic sets — VERIFY license + access per dataset before use; record choice.

### Implementation
1. Kotlin `WerCalculator` + `CerCalculator`: Levenshtein-based (S/D/I), unicode-aware normalization (NFC, casefold for Latin, punctuation strip rules, Indic-specific: vowel-sign handling documented). Exhaustive unit tests with known S/D/I cases.
2. `scripts/sravaani/eval_wer.py`: batch-eval corpus utterances (desktop or device pull) → per-language + aggregate table (WER, CER, n, duration, conditions).
3. Corpus: prefer ≥50 utterances/language × 10 languages; record dataset, source, license, counts, duration, speaker mix, normalization rules in production doc §15. If a needed language's data is unavailable → per-language BLOCKED row, not fabricated data.
4. Measure baseline with the official FP16 TorchScript artifact (desktop) AND the packaged engine (device if available). Both labeled separately. These numbers define P9's regression gate.

### Constraints
No synthetic-speech accuracy numbers ever reported as WER. "transcript != empty" is never accuracy.

### Tests
- `WerCalculatorTest`: hand-computed S/D/I cases incl. Indic normalization edge cases.

### Benchmarks
- Baseline WER/CER per language ×2 environments (desktop/device or NOT MEASURED ON PHYSICAL DEVICE).

### Acceptance Criteria
- Harness runs; baseline table complete for every language that has corpus data; gaps BLOCKED-labeled.

### Evidence To Record
- Corpus manifest; raw score CSVs in `scratch/sravaani/logs/eval/`; tables in production doc §15.

### Commit
`test(stt): add WER/CER evaluation harness and SraVaani baseline results`

### Rollback/Safety
Additive; revert removes harness only.

---

## PROMPT 9 — Optimization With Numeric-Equivalence Proof (no blind INT8)

### Objective
Produce the optimized production artifact ONLY via proven-equivalent conversion/quantization; compare vs P8 baseline; pick the best validated candidate (FP16 vs int8 vs graph-opt).

### Current Repository State
P2 established op inventory + runtime shortlist. P8 established baseline WER/CER + desktop RTF/RSS. Community int8-ONNX recipe exists (MatMul-only int8, weights untouched) with published hi/gu WER parity (29.6/30.3 vs 29.2/28.8 parent) — treat as HYPOTHESIS to reproduce, not fact.

### Dependencies
P8 (gate), P2 (graph knowledge), P7 (packaging slot).

### Files To Inspect
- `scripts/sravaani/validate_graph.py` outputs
- `docs/STT_SRAVAANI_GRAPH_VALIDATION.md`
- P8 baseline CSVs

### Implementation
1. `scripts/sravaani/export_optimized.py`: TorchScript → ONNX (opset fixed by P2 op coverage) → (optional) MatMul/weight-only int8 with calibration sets from corpus audio; fold `preproc.pt` normalization into graph (as community did) — verify numeric behavior explicitly.
2. `scripts/sravaani/prove_equivalence.py`: SAME utterance set through (a) official TorchScript, (b) FP16 ONNX, (c) int8 ONNX → per-utterance text equality rate + token-logit correlation on a probe set; assert text equality ≥ agreed threshold on hi+gu+2 more languages BEFORE accepting any artifact.
3. Re-run P8 WER/CER for the accepted artifact. Comparison table (master §14): size/RAM/cold/warm/RTF/CPU/WER/CER/lang-accuracy, baseline vs optimized, measured or NOT MEASURED.
4. Decide production artifact: best WER-retaining candidate under device constraints. If int8 regresses beyond gate → ship FP16 (larger) and document. If equivalence fails → BLOCKED, keep official artifact, document why.
5. Wire chosen artifact into P7 packaging (update manifest hash).

### Constraints
No quantization claims without the actual artifact + test evidence (master §23). WER regression gate: explicitly recorded in production doc §13 before comparing (default proposal: ≤ +1.5 WER absolute vs baseline, subject to gate note).

### Tests
- Equivalence script exit-0; packaging `checkSravaaniModel` updated hash passes.

### Benchmarks
- Full §14 table for accepted candidate.

### Acceptance Criteria
- One artifact chosen with evidence; table complete; regression gate honored.

### Evidence To Record
- CSVs, artifact hash, conversion settings JSON (provenance chain: HF sha → export script commit → artifact hash).

### Commit
`perf(stt): produce validated optimized SraVaani artifact with equivalence proof`

### Rollback/Safety
Previous artifact + hash retained in manifest history; revert restores FP16 packaging.

---

## PROMPT 10 — Memory, Lifecycle, and Soak Hardening

### Objective
Bound and measure runtime memory; lifecycle-safe load/release; survive memory pressure; repeated-use stability.

### Current Repository State
Single ~0.7–0.9 GB artifact ⇒ process will run heavy; streaming gives flat memory in utterance length (desktop RSS evidence from P2/P8). Engine loads on init; release exists; no LRU needed (single model) but leak/pressure behavior unproven on device.

### Dependencies
P9 (final artifact), P3/P4.

### Files To Inspect
- `SravaaniEngine.kt`, `SttPipeline.kt`, `MeshService.kt` (foreground service lifecycle interplay)

### Implementation
1. Instrumented memory tests: peak PSS after load, during 60 s stream, after release; assert release returns near pre-load PSS (documented tolerance).
2. Leak checks: 100 load/stream/release cycles — PSS plateau, no native growth (Debug.getNativeHeapAllocatedSize trend).
3. Memory-pressure behavior: onTrimMemory → release stream state (not necessarily model); re-init path tested.
4. Lifecycle: backgrounding mid-stream, process death + restore, mic loss/reconnect.

### Constraints
No changes outside stt/ unless a lifecycle bug is proven to live there.

### Tests
- All instrumented tests above (device or emulator-labeled NOT MEASURED ON PHYSICAL DEVICE).

### Benchmarks
- Peak/sustained PSS, cycle soak results, crash rate = 0 target over soak.

### Acceptance Criteria
- Soak passes; memory numbers recorded; no leak trend.

### Evidence To Record
- Instrumented logs; PSS curves (CSV) in `scratch/sravaani/logs/memory/`.

### Commit
`fix(stt): harden SraVaani engine memory lifecycle under pressure and soak`

### Rollback/Safety
Revert-safe; tests additive.

---

## PROMPT 11 — Physical Device Benchmarking (low + mid tier)

### Objective
Measure the §17 matrix on ≥2 physical devices; label anything unmeasured honestly.

### Current Repository State
All numbers so far desktop/emulator or NOT MEASURED. `SttBenchmark` exists (sine-based; supersede with corpus-based runner from P8 harness).

### Dependencies
P9/P10 (final artifact + hardened engine).

### Files To Inspect
- `SttBenchmark.kt` (extend or replace with device runner)
- `docs/STT_SRAVAANI_PRODUCTION.md` (results sections)

### Implementation
1. Device runner (instrumented or adb-driven): cold load, warm load, first-partial latency, final latency, RTF sustained, CPU%, peak/sustained PSS, 30-min soak crash count, battery proxy (BatteryManager discharge during fixed workload).
2. Run matrix × conditions (quiet/noise, volumes, speeds, short/long) × 10 languages where corpus allows.
3. Record device profiles (SoC, RAM, Android version). Fill production doc §16–§18 tables.

### Constraints
Emulator numbers NEVER presented as device numbers.

### Tests
- Runner smoke on device.

### Benchmarks
- The full matrix — this prompt's whole output.

### Acceptance Criteria
- §17 tables filled with measured values or explicit NOT MEASURED ON PHYSICAL DEVICE.

### Evidence To Record
- Raw logs + per-device CSVs; device profile table.

### Commit
`test(stt): physical-device SraVaani benchmark matrix (low/mid tier)`

### Rollback/Safety
Additive test infra + docs.

---

## PROMPT 12 — Production Documentation, Claim Audit, Final Regression

### Objective
Complete `STT_SRAVAANI_PRODUCTION.md` (25 required sections), sweep every claim into the verification taxonomy, finish the ledger, full regression, clean Git end-state.

### Current Repository State
Provenance + graph docs exist; ledger tracks P1–P11; production doc sections partially drafted by earlier prompts.

### Dependencies
All previous.

### Files To Inspect
- All `docs/STT_SRAVAANI_*.md`, `STT_SRAVAANI_TASK_LEDGER.md`, README STT sections, `docs/ARCHITECTURE.md` STT parts

### Implementation
1. Write/complete all 25 production-doc sections per master §25.
2. Claim audit (master §26): classify every STT claim in docs/code comments (VERIFIED BY CODE/TEST/BENCHMARK/MODEL DOC / EXTERNAL / UNVERIFIED / CONTRADICTED / NOT IMPLEMENTED / BLOCKED) — output table appended to production doc.
3. Final checklist (master §27) answered YES/NO item by item in production doc.
4. Full regression: `./gradlew test detekt ktlintCheck assembleDebug` green; instrumented suite where device available.
5. README/ARCHITECTURE STT sections updated to SraVaani reality (no stale Vosk claims).

### Constraints
No new features. Doc + hygiene only.

### Tests
- Full suite green is the test.

### Benchmarks
- None new; consolidate existing.

### Acceptance Criteria
- Checklist complete; zero UNVERIFIED claims about shipped capabilities without labels; ledger fully updated.

### Evidence To Record
- The production doc itself + claim-audit table.

### Commit
`docs(stt): SraVaani production documentation, claim audit, final regression`

### Rollback/Safety
Docs only.

---

## Dependency Graph

```
P1 ── P2 ── P3 ── P4 ── P5
              │     └── P6
              ├── P7 ──────────────┐
              └── P8 ── P9 ── P10 ─ P11 ── P12
                        └──(artifact)──^
```
