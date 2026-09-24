# Inter-Language Voice Calling — Implementation & Feasibility Analysis

## 1. Executive Summary
This document analyzes the requirements, feasibility, and technical architecture for implementing a **100% offline, low-latency, inter-language voice calling feature** across 10 languages (Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English). The goal is to allow users speaking different languages to communicate seamlessly over a local mesh network without any internet dependency.

## 2. Core Requirements
- **Offline Execution**: All STT (Speech-to-Text), NMT (Neural Machine Translation), and TTS (Text-to-Speech) must happen on-device.
- **Low Latency**: The delay between the sender speaking and the receiver hearing the translated voice must be minimized to mimic real-time conversation.
- **10 Language Matrix**: Full support for all 90 translation directions across the specified 10 languages.

## 3. Feasibility & UX Paradigm
**Feasibility:** Fully feasible. The codebase has successfully integrated state-of-the-art on-device neural models for both STT and Translation.
**UX Paradigm:** True "full-duplex" continuous calling (like a standard phone call) is extremely challenging due to accumulated inference latencies and overlapping audio. 
**Recommendation:** A **"Walkie-Talkie" (Half-Duplex) or "Turn-based" UX** is highly recommended. The UI should display live subtitles as the audio is transcribed and translated, masking the processing delay.

## 4. Pipeline Architecture
The critical path for translated calling involves 5 distinct stages. To optimize latency and distribute processing load across devices, the architecture should follow this flow:

### Distributed Processing Flow (Caller -> Receiver)
1. **Caller (Node A) - Capture & STT:** 
   - `AudioCapture` listens to the microphone.
   - `SravaaniEngine` (STT) runs in streaming mode (`startLiveStream`) locally to generate the **Source Text** from audio chunks.
2. **Mesh Transport (Node A -> Node B):** 
   - Node A transmits a lightweight payload: `[Source Text, Source Language]` over the Wi-Fi Aware mesh. 
   - *Advantage: Sending text instead of audio takes mere bytes, requiring near-zero network bandwidth and zero network latency.*
3. **Receiver (Node B) - Translate & TTS:**
   - Node B receives the text payload.
   - `TranslatorEngine` (backed by `IndicTrans2Engine`) translates the text into Node B's preferred language natively using ONNX.
   - `TtsEngine` synthesizes the translated text into speech and plays it.
   - UI displays both the original text and the translated text as subtitles.

## 5. Model Selection & Engine Integration
Based on the latest integration, the codebase utilizes highly optimized offline neural models:

- **STT Engine (`SravaaniEngine.kt`):** Uses the **SraVaani 0.5-live** model (pinned to `29a15303`). It is a single multilingual, language-unconditioned model covering all 10 target languages. It uses an INT8 optimized ONNX graph (~660 MB total) for cache-aware streaming transcription.
- **Translation Engine (`TranslatorEngine.kt` & `IndicTrans2Engine.kt`):** Uses the **IndicTrans2** ONNX INT8 models natively. It supports entity preservation and successfully handles all 10 languages (including previously problematic languages like Odia and Malayalam) without needing Google ML Kit.
- **TTS Engine:** Uses Android Native TTS via `TtsManager`.

## 6. Latency Analysis (The "Low Latency" Challenge)
Achieving "low latency" requires understanding the bottleneck at each pipeline stage.

| Stage | Estimated Latency | Description |
|-------|-------------------|-------------|
| STT Inference (SraVaani) | ~100-200ms | Sravaani processing audio chunks incrementally with its streaming RNN-T greedy decoding loop. |
| Network Transport | ~20-50ms | Wi-Fi Aware text transmission (very fast). |
| Translation (IndicTrans2) | ~200-500ms | IndicTrans2 ONNX autoregressive generation loop. |
| TTS Generation | ~100-200ms | Generating PCM audio from translated text. |
| **Total Glass-to-Glass** | **~0.4s to 1.0s** | Delay from speaker pausing to receiver hearing. |

### Optimizations for Low Latency:
1. **Streaming STT Subtitles:** Utilize `SravaaniEngine.startLiveStream()` which yields cumulative partials (`SttResultType.PARTIAL`). Send these over the mesh continuously so the receiver sees "live typing" before the final translation arrives.
2. **RAM Pinning:** Keep both Sravaani and IndicTrans2 ONNX sessions active in memory for the duration of the call to avoid cold-start model load times.

## 7. Resource & Hardware Management
- **RAM Constraints:** This is the biggest bottleneck. The STT engine requires ~660 MB mapping, and the IndicTrans2 ONNX translation sessions will take another ~600-800 MB. The total memory pressure is heavily skewed towards high-end devices. 
  - *Risk:* Android may kill background services or even the app on low-end devices due to OOM. 
  - *Mitigation:* Bind the calling session to a Foreground Service with a persistent notification. Explicitly release models the moment the call ends (`SravaaniEngine.release()`, `IndicTrans2Engine.releaseAll()`).
- **Battery / Thermal Throttling:** Continuous CPU execution of large ONNX graphs will rapidly heat up the device. A half-duplex (push-to-talk) flow prevents constant 100% CPU usage.

## 8. Implementation Steps (How to Build It)
1. **Mesh Protocol Layer:** 
   - Define a `CallPacket` data class.
   - Create a dedicated mesh communication channel in `MeshManager` for call packets to bypass standard chat queueing delays.
2. **Session Manager (`TranslationCallSession.kt`):** 
   - Create an orchestrator that holds references to `SravaaniEngine`, `TranslatorEngine`, and `MeshController`.
   - Manage the state machine: `IDLE -> LISTENING -> PROCESSING -> SENDING`.
3. **UI Layer (`CallScreen.kt`):** 
   - Implement a Walkie-Talkie style UI.
   - Build a scrolling subtitle view (similar to Google Translate's conversation mode).
4. **Lifecycle & Memory:**
   - Pre-warm models (`sttEngine.warmUp()`, load IndicTrans2 bundles) when the call is accepted.
   - Force release all models in `onDestroy()` to prevent memory leaks.
