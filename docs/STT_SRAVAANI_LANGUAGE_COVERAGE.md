# SraVaani 0.5 — Ten-Language Coverage (P6)

Status: P6 evidence. Vocabulary facts are verified from the packaged artifact
(`assets/sravaani/tokens.txt`, generated from the official `tokenizer.model` in P1/P3) and the
exported graph (P3). Accuracy per language is NOT claimed here — P8 measures WER/CER on a
licensed corpus; on-device per-language smoke happens in P10/P11.

## 1. Language-handling semantics (honest classification)

| Concept | Status | Explanation |
|---|---|---|
| User-selected language | IMPLEMENTED | `SttPipeline.startCapture(language)`; UI/routing/metrics use it (`VoiceMessageAdapter`) |
| Model language conditioning | NONE | the model takes NO language input — verified: encoder graph inputs contain no language field (`language_smoke.py` graph scan) |
| Automatic language identification | NOT CLAIMED | no LID model exists in the system; `SttConfig.autoDetectLanguage` is a documented no-op |
| Transcript language inference | OBSERVABLE OUTPUT PROPERTY | the model emits the spoken language's script; the transcript's script block identifies what was spoken — this is a post-hoc observation, not detection |
| Script detection | UTILITY ONLY | possible post-processing helper; never presented as language detection |
| Code-switch behavior | DOCUMENTED | the model emits Latin pieces for genuine English loanwords inside Indic speech — do not gate on script purity |

## 2. Measured vocab coverage (VERIFIED, 2026-09-17)

Evidence: `scratch/sravaani/logs/language_smoke/p6_smoke.json` (regenerate with
`python scripts/sravaani/language_smoke.py --out scratch/sravaani/logs/language_smoke/p6_smoke.json`).
Kotlin mirror: `SravaaniLanguageCoverageTest` (runs in CI).

| Language | Script block | Dedicated pieces | Status |
|---|---|---:|---|
| Hindi (hi) | Devanagari | 1474 | PASS |
| Marathi (mr) | Devanagari (shared with hi) | 1474 | PASS |
| Gujarati (gu) | Gujarati | 145 | PASS |
| Bengali (bn) | Bengali | 586 | PASS |
| Kannada (kn) | Kannada | 424 | PASS |
| Malayalam (ml) | Malayalam | 290 | PASS |
| Tamil (ta) | Tamil | 282 | PASS |
| Telugu (te) | Telugu | 440 | PASS |
| Odia (or) | Odia | 270 | PASS |
| English (en) | Latin | 316 | PASS |

Total vocab: 5000 pieces (official SentencePiece `piece_size=5000`, `unk_id=0`, verified).

## 3. Why the alias failure mode is now structurally impossible

The model is a SINGLE language-unconditioned network. Its Kannada pieces can only be emitted
when the acoustic content is Kannada — there is no code path that substitutes another
language's output. The historical Vosk-era `CANONICAL_ALIASES` map (kn/ml/or → Hindi model,
returning Hindi text) was deleted in the Vosk removal commit (P4 prerequisite); `SttLanguage`
documents that a selected language is the language the model serves.

## 4. Packaging note

One shared artifact set (`assets/sravaani/`) serves all 10 languages. The legacy per-language
`assetDir` fields on `SttLanguage` are deprecated Vosk-era diagnostics keys — they do NOT map
to per-language models anymore and exist only so old extracted-dir listings don't break.
