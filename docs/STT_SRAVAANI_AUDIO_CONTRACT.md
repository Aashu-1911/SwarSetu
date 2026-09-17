# SraVaani 0.5 — Audio Input Contract & VAD Validation (P5)

Status: P5 evidence. Every claim below is verified by code (`PcmCapture.kt`, `SttPipeline.kt`,
`VoiceActivityDetector.kt`) or by test (`VoiceActivityDetectorMatrixTest`,
`SttPipelineSentenceSegmentationTest`). On-device validation of the same parameters happens in
P10/P11 and is labeled accordingly.

## 1. SraVaani 0.5 input contract (verified)

| Property | Value | Evidence |
|---|---|---|
| Sample rate | 16,000 Hz | `PcmCapture.SAMPLE_RATE` == `SttLanguage.SAMPLE_RATE`; matches model (P2 graph validation) |
| Channels | mono | `AudioFormat.CHANNEL_IN_MONO` |
| PCM format | 16-bit signed, little-endian | `AudioFormat.ENCODING_PCM_16BIT` |
| Audio source | `VOICE_RECOGNITION` (platform AGC/NS disabled) | `PcmCapture.kt` |
| Feature extraction | 128-bin log-mel, in-Kotlin incremental featurizer | `SravaaniFeaturizer` (golden-vector tested vs official Python) |
| In-model normalization | per-window feature normalization is INSIDE the model (`preproc.pt` semantics, P2) | `docs/STT_SRAVAANI_GRAPH_VALIDATION.md` |

## 2. Preprocessing decisions (evidence-only adoption)

| Stage | Decision | Reason |
|---|---|---|
| Resampling | NOT REQUIRED | capture already runs at the model's native 16 kHz |
| Mono conversion | NOT REQUIRED | captured mono |
| Amplitude normalization | REJECTED (default) | model normalizes features internally; external normalization would double-process and can amplify noise-floor hiss |
| Noise suppression | REJECTED (default) | no evidence of gain yet; VOICE_RECOGNITION already disables platform NS; risk of speech distortion; re-evaluate only if P8 WER shows a noise-condition gap |
| AGC | REJECTED (default) | same evidence bar; loudness variation is handled by in-model normalization |
| Bandpass filter | REJECTED (default) | mel featurizer already band-limits; an extra filter adds latency with unproven benefit |
| Silence trimming | NOT APPLICABLE | streaming architecture: audio flows to the engine continuously; VAD only finalizes |
| Adaptive VAD thresholds | ADOPTED (P5) | static thresholds latch SPEECH on steady fan/traffic hum ≥400 RMS; noise-floor adaptation + steady-noise rejection fix this (tested) |

Any of the REJECTED stages may be revisited ONLY with P8 WER evidence showing a measurable gain.

## 3. VAD parameters (current, tested)

| Parameter | Value | Notes |
|---|---|---|
| Frame size | ~30 ms (480 samples) | `sampleRate / 33` |
| Static silence threshold | RMS 200 | quiet-room floor |
| Static speech threshold | RMS 400 | hysteresis upper bound |
| Hangover (silence frames) | 20 (~600 ms) | end-of-speech |
| Trigger (speech frames) | 3 (~90 ms) | onset debounce |
| Noise-floor EMA α | 0.05 | ~2 s time constant outside speech |
| Effective silence threshold | max(200, floor × 2) | floor = ambient EMA |
| Effective speech threshold | max(400, floor × 3) | never drops below static |
| Steady-noise window | 33 frames (~1 s) | while in SPEECH only |
| Steady-noise rule | spread < 8% of mean ⇒ noise | drops to SILENT, pins floor at hum level |

## 4. Matrix results (synthetic fixtures, JVM — deterministic)

| Condition | Result | Evidence |
|---|---|---|
| Short speech (<1 s) | PASS — triggers, releases after final 2 s pause | `short speech under 1s…` |
| Long speech (30 s) | PASS — no mid-speech dropout | `long 30s syllabic speech…` |
| Intra-sentence pauses 0.5/1.0/1.5 s | PASS — no audio loss; speech resumes | `intra-sentence pauses…` |
| Final pause | PASS — hangover holds 600 ms, releases by 2 s | `short speech…` |
| Two sentences / 2 s pause | PASS — re-triggers with and without reset | `two sentences…`, `new sentence…` |
| Brief noise bursts | PASS — 2-frame bursts never trigger | `brief noise bursts…` |
| Steady fan ≥ speech threshold | PASS — rejected, cannot re-trigger, threshold pinned above hum | `steady fan noise…` |
| Noisy room + real speech | PASS — speech at 6× hum still triggers | `noise floor adaptation…` |
| Speech does not pump floor | PASS — floor stays at quiet baseline through 5 s loud speech | `speech does not pump…` |
| Quiet speech −30 dBFS | PASS | `quiet speech at -30 dBFS…` |
| Loud speech −3 dBFS | PASS | `loud speech at -3 dBFS…` |
| Quiet-room silence 15 s | PASS — never triggers | `quiet room silence…` |

Speech→pause→speech through the REAL pipeline loop produces two finalized sentences with every
scripted sample delivered to the engine (`SttPipelineSentenceSegmentationTest`).

## 5. Known limits (honest, documented)

1. **Constant-RMS sustained phonation** (a perfectly flat vowel for ≥1 s) is theoretically
   classifiable as steady noise by a pure-energy detector. Worst case = early finalize; audio is
   never lost (the VAD is not an audio gate). Fixtures in tests model real syllabic phonation.
2. **Background speech / transients** are indistinguishable from speech by energy alone —
   accepted (the user is expected to speak; extra voices degrade ASR, not the VAD).
3. All threshold behavior is fixture-based until P11 records on-device values.
