# FP16 IndicTrans2 Integration Analysis

## 1. Current Project Architecture
- **Framework & UI:** Android application built with Kotlin, Jetpack Compose, and Koin for Dependency Injection.
- **Entry Point & Structure:** `app.swarsetu.SwarSetuApplication`. Features are modularized by package (e.g., `app.swarsetu.translation`, `app.swarsetu.stt`, `app.swarsetu.tts`).
- **Data/Persistence:** Room Database (`MeshDatabase`), DataStore for preferences, SQLCipher for encryption.
- **Offline First:** Highly focused on offline mesh capabilities (Wi-Fi Aware).

## 2. Current Translation Flow
- Managed by `TranslatorEngine.kt`.
- It implements a sophisticated entity shielding mechanism (`maskEntities` and `unmaskEntities`) using regex to protect proper nouns, numbers, and critical entities from being corrupted during translation.
- **Pivot Translation:** For Indic-to-Indic translations, it explicitly pivots through English (e.g., Hindi → English → Marathi) if direct models are deemed insufficient or if it simplifies routing. 

## 3. Current STT/TTS Flow
- **STT (Speech-to-Text):** Uses Vosk (Kaldi-based offline NMT engine via `vosk-android`). The pipeline captures PCM audio, runs VAD (Voice Activity Detection), and feeds it to Vosk.
- **TTS (Text-to-Speech):** Uses the native Android `TextToSpeech` API (`AndroidTtsEngine.kt`), managed by `TtsManager` with audio focus control and queue scheduling.

## 4. Current Translation Implementation
- **Provider:** Google ML Kit (`com.google.mlkit:translate:17.0.2`).
- **Lifecycle:** Downloads translation models dynamically (`RemoteModelManager.getInstance().download(...)`).
- **Limitations:** Odia and Malayalam are explicitly mapped to Hindi in `TranslatorEngine.kt` (`"ml" -> TranslateLanguage.HINDI`, `"or" -> TranslateLanguage.HINDI`) due to ML Kit's lack of support or limited on-device support for these languages.

## 5. Existing Dependencies
- **LiteRT (TensorFlow Lite):** Already present (`implementation(libs.litert)`) and used for ML moderation (`nsfw.tflite`, `toxicity.tflite`).
- **Tokenizer:** The app already includes a pure Kotlin `SentencePieceTokenizer` (`app.swarsetu.moderation.SentencePieceTokenizer`), which is a huge advantage since IndicTrans2 uses SentencePiece.

## 6. IndicTrans2 Integration Requirements
- **Model:** `ai4bharat/indictrans2-indic-indic-dist-320M` (Transformer Encoder-Decoder).
- **Format:** FP16 (Half precision).
- **Tokenizer:** Requires SentencePiece (BPE) tokenization with specific IndicTrans2 vocab.
- **Formatting:** Needs strict formatting with source and target language tags (e.g., `__src_hin__`, `__tgt_mar__`).
- **Generation:** Requires an autoregressive decoding loop (Greedy or Beam Search) to generate tokens one by one from the decoder while caching cross-attention from the encoder.

## 7. Android Runtime Comparison
- **TensorFlow Lite (LiteRT):** 
  - *Pros:* Already in the app.
  - *Cons:* Seq2Seq models with dynamic lengths and caching (KV cache) are notoriously difficult to export and run efficiently in pure TFLite without custom C++ ops. 
- **PyTorch Mobile / ExecuTorch:** 
  - *Pros:* Native environment for the original PyTorch model.
  - *Cons:* ExecuTorch export (EXIR) for full encoder-decoder generation loops is still evolving and complex.
- **ONNX Runtime (ORT):** 
  - *Pros:* Excellent Hugging Face `optimum` support. Can export the encoder and decoder (with past key values) separately. Prebuilt AAR is lightweight.
  - *Cons:* Will require writing the generation loop (greedy search) in Kotlin, passing the KV cache tensors back and forth, which can have JNI overhead.
- **CTranslate2:** 
  - *Pros:* Highly optimized C++ engine specifically built for Transformer translation (Marian, Fairseq). Incredible RAM efficiency, native FP16 execution, and built-in beam search.
  - *Cons:* Requires compiling an NDK/JNI wrapper and shipping custom `.so` files.

## 8. Recommended Runtime
**ONNX Runtime (ORT)** is the most technically realistic and maintainable for Android without custom C++ compilation. We can export IndicTrans2 using Hugging Face's `optimum` into two ONNX files (`encoder.onnx` and `decoder.onnx`). The generation loop will be implemented in Kotlin. 
*(Alternative: If generation overhead is too high, compiling **CTranslate2** is the fallback).*

