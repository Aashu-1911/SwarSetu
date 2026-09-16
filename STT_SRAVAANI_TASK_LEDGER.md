# STT SraVaani 0.5 — Live Task Ledger (SIH 26173)

Branch: `stt-sravaani` (from `origin/main`, created 2026-09-15)
Master plan: `docs/STT_SRAVAANI_IMPLEMENTATION_PROMPTS.md`
Rules: Never mark COMPLETE without evidence. Never fabricate. `BLOCKED` requires reason. `NOT MEASURED` is valid.

| ID | Prompt | Status | Files | Tests | Benchmark | Commit | Evidence |
|----|--------|--------|-------|-------|-----------|--------|----------|
| P1 | Artifact acquisition + provenance vault | COMMITTED | scripts/sravaani/download_model.py, verify_manifest.py; docs/STT_SRAVAANI_PROVENANCE.md | verify_manifest.py exit-0 (all 8 files) | sha256 table in provenance doc | 8bed260→ | manifest.json; docs/STT_SRAVAANI_PROVENANCE.md |
| P2 | Graph/operator/runtime validation | COMMITTED | scripts/sravaani/p2_smoke.py, validate_graph.py; docs/STT_SRAVAANI_GRAPH_VALIDATION.md | p2_smoke.py exit-0 (contract + 4 latency modes) | desktop RTF 0.90/0.67/2.19/3.20; RSS NOT MEASURED (Windows probe) | this commit | logs/p2_smoke.json; graph validation doc |
| P3 | Android engine integration (SravaaniEngine) | TESTED | SravaaniEngine.kt, SravaaniFeaturizer.kt, SravaaniTables.kt, SravaaniCtcDecoder.kt, SravaaniTokenizer.kt, SravaaniStreamingState.kt, SttEngineFactory.kt, libs.versions.toml, build.gradle.kts, gradle.lockfile, assets/sravaani/{tables.bin,tokens.txt} | SravaaniFeaturizerGoldenTest (4), SravaaniDecodeTest (12) — 16/16 pass; compileDebugKotlin clean | cold init / first partial: NOT MEASURED (needs on-device P10) | see P3 commit | true-streaming loop ported 1:1 from official wrapper; ORT 1.27.0 pinned = equivalence-gate runtime; graphs staged in scratch/sravaani/onnx (equivalence: feat rel 2.6e-05, enc rel 4.7e-05, structured token streams 100%); device packaging deferred to P7 |
| P4 | True streaming pipeline (kill O(n²)) | NOT_STARTED | SttPipeline.kt | SttPipelineStreamingTest | pipeline overhead ~O(n) | — | | 
| P5 | Audio + VAD validation for SraVaani | NOT_STARTED | VoiceActivityDetector.kt, SttPipeline.kt | VoiceActivityDetectorMatrixTest | VAD decision accuracy per condition | — | — |
| P6 | 10-language verification (alias removal) | NOT_STARTED | SttModelManager.kt, SttLanguage.kt, scripts/sravaani/language_smoke.py | script-block assertions | per-language smoke pass/fail | — | — |
| P7 | Offline packaging + integrity | NOT_STARTED | build.gradle.kts, SttModelManager.kt, .gitattributes, checkSravaaniModel task | Robolectric corrupt-fixture fallback | APK/AAB delta, extraction+load time | — | — |
| P8 | WER/CER harness + baseline | NOT_STARTED | WerCalculator.kt, CerCalculator.kt, scripts/sravaani/eval_wer.py, SttBenchmark.kt | WerCalculatorTest | baseline WER/CER ×10 langs ×2 environments | — | — |
| P9 | Optimization w/ equivalence proof | NOT_STARTED | scripts/sravaani/export_optimized.py, prove_equivalence.py; packaging manifest | equivalence exit-0; hash check | full §14 baseline-vs-optimized table | — | — |
| P10 | Memory/lifecycle/soak hardening | NOT_STARTED | SravaaniEngine.kt, lifecycle tests | instrumented memory/soak tests | peak/sustained PSS, soak | — | — |
| P11 | Physical-device benchmark matrix | NOT_STARTED | device runner, production doc §16–18 | runner smoke | full §17 matrix ×2 devices | — | — |
| P12 | Production docs + claim audit | NOT_STARTED | STT_SRAVAANI_PRODUCTION.md, ledger, README/ARCHITECTURE | full suite green | consolidate | — | — |

## Blockers / Notes

- **HF_TOKEN:** required for P1 (repo is gated). Not yet set in env. User has token ready.
- **Stashed WIP:** 5 TTS/mesh files from `stt-translate-tts` in `stash@{0}` — belongs to that branch, never commit here.
- **Physical devices:** availability unknown → P10/P11 may be partially `NOT MEASURED ON PHYSICAL DEVICE`.
- **Corpus licenses:** must be verified per dataset in P8 before any WER is reported.
- **Low-end RAM reality:** single ~0.7–0.9 GB artifact ⇒ low-end (2 GB RAM) support unlikely; will be recorded as measured limitation, not hidden.
- **Runtime decision:** deferred to P2 evidence per user directive (ONNX/ExecuTorch are candidates, not pre-selections).
