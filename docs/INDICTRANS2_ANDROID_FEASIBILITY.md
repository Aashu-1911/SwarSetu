# IndicTrans2 Android Feasibility Report

## Model Specifications
- **Model:** `ai4bharat/indictrans2-indic-indic-dist-320M`
- **Architecture:** Transformer (Seq2Seq, Encoder-Decoder)
- **Parameters:** ~320 Million
- **Quantization:** FP16
- **Disk Size:** ~628.7 MB
- **Required Task:** Offline generation (Autoregressive decoding)

## Technical Feasibility on Android

### 1. Memory (RAM) Capabilities
- **Status:** **HIGH RISK**
- **Analysis:** Android OS tightly controls application memory. A 628MB FP16 model will require ~650MB of RAM just for weights. During inference, KV cache and activations for a sequence length of 128-256 tokens will consume an additional 100MB-200MB. 
- **Constraint:** Total RAM usage will peak around 800MB-1GB. On 8GB+ RAM devices, this is viable. On 4GB RAM devices, the Android Low Memory Killer Daemon (LMKD) has a high probability of terminating the app during translation.
- **Mitigation:** We MUST use memory mapping (`mmap`). ONNX Runtime automatically mmaps weights if configured correctly, preventing the OS from counting the entire 628MB against the app's Dalvik/ART heap.

### 2. Execution Engine (ONNX vs TFLite)
- **Status:** **ONNX IS MANDATORY**
- **Analysis:** TFLite is designed for static graph execution (like image classification or STT acoustic models). Transformer Seq2Seq generation requires a dynamic loop (Encode -> Decode token 1 -> Decode token 2 -> ...). Implementing beam search or even greedy search in pure C++/JNI over TFLite is highly complex and error-prone.
- **Solution:** Use `onnxruntime-genai` or standard `onnxruntime-android`. ORT has native support for generation loops and optimized Transformer kernels.

### 3. Tokenizer Integration
- **Status:** **EXCELLENT**
- **Analysis:** The repository already contains a pure Kotlin `SentencePieceTokenizer` (`app/src/main/java/app/swarsetu/moderation/SentencePieceTokenizer.kt`). It is currently configured for a Unigram model.
- **Action:** Verify if IndicTrans2 uses BPE or Unigram. If it uses BPE (typical for IndicTrans2/Flores), the existing `SentencePieceTokenizer` will need to be adapted or expanded to support BPE merges, or we must compile the C++ SentencePiece library via JNI.

### 4. Storage & Asset Management
- **Status:** **REQUIRES EXTERNAL STORAGE**
- **Analysis:** A 628MB model cannot be shipped inside the APK (`assets/`). The Google Play Store restricts APKs to 150MB, and Play Asset Delivery is complex. 
- **Action:** The model must be downloaded post-install or placed in `getExternalFilesDir()` by the user/tester.

### 5. Latency & User Experience
- **Status:** **DEGRADED (EXPECTED)**
- **Analysis:** Mobile CPUs executing 320M parameters per token without an NPU will be slow. Assuming 20-30 ms per token on a modern Snapdragon CPU, a 20-word sentence (~30 tokens) will take 600-900ms to translate. On older CPUs, this could exceed 3-5 seconds.
- **Action:** Run the engine asynchronously. Inform the user via UI loading states. Use Greedy Search instead of Beam Search to minimize latency.

## Conclusion
Integrating a 628MB FP16 IndicTrans2 model into this Android application is **technically feasible**, provided:
1. ONNX Runtime is used for execution.
2. The model is sideloaded/downloaded post-install, not bundled in the APK.
3. Memory is strictly managed via mmap to prevent OOM crashes on low-end devices.
4. The English translation requirement is fulfilled by retaining ML Kit (Hybrid approach), as `indic-indic` lacks English support.
