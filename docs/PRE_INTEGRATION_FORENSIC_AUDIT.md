# Pre-Integration Forensic Audit

## 1. Android Application Architecture
The application is built using a modern Android architecture:
- **UI:** Jetpack Compose (implied by typical modern setups and view models like `ChatViewModel`).
- **State Management:** Kotlin Coroutines & Flows (`StateFlow`, `SharedFlow`).
- **Dependency Injection:** Koin (observed in Gradle and singleton notes).
- **Local Storage:** Room Database (`MessageRepository`, `GroupRepository`, etc.).
- **Networking/Mesh:** A custom decentralized communication layer (`MeshManager`, `InboundPipeline`, `MeshRouter`, `MeshTransport`) handling both mesh and Internet (Spool) planes. E2E Encryption is heavily utilized using Signal-like ratcheting (`RatchetSessions`).

## 2. Main Application Entry Point
The application likely starts via a custom `Application` class initializing Koin, followed by a primary Activity launching the Compose navigation graph. The core lifecycle is deeply tied to `MeshManager.start()`, which binds the mesh transports and starts observing background data flows.

## 3. Current UI/Navigation Structure
Core navigation centers around:
- **Chat:** `ChatViewModel` orchestrates the chat interface, managing messages, attachments, and voice recordings.
- **Voice/Communication:** Dedicated UI components for Walkie-Talkie/PTT behavior, tied to `VoiceConversationController`.
- **Diagnostics/Status:** A connection header tracks mesh neighbors and transport health.

## 4. Current STT Implementation
- **Pipeline:** `SttPipeline` orchestrates STT. It captures PCM audio, uses a `VoiceActivityDetector` (VAD) to chunk speech, and feeds it to an `SttEngine`.
- **Engines:** Currently relies on **Vosk** and **Sherpa-ONNX** (Diagnostic modes mention VOSK and SHERPA).
- **Languages:** Managed by `SttLanguage`, mapping 10 languages to specific model directories (`stt-hi`, `stt-gu`, etc.). All models expect a 16kHz sample rate.

## 5. Current TTS Implementation
- **Pipeline:** `TtsManager` queues and plays audio. `VoiceMessageReceiver` automatically triggers TTS for inbound voice messages if auto-play is enabled.
- **Engine:** Backed by Android's native `TextToSpeech` API (`AndroidTtsEngine`), using `TtsLanguage` to resolve Locales.

## 6. Current Translation Implementation
- **Engine:** `TranslatorEngine` powered by **Google ML Kit** (`com.google.mlkit.nl.translate`).
- **Entity Shielding:** Heavy regex and conversational pattern matching used to mask names, numbers, and locations (`__N0__`) before translation, then unmasking afterward.
- **Routing:** Implements a pivot translation strategy (Indic → English → Indic) when direct Indic-Indic models are unavailable in ML Kit.

## 7. Language Coverage vs SIH Requirements
**Required:** Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English.
**Current State (ML Kit):**
- English, Hindi, Gujarati, Marathi, Kannada, Tamil, Telugu, Bengali are natively supported.
- **CRITICAL FLAW:** Malayalam (`ml`) and Odia (`or`) are completely unsupported by Google ML Kit's on-device translation. They are currently *hardcoded to alias to Hindi* in `TranslatorEngine.normalizeToLanguageTag`.

## 8. The "Mesh" Translation Pipeline
1. **Sender STT:** `SttPipeline` transcribes voice to text.
2. **Sender Translate (Optional):** `ChatViewModel.send()` attempts translation *before* sending if the recipient's preferred language is known. If translating, it sends `sourceLanguage`, `targetLanguage`, and `translatedText` on the wire.
3. **Transmission:** `MeshManager` encrypts and floods the message.
4. **Receiver Translate:** `VoiceMessageReceiver` intercepts the inbound message. If the message isn't in the receiver's preferred language, it translates it locally using `TranslatorEngine`.
5. **Receiver TTS:** The final text is passed to `TtsManager` for playback.

## 9. Memory Constraints Analysis
- Integrating a 628.7 MB FP16 model (IndicTrans2 320M) will heavily impact memory.
- Android apps typically have heap limits between 256MB and 512MB unless `largeHeap="true"` is set. However, native allocations (like ONNX Runtime/C++) bypass the Dalvik/ART heap limit and are restricted only by physical RAM and the OS Low Memory Killer (LMK).
- A 628MB model will consume ~650MB of RAM just for weights, plus ~100-200MB for KV cache and activations during Seq2Seq generation. This pushes total RAM usage near 1GB, making it highly susceptible to termination on devices with <4GB RAM.

## 10. TFLite vs ONNX Android Capabilities
- **TFLite:** Struggles with complex control flow (Seq2Seq, Beam Search) natively. Often requires breaking the model into encoder/decoder pieces and orchestrating the generation loop in Kotlin/C++, which is slow and error-prone.
- **ONNX Runtime (ORT):** Excellent support for Seq2Seq pipelines. Supports `generate()` loops in C++ natively via ORT Extensions or specific GenAI libraries. W8A16 (int8 weight, fp16 activation) or pure FP16 quantization is well supported, particularly with the XNNPACK execution provider.

## 11. Build System Analysis
- **Gradle:** Uses KTS (`build.gradle.kts`).
- **Dependencies:** Uses LiteRT (TFLite) currently for other tasks, Vosk for STT, ML Kit for translation, Koin, Room.
- **Conflict Risk:** Introducing `onnxruntime-android` or `onnxruntime-genai` could increase APK size substantially (~15-30MB for the AARs). No direct dependency conflicts identified, but ABI splits (arm64-v8a vs armeabi-v7a) will be crucial for managing APK size.

## 12. Risk Assessment Matrix
| Risk | Severity | Mitigation |
| :--- | :--- | :--- |
| **OOM Crashes (Low RAM)** | Critical | Ensure model memory maps (mmap) correctly via ONNX Runtime to avoid loading all weights into active RAM simultaneously. |
| **English Translation Missing** | High | IndicTrans2 `indic-indic` does not support English. We must retain ML Kit for English pairs and route Indic-Indic pairs to IndicTrans2. |
| **Generation Latency** | High | A 320M parameter model running autoregressive decoding on a mobile CPU may take >5 seconds per sentence. Use Greedy Search, XNNPACK, and thread tuning. |
| **APK Size Bloat** | Medium | The 628MB model cannot be bundled in the APK. It must be downloaded on-demand or loaded from an external directory. |

## 13. Final GO/NO-GO Recommendation
**GO, WITH CONDITIONS.**
The project is structurally sound to support a new engine. The `TranslatorEngine` facade makes swapping the backend straightforward. However, the 628MB FP16 model is dangerously large for seamless mobile use.
**Condition:** We must implement a "Hybrid Routing Strategy". ML Kit continues to handle English translations (and simple Indic pairs where speed is critical), while IndicTrans2 handles complex Indic-Indic pairs (especially Odia and Malayalam, which ML Kit lacks). We must also use ONNX Runtime, not TFLite, to handle the Seq2Seq loop safely.