## 9. Model Integration Architecture
1. **Conversion:** Export the 320M model to ONNX FP16 (Encoder and Decoder with KV caching).
2. **Tokenization:** Adapt the existing `SentencePieceTokenizer.kt` to load the IndicTrans2 SPM model.
3. **Engine Implementation:** Create `IndicTrans2Engine` implementing a standard interface alongside ML Kit. 
4. **Execution Loop:**
   - Tokenize text + add language tags.
   - Run Encoder ONNX.
   - Loop Decoder ONNX (passing encoder hidden states and accumulating KV cache) until `<eos>`.
   - Detokenize.

## 10. Language Architecture
**CRITICAL LIMITATION:** The model requested is `indictrans2-indic-indic-dist-320M`. 
- **Indic → Indic:** This model will perfectly handle the 9 Indic languages (Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali).
- **Indic ↔ English:** The `indic-indic` model **DOES NOT** translate to or from English natively. AI4Bharat provides separate `en-indic` and `indic-en` models for English support.
- **Architecture Solution:** 
  1. Use the `indic-indic` FP16 model for all Indic-to-Indic translations (solves the ML Kit Malayalam/Odia issue).
  2. Continue using **Google ML Kit** for Indic ↔ English translations (to avoid adding another 1.2 GB of models for `en-indic` and `indic-en`).

## 11. Offline Architecture
- The model is **~628 MB**, which exceeds the Google Play App Bundle (AAB) initial install limit (150 MB).
- **Strategy:** 
  1. **Play Asset Delivery (PAD):** Package the model in an `install-time` or `on-demand` asset pack (supports up to 1GB).
  2. **In-App Download:** Download the 628 MB `.onnx` files from a reliable CDN/server on the first launch (similar to ML Kit's current flow) and store them in `Context.filesDir`. 

## 12. Storage and RAM Considerations
- **Storage:** ~650 MB of disk space.
- **RAM:** FP16 mmap execution will consume ~650-800 MB of RAM. 
- **Lifecycle:** The model MUST NOT be kept in RAM permanently. It should be loaded lazily on the first translation request and unloaded/closed aggressively (e.g., using `onTrimMemory` callbacks or coroutine timeouts) to prevent Android from killing the background service.

## 13. Files That Need Modification
- `app/build.gradle.kts`: Add ONNX runtime dependencies (e.g., `com.microsoft.onnxruntime:onnxruntime-android`).
- `app/src/main/java/app/swarsetu/translation/TranslatorEngine.kt`: Refactor into an interface `TranslationProvider`, make ML Kit one implementation, and inject the logic.
- `app/src/main/java/app/swarsetu/di/VoiceModule.kt` (or similar): Bind the new translation engines.

## 14. New Files/Classes Required
- `IndicTrans2Engine.kt`: Orchestrates ONNX inference and the generation loop.
- `OnnxSeq2Seq.kt`: Wrapper for ORT environment, sessions (Encoder and Decoder), and KV cache management.
- `IndicTrans2Tokenizer.kt`: Wrapper around the existing `SentencePieceTokenizer` tailored for the `indictrans2` vocabulary and special tokens.

## 15. Potential Blockers
1. **JNI Overhead:** Passing large KV cache tensors across the JNI boundary per token in Kotlin can be slow. If latency > 100ms per token, we will need to pivot to `onnxruntime-extensions` (which handles the generation loop in C++) or CTranslate2.
2. **OOM Crashes:** Older devices with < 4GB RAM might struggle to allocate 650MB for the model while keeping the OS and Compose UI alive.
3. **Vocabulary Size:** The tokenizer for IndicTrans2 is huge. Loading the `tokenizer.model` might take time.

## 16. Step-by-Step Implementation Plan
1. **Model Preparation (Offline):** Use `optimum-cli` to export `ai4bharat/indictrans2-indic-indic-dist-320M` to ONNX FP16. Verify output (Encoder, Decoder).
2. **Dependency Update:** Add ONNX Runtime AAR to `build.gradle.kts`.
3. **Tokenizer Adaptation:** Expand the Kotlin SentencePiece parser to support IndicTrans2's model file and special tokens (`__src_hin__`, `__tgt_mar__`).
4. **Engine Abstraction:** Refactor `TranslatorEngine.kt` to act as a router (ML Kit for English, IndicTrans2 for Indic).
5. **Inference Loop:** Write `IndicTrans2Engine.kt` and implement greedy decoding.
6. **Memory Management:** Implement lazy loading and `close()` mechanisms.
7. **Testing:** Unit test with static inputs, profile memory usage.
