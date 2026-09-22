package app.swarsetu.stt.sravaani

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.swarsetu.stt.SttConfig
import app.swarsetu.stt.SttEngine
import app.swarsetu.stt.SttException
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttLiveStream
import app.swarsetu.stt.SttModelInfo
import app.swarsetu.stt.SttModelManager
import app.swarsetu.stt.SttResult
import app.swarsetu.stt.SttResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SraVaani 0.5 [SttEngine] — the ONLY permitted STT model for SIH 26173
 * ([ARTPARK-IISc/SraVaani-0.5-live](https://huggingface.co/ARTPARK-IISc/SraVaani-0.5-live),
 * pinned revision `29a15303`, MIT).
 *
 * **Architecture (verified in P2, evidence in `docs/STT_SRAVAANI_GRAPH_VALIDATION.md`):**
 * the official artifact ships a cache-aware streaming FastConformer encoder + CTC head.
 * This engine executes the P3-exported ONNX graphs of exactly those two components,
 * with the PRODUCTION encoder being the P9 weight-only INT8 optimization of the fp32 export
 * (`sravaani_encoder.onnx` 638.7 MB int8 + `sravaani_ctc.onnx` 20.5 MB — provenance chain:
 * official fp16 artifact → P3 fp32 export (equivalence gate: feat rel 2.6e-05, encoder rel
 * 4.7e-05, collapsed token streams identical on structured trials) → P9 int8 (collapsed token
 * streams identical on all probes; FLEURS real-speech WER parity +0.05 pp, 2026-09-17))
 * via ONNX Runtime, and reimplements the thin streaming brain in Kotlin
 * ([SravaaniFeaturizer], [SravaaniStreamingState], [SravaaniCtcDecoder], [SravaaniTokenizer]) —
 * each class is an exact port of the official Python, pinned by golden-vector tests.
 *
 * **Graph I/O contract (from `scripts/sravaani/export_onnx.py`, the source of truth):**
 * - encoder: `feats(B,128,T)` mel-major | `feat_len(1)` i64 | `cache_last_channel(1,17,70,1024)`
 *   | `cache_last_time(1,17,1024,8)` | `cache_last_channel_len(1)` i64
 *   → `enc(1,1024,T')` | `enc_len(1)` i64 | `cache_next_*` (same shapes as inputs).
 * - ctc: `enc(1,1024,T')` → `log_probs(1,T',5001)` — TIME-major, vocab on axis 2 (real-speech
 *   verified 2026-09-17 vs the official TorchScript wrapper: identical transcript). NOTE: the
 *   official NeMo CTC export is vocab-major `(1,V,T)`; OUR export flattens the TimeMask/LogSoftmax
 *   tail to `(1,T,V)` — do not "fix" this back to the official layout.
 * - The graph's `cache_next_*` outputs stay alive only while its [OrtSession.Result] is open,
 *   so they are COPIED into per-stream persistent tensors inside the result scope.
 *
 * **True streaming:** per-chunk encoder caches are carried across chunks (the graph's
 * `cache_next_*` outputs become the next `cache_last_*` inputs) and the CTC `prev_id` collapse
 * state spans chunk seams — the exact mechanism the official docstring identifies as what
 * naive chunked ASR drops. Unlike the official live path, featurization is O(n) incremental
 * (the official one recomputes features over the whole buffer per push).
 *
 * **Live pipeline (P4):** [startLiveStream] exposes that cache-carrying state directly to the
 * capture loop: mic chunks are pushed into ONE session whose featurizer ring and encoder caches
 * persist across pushes, so each audio sample is transcribed exactly once (total inference work
 * is O(n) over the utterance) and partials are cumulative. The batch [transcribe]/
 * [transcribeStream] paths share the same machinery for whole-buffer inputs.
 *
 * **Languages:** one multilingual, language-UNCONDITIONED model covering all 10 required
 * languages (verified from the model card tags + official demo — the model takes NO language
 * input and emits the spoken language's script). There is deliberately no per-language model,
 * no aliasing, and no auto-detection claim: [SttConfig.autoDetectLanguage] is a documented
 * no-op (the engine never conditions on language; transcripts come out in whatever language
 * was spoken).
 *
 * **Memory reality (measured, P2/P9):** the production int8 encoder is ~638.7 MB on disk
 * (fp32 reference is 1.77 GB, kept in scratch); mmap'd by ORT
 * from app-private storage, mapped pages are file-backed and pageable, but this model class
 * will not run on 2 GB-RAM devices. Recorded as a known limitation, not hidden.
 *
 * **Threading:** all native session calls serialize behind [mutex] (project convention).
 * Sessions are created lazily on first transcription — [initialize] only verifies packaging,
 * so a cold app init never pays the ~1.8 GB map unless STT is actually used (P10 hardens
 * this lifecycle).
 */
class SravaaniEngine(
    private val context: Context,
    private val modelManager: SttModelManager,
) : SttEngine {

    private val mutex = Mutex()
    private var released = false
    private var _config: SttConfig? = null
    private var _currentLanguage: SttLanguage? = null

    /** Lazily-built native internals; null until first transcription. */
    private var internals: Internals? = null

    override val config: SttConfig? get() = _config
    override val isReady: Boolean get() = _config != null && !released
    override val currentLanguage: SttLanguage? get() = _currentLanguage

    // ------------------------------------------------------------------ lifecycle

    override suspend fun initialize(config: SttConfig) =
        mutex.withLock {
            Log.d("SW_SRAVAANI", "STT_ENGINE_INITIALIZE_START (lang=${config.language.code})")
            try {
                check(!released) { throw SttException.EngineNotReady("engine was released") }
                // Verify packaging BEFORE any state mutation — a failed initialize must not
                // leave the engine claiming readiness (P10: no half-initialized states).
                requireGraphsPresent()
                _config = config
                _currentLanguage = config.language
                modelManager.markLoaded(
                    SttModelInfo(
                        language = config.language,
                        modelPath = ASSET_DIR,
                        sizeBytes = GRAPH_ENCODER_BYTES + GRAPH_CTC_BYTES,
                        description = "SraVaani 0.5-live (multilingual, 29a15303, MIT)",
                    ),
                )
                Log.i("SW_SRAVAANI", "STT_ENGINE_INITIALIZE_SUCCESS (SraVaani 0.5 initialized)")
                Unit
            } catch (e: Exception) {
                Log.e("SW_SRAVAANI", "STT_ENGINE_INITIALIZE_FAILED: ${e.message}", e)
                throw e
            }
        }

    override suspend fun setLanguage(language: SttLanguage) {
        require(language in SttLanguage.entries) {
            throw SttException.UnsupportedLanguage(language)
        }
        // Single multilingual unconditioned model: every declared language is served by the
        // same graph. Config update only — no model swap, no aliasing.
        mutex.withLock {
            check(!released) { throw SttException.EngineNotReady("engine was released") }
            _currentLanguage = language
            _config = _config?.copy(language = language)
        }
    }

    override suspend fun release() {
        mutex.withLock {
            released = true
            runCatching { internals?.close() }
            internals = null
            _currentLanguage?.let { modelManager.markReleased(it) }
            _config = null
            Log.i(TAG, "SraVaani engine released")
        }
        // OUTSIDE the lock: drain any queued abandoned-stream closes (they need the mutex;
        // shutting down while holding it would deadlock the closer thread).
        streamCloser.shutdown()
        streamCloser.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * Eagerly build the ORT sessions so they are ready before the first PTT press.
     *
     * On first call (after [initialize]): calls [extractGraphs] (SHA-256 verify + possible
     * 680 MB copy from assets) then creates the ORT encoder + CTC sessions. All of this is
     * on [Dispatchers.Default], inside the [mutex], so it serializes safely with any racing
     * batch or live-stream call.  Failures are logged and swallowed — the engine falls back to
     * lazy init on the first real [transcribe]/[pushPcm] (same as before this method existed).
     *
     * On subsequent calls: the [internals] reference is already populated; this is O(1).
     */
    override suspend fun warmUp() {
        if (released || _config == null) return // not initialized yet — nothing to warm
        runCatching {
            mutex.withLock {
                if (!released && _config != null) {
                    lazyInternals() // idempotent: returns the cached Internals after first call
                    Log.i(TAG, "warmUp: ORT sessions ready")
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "warmUp failed (will retry lazily on first use): ${e.javaClass.simpleName} — ${e.message}")
        }
    }

    /**
     * Close a live stream's native tensors without racing an in-flight chunk: take the engine
     * mutex if it is free; otherwise queue the close on a daemon thread that waits for the
     * current inference to finish. Never blocks the caller on inference (release() is sync).
     */
    private fun closeStreamEventually(s: StreamNative) {
        if (mutex.tryLock()) {
            try {
                s.close()
            } finally {
                mutex.unlock()
            }
        } else {
            streamCloser.execute {
                runBlocking { mutex.withLock { } }
                runCatching { s.close() }
            }
        }
    }

    /** Serializes abandoned-stream tensor closes against inference (see [closeStreamEventually]). */
    private val streamCloser = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sravaani-stream-closer").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ inference

    override suspend fun transcribe(
        pcm: ShortArray,
        language: SttLanguage,
    ): SttResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (released || _config == null) {
                    throw SttException.EngineNotReady("engine was released or never initialized")
                }
                if (pcm.isEmpty()) return@withLock SttResult.empty(language)
                validateDuration(pcm.size)
                val internal = lazyInternals()
                val stream = internal.newStream(language)
                try {
                    feedAndDrain(internal, stream, pcm, emitPartial = null)
                    val text = internal.tokenizer.decode(stream.state.tokens)
                    val audioMs = stream.state.samplesPushed * 1000L / SttLanguage.SAMPLE_RATE
                    if (text.isBlank()) SttResult.empty(language)
                    else {
                        SttResult(
                            text = text,
                            type = SttResultType.FINAL,
                            language = language,
                            durationMs = audioMs,
                        )
                    }
                } finally {
                    stream.close()
                }
            }
        }

    override fun transcribeStream(
        pcm: ShortArray,
        language: SttLanguage,
    ): Flow<SttResult> =
        flow {
            mutex.withLock {
                if (released || _config == null) {
                    throw SttException.EngineNotReady("engine was released or never initialized")
                }
                if (pcm.isEmpty()) {
                    emit(SttResult.empty(language))
                    return@flow
                }
                validateDuration(pcm.size)
                val internal = lazyInternals()
                val stream = internal.newStream(language)
                try {
                    // True streaming: emit a partial after every processed chunk whose text
                    // changed (official semantics: cumulative text), final after the drain.
                    var lastPartial: String? = null
                    feedAndDrain(internal, stream, pcm) { text ->
                        if (text != lastPartial && text.isNotBlank()) {
                            lastPartial = text
                            emit(
                                SttResult(
                                    text = text,
                                    type = SttResultType.PARTIAL,
                                    language = language,
                                ),
                            )
                        }
                    }
                    val text = internal.tokenizer.decode(stream.state.tokens)
                    val audioMs = stream.state.samplesPushed * 1000L / SttLanguage.SAMPLE_RATE
                    emit(
                        if (text.isBlank()) {
                            SttResult.empty(language)
                        } else {
                            SttResult(
                                text = text,
                                type = SttResultType.FINAL,
                                language = language,
                                durationMs = audioMs,
                            )
                        },
                    )
                } finally {
                    stream.close()
                }
            }
        }.flowOn(Dispatchers.Default)

    // ------------------------------------------------------------------ live streaming (P4)

    override fun startLiveStream(language: SttLanguage): SttLiveStream =
        SravaaniLiveStream(language)

    /**
     * One live SraVaani session: a single [StreamNative] whose featurizer ring, encoder caches,
     * and CTC collapse state persist across [pushPcm] calls. Audio is transcribed exactly once;
     * partials are the cumulative transcript after each processed chunk. [finish] flushes the
     * tail chunks (official finalize semantics) and returns the final result.
     *
     * NOT thread-safe by itself — the capture loop calls it serially — but pushes/finalizes
     * serialize against the engine's [mutex] (shared with batch inference).
     */
    private inner class SravaaniLiveStream(
        private val language: SttLanguage,
    ) : SttLiveStream {
        private var opened: StreamNative? = null
        private var finished = false

        override var onPartial: ((String) -> Unit)? = null

        /** Lazily open the native stream on first push (keeps a no-audio session free). */
        private fun stream(): StreamNative {
            opened?.let { return it }
            check(!released && _config != null) {
                throw SttException.EngineNotReady("engine was released or never initialized")
            }
            return internals?.newStream(language).also { opened = it }
                ?: throw SttException.EngineNotReady("engine internals not built")
        }

        override suspend fun pushPcm(samples: ShortArray) {
            check(!finished) { "live stream already finished" }
            mutex.withLock {
                if (samples.isEmpty()) return@withLock
                val internal = lazyInternals()
                val s = stream()
                var offset = 0
                while (offset < samples.size) {
                    val n = minOf(FEED_SAMPLES, samples.size - offset)
                    feedSamples(internal, s, samples, offset, n) { text ->
                        if (text.isNotBlank()) onPartial?.invoke(text)
                    }
                    offset += n
                }
            }
        }

        override suspend fun finish(): SttResult {
            check(!finished) { "live stream already finished" }
            finished = true
            return mutex.withLock {
                val s = opened ?: return@withLock SttResult.empty(language)
                try {
                    val internal = lazyInternals()
                    // Official finalize: flush trailing partial chunk(s), then stop.
                    s.state.beginFinalize()
                    while (s.state.hasChunkReady()) {
                        runChunk(internal, s)
                        onPartial?.invoke(internal.tokenizer.decode(s.state.tokens))
                    }
                    s.state.markFinalized()
                    val text = internal.tokenizer.decode(s.state.tokens)
                    val audioMs = s.state.samplesPushed * 1000L / SttLanguage.SAMPLE_RATE
                    if (text.isBlank()) SttResult.empty(language)
                    else {
                        SttResult(
                            text = text,
                            type = SttResultType.FINAL,
                            language = language,
                            durationMs = audioMs,
                        )
                    }
                } finally {
                    s.close()
                }
            }
        }

        override fun release() {
            // P10 hardening: an abandoned live session must free its native cache tensors
            // (~5.4 MB direct memory) immediately — not at next engine.release(). finish()
            // remains the normal path; release() covers the pipeline's cancel/abandon path.
            val s = opened ?: return
            opened = null
            finished = true // blocks any further pushPcm/finish on this stream
            closeStreamEventually(s)
        }
    }

    // ------------------------------------------------------------------ core loop

    /**
     * Push [count] PCM samples starting at [offset] and drain every chunk that becomes ready
     * (live mode — no finalize). [emitPartial] gets the cumulative transcript per chunk.
     */
    private suspend fun feedSamples(
        internal: Internals,
        stream: StreamNative,
        pcm: ShortArray,
        offset: Int,
        count: Int,
        emitPartial: (suspend (String) -> Unit)?,
    ) {
        stream.pushPcm(pcm, offset, count)
        drainChunks(internal, stream, emitPartial)
    }

    /**
     * Push all PCM, then drain chunks: live chunking while audio keeps coming, then finalize
     * semantics for the tail. [emitPartial] (nullable for batch calls) is invoked with the
     * cumulative transcript text after each processed chunk. Suspend lambda so the streaming
     * path can emit into its Flow from inside the drain loop.
     */
    private suspend fun feedAndDrain(
        internal: Internals,
        stream: StreamNative,
        pcm: ShortArray,
        emitPartial: (suspend (String) -> Unit)?,
    ) {
        // Feed in bounded pushes so the featurizer's ring always covers chunk read-back.
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(PUSH_SAMPLES, pcm.size - offset)
            stream.pushPcm(pcm, offset, n)
            offset += n
            // While enough frames exist for a full chunk, run chunks (official stream_audio).
            drainChunks(internal, stream, emitPartial)
        }
        // Official finalize: flush trailing partial chunk(s), then stop.
        stream.state.beginFinalize()
        while (stream.state.hasChunkReady()) {
            runChunk(internal, stream)
            emitPartial?.invoke(internal.tokenizer.decode(stream.state.tokens))
        }
        stream.state.markFinalized()
    }

    /** Run all currently-available full chunks (live mode). */
    private suspend fun drainChunks(
        internal: Internals,
        stream: StreamNative,
        emitPartial: (suspend (String) -> Unit)?,
    ) {
        while (stream.state.hasChunkReady()) {
            runChunk(internal, stream)
            emitPartial?.invoke(internal.tokenizer.decode(stream.state.tokens))
        }
    }

    /**
     * Assemble + execute ONE chunk: encoder → CTC head → per-frame argmax → cross-chunk
     * collapse. Cache outputs are copied into the stream's persistent tensors inside the
     * result scope (they die with the result).
     */
    private fun runChunk(internal: Internals, stream: StreamNative) {
        val state = stream.state
        val total = state.assembleChunk(internal.assembled)
        require(total > 0) { "runChunk called with no chunk ready" }
        val env = internal.env
        Log.d("SW_SRAVAANI", "RUN_CHUNK_START (featIdx=${state.featIdx}, frames=$total)")

        // Transpose the frame-major assembly (T x mel) into the mel-major graph layout
        // (mel x T), then hand ORT a fresh direct buffer (shape varies on the finalize tail,
        // and ORT reads direct buffers from their start address).
        run {
            val scratch = internal.scratch
            for (t in 0 until total) {
                val base = t * MEL_BINS
                for (m in 0 until MEL_BINS) {
                    scratch[m * total + t] = internal.assembled[base + m]
                }
            }
        }
        val featsBuf = internal.featsDirectBuf
        featsBuf.clear()
        featsBuf.put(internal.scratch, 0, total * MEL_BINS)
        featsBuf.flip()
        val featsTensor = OnnxTensor.createTensor(
            env,
            featsBuf,
            longArrayOf(1, MEL_BINS.toLong(), total.toLong()),
        )
        internal.featLenDirectBuf.put(0, total.toLong())
        internal.featLenDirectBuf.rewind()
        val featLenTensor = OnnxTensor.createTensor(
            env,
            internal.featLenDirectBuf,
            longArrayOf(1),
        )
        val results = try {
            internal.encoderSession.run(
                mapOf(
                    "feats" to featsTensor,
                    "feature_lengths" to featLenTensor,
                ),
            )
        } finally {
            featsTensor.close()
            featLenTensor.close()
        }

        results.use { r ->
            val encTensor = r.get(0) as OnnxTensor
            val encLenTensor = r.get(1) as OnnxTensor
            val encLen = encLenTensor.longBuffer.get(0).toInt()
            val encBuffer = encTensor.floatBuffer
            encBuffer.rewind()
            val encShape = encTensor.info.shape
            val encTime = if (encShape.size >= 3) encShape[2].toInt() else (encBuffer.remaining() / ENC_D_MODEL.toInt())

            val validFrames = minOf(encLen, encTime)
            Log.d("SW_SRAVAANI", "ENCODER_SUCCESS (encLen=$encLen, encTime=$encTime, validFrames=$validFrames)")

            var newTokensCount = 0
            // ---- TDT / RNN-T greedy decoding loop over acoustic frames t ----
            var t = 0
            while (t < validFrames) {
                // Slice acoustic frame t: shape [1, 1024, 1]
                for (d in 0 until ENC_D_MODEL.toInt()) {
                    stream.fBuf.put(d, encBuffer.get(d * encTime + t))
                }
                stream.fBuf.rewind()

                var symbolsInFrame = 0
                var advanceFrames = 1
                while (symbolsInFrame < MAX_SYMBOLS_PER_FRAME) {
                    stream.tgtBuf.put(0, minOf(stream.prevToken, BLANK_ID))
                    stream.tgtBuf.rewind()
                    stream.tlenBuf.put(0, 1)
                    stream.tlenBuf.rewind()
                    stream.hBuf.rewind()
                    stream.cBuf.rewind()

                    val fTensor = OnnxTensor.createTensor(env, stream.fBuf, longArrayOf(1, ENC_D_MODEL, 1))
                    val tgtTensor = OnnxTensor.createTensor(env, stream.tgtBuf, longArrayOf(1, 1))
                    val tlenTensor = OnnxTensor.createTensor(env, stream.tlenBuf, longArrayOf(1))
                    val hTensor = OnnxTensor.createTensor(env, stream.hBuf, longArrayOf(1, 1, RNNT_HIDDEN_DIM))
                    val cTensor = OnnxTensor.createTensor(env, stream.cBuf, longArrayOf(1, 1, RNNT_HIDDEN_DIM))

                    val stepResult = try {
                        internal.ctcSession.run(
                            mapOf(
                                "f" to fTensor,
                                "tgt" to tgtTensor,
                                "tlen" to tlenTensor,
                                "h_in" to hTensor,
                                "c_in" to cTensor,
                            ),
                        )
                    } finally {
                        fTensor.close()
                        tgtTensor.close()
                        tlenTensor.close()
                        hTensor.close()
                        cTensor.close()
                    }

                    advanceFrames = 1

                    stepResult.use { sr ->
                        val logitsTensor = sr.get(0) as OnnxTensor
                        val logitsBuf = logitsTensor.floatBuffer
                        logitsBuf.rewind()
                        val totalLogits = logitsBuf.remaining()

                        // 1. Vocabulary logits: 0 until minOf(totalLogits, VOCAB_WITH_BLANK = 5001)
                        var bestToken = BLANK_ID
                        var maxLogit = Float.NEGATIVE_INFINITY
                        var secondToken = BLANK_ID
                        var secondLogit = Float.NEGATIVE_INFINITY
                        val vocabLimit = minOf(totalLogits, VOCAB_WITH_BLANK)
                        val mask = stream.allowedMask
                        for (v in 0 until vocabLimit) {
                            if (mask != null && v < mask.size && !mask[v]) continue
                            val l = logitsBuf.get(v)
                            if (l > maxLogit) {
                                secondLogit = maxLogit
                                secondToken = bestToken
                                maxLogit = l
                                bestToken = v
                            } else if (l > secondLogit) {
                                secondLogit = l
                                secondToken = v
                            }
                        }

                        // 2. Duration logits: VOCAB_WITH_BLANK until totalLogits (indices 5001..5005 -> durations 0..4)
                        var bestDur = 0
                        var maxDurLogit = Float.NEGATIVE_INFINITY
                        for (d in VOCAB_WITH_BLANK until totalLogits) {
                            val l = logitsBuf.get(d)
                            if (l > maxDurLogit) {
                                maxDurLogit = l
                                bestDur = d - VOCAB_WITH_BLANK
                            }
                        }

                        if (t % 2 == 0 || bestToken < BLANK_ID) {
                            Log.d("SW_SRAVAANI", "FRAME $t (sym=$symbolsInFrame): vocabTop1=$bestToken ($maxLogit), top2=$secondToken ($secondLogit), dur=$bestDur ($maxDurLogit)")
                        }

                        if (bestToken >= BLANK_ID) {
                            // Blank token: advance to next acoustic frame
                            advanceFrames = if (bestDur > 0) bestDur else 1
                            symbolsInFrame = MAX_SYMBOLS_PER_FRAME // finish symbol loop for this frame
                        } else {
                            // Non-blank token: emit and advance decoder/predictor state
                            state.tokens.add(bestToken)
                            stream.prevToken = bestToken
                            newTokensCount++
                            Log.d("SW_SRAVAANI", "EMIT_TOKEN: $bestToken (logit=$maxLogit, frame=$t, dur=$bestDur)")

                            val nextH = (sr.get(2) as OnnxTensor).floatBuffer
                            val nextC = (sr.get(3) as OnnxTensor).floatBuffer
                            nextH.rewind()
                            nextC.rewind()
                            stream.hBuf.clear()
                            stream.hBuf.put(nextH)
                            stream.hBuf.rewind()
                            stream.cBuf.clear()
                            stream.cBuf.put(nextC)
                            stream.cBuf.rewind()

                            symbolsInFrame++
                            if (bestDur > 0) {
                                advanceFrames = bestDur
                                symbolsInFrame = MAX_SYMBOLS_PER_FRAME // finish symbol loop for this frame
                            } else {
                                advanceFrames = 0 // stay on same frame to emit next token
                            }
                        }
                    }

                    if (advanceFrames > 0) {
                        t += advanceFrames
                        break
                    }
                }
                if (symbolsInFrame == 0 || advanceFrames == 0) {
                    t++
                }
            }
            Log.d("SW_SRAVAANI", "RUN_CHUNK_DONE (newTokens=$newTokensCount, totalTokens=${state.tokens.size})")
        }
        state.advanceChunk()
    }

    // ------------------------------------------------------------------ internals

    private fun validateDuration(sampleCount: Int) {
        val max = _config?.maxAudioDurationMs ?: 0L
        if (max > 0 && sampleCount * 1000L / SttLanguage.SAMPLE_RATE > max) {
            throw SttException.AudioInputError(
                "pcm duration ${sampleCount * 1000L / SttLanguage.SAMPLE_RATE} ms exceeds maxAudioDurationMs $max",
            )
        }
    }

    private fun requireGraphsPresent() {
        Log.d("SW_SRAVAANI", "STT_MODEL_CHECK_START")
        val extractDir = File(context.filesDir, EXTRACT_DIR)
        for (name in listOf(ENCODER_ASSET, CTC_ASSET)) {
            // Accept the graph from either source: APK assets (fresh install) OR already
            // extracted to app-private storage (post-first-use, sideloaded, or downloaded).
            val inAssets = try {
                context.assets.open("$ASSET_DIR/$name").use { it.read() >= 0 }
            } catch (_: Exception) { false }
            val inFilesDir = File(extractDir, name).isFile
            if (!inAssets && !inFilesDir) {
                Log.e("SW_SRAVAANI", "STT_MODEL_CHECK_FAILED ($name missing)")
                throw SttException.ModelLoadError(
                    "$ASSET_DIR/$name not found in APK assets or app-private storage " +
                        "(${extractDir.absolutePath})",
                )
            }
        }
        Log.d("SW_SRAVAANI", "STT_MODEL_CHECK_SUCCESS")
    }

    /** Build (or reuse) the native sessions + tables. Heavy: first call maps ~1.8 GB. */
    private fun lazyInternals(): Internals {
        // Defensive gate: no inference path may build sessions on an un-initialized or
        // released engine (P10 — pushPcm previously reached here before initialize).
        if (released || _config == null) {
            throw SttException.EngineNotReady("engine was released or never initialized")
        }
        internals?.let { return it }
        Log.d("SW_SRAVAANI", "STT_ENGINE_CREATE_START")
        try {
            val dir = extractGraphs()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(INTRA_OP_THREADS)
            opts.setInterOpNumThreads(1)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val encoder =
                env.createSession(File(dir, ENCODER_ASSET).absolutePath, opts)
            val ctc = env.createSession(File(dir, CTC_ASSET).absolutePath, opts)
            opts.close()
            val tables = context.assets.open("$ASSET_DIR/$TABLES_ASSET").use { s ->
                SravaaniTables.read(s)
            }
            val pieces = context.assets.open("$ASSET_DIR/$TOKENS_ASSET").use { s ->
                s.readBytes().decodeToString().lineSequence().filter { it.isNotEmpty() }.toList()
            }
            require(pieces.size == SravaaniTokenizer.PIECE_COUNT) {
                "tokens.txt has ${pieces.size} pieces, expected ${SravaaniTokenizer.PIECE_COUNT}"
            }
            internals = Internals(
                env = env,
                encoderSession = encoder,
                ctcSession = ctc,
                tables = tables,
                tokenizer = SravaaniTokenizer(pieces),
            )
            Log.i("SW_SRAVAANI", "STT_ENGINE_CREATE_SUCCESS (ORT sessions created: encoder + ctc, tables + tokenizer loaded)")
            return internals!!
        } catch (e: SttException) {
            Log.e("SW_SRAVAANI", "STT_ENGINE_CREATE_FAILED: ${e.message}", e)
            throw e
        } catch (e: OutOfMemoryError) {
            Log.e("SW_SRAVAANI", "STT_ENGINE_CREATE_FAILED (OOM): ${e.message}", e)
            throw SttException.OutOfMemory(cause = e)
        } catch (e: Exception) {
            Log.e("SW_SRAVAANI", "STT_ENGINE_CREATE_FAILED: ${e.message}", e)
            throw SttException.ModelLoadError(ASSET_DIR, e)
        }
    }

    /**
     * Copy the ONNX graphs from assets to app-private storage (once) so ORT can mmap them —
     * assets cannot be mapped directly. Copy is version-guarded by a marker file; every
     * extraction is SHA-256-verified (P7) against the P3 export manifest pins before the
     * marker is written, so a corrupt/truncated asset fails LOUDLY instead of loading
     * garbage weights. Previously-extracted graphs are re-verified on every engine start
     * (cheap: ORT would map them anyway; hashing catches post-install corruption).
     */
    private fun extractGraphs(): File {
        val dir = File(context.filesDir, EXTRACT_DIR)
        val marker = File(dir, "graphs.meta")

        // ── Fast path: already extracted and pinned by the version marker ──────────────────
        if (marker.exists() && marker.readText().trim() == GRAPHS_VERSION) {
            for (name in listOf(ENCODER_ASSET, CTC_ASSET)) {
                SravaaniPackaging.verifyExtracted(dir, name)
            }
            return dir
        }

        // ── Medium path: files already exist (sideloaded / from a previous install whose
        //    marker was cleared) but marker is absent or stale. Verify in-place and pin. ──
        val encoderExists = File(dir, ENCODER_ASSET).isFile
        val ctcExists = File(dir, CTC_ASSET).isFile
        if (encoderExists && ctcExists) {
            Log.i(TAG, "Found pre-existing extracted graphs in ${dir.absolutePath} — verifying")
            for (name in listOf(ENCODER_ASSET, CTC_ASSET)) {
                SravaaniPackaging.verifyExtracted(dir, name)
            }
            // Pin so subsequent starts take the fast path (SHA-256 already done above).
            marker.writeText(GRAPHS_VERSION)
            Log.i(TAG, "Pre-existing graphs verified and pinned as $GRAPHS_VERSION")
            return dir
        }

        // ── Slow path: extract from APK assets (first install or forced re-extract) ────────
        dir.mkdirs()
        for (name in listOf(ENCODER_ASSET, CTC_ASSET)) {
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw SttException.ModelLoadError(target.path)
            }
            // Verify the extraction BEFORE the marker pins it — a mismatch throws
            // ModelLoadError (engine surfaces it; factory falls back to DefaultSttEngine).
            SravaaniPackaging.verifyExtracted(dir, name)
        }
        marker.writeText(GRAPHS_VERSION)
        Log.i(TAG, "Graphs extracted and verified to ${dir.absolutePath}")
        return dir
    }

    /**
     * Native sessions + per-engine immutable state (the 1.77 GB graphs + tables). Per-stream
     * caches live in [StreamNative].
     */
    private inner class Internals(
        val env: OrtEnvironment,
        val encoderSession: OrtSession,
        val ctcSession: OrtSession,
        val tables: SravaaniTables,
        val tokenizer: SravaaniTokenizer,
    ) {
        /** Encoder input block, mel-major: (PEC + CHUNK frames) x mel values. */
        val assembled = FloatArray(SravaaniStreamingState.CHUNK_FRAMES * MEL_BINS)

        /** Copy of [assembled] backed into a direct buffer for ORT. */
        val scratch = FloatArray(SravaaniStreamingState.CHUNK_FRAMES * MEL_BINS)

        /** Reusable direct buffer for encoder input features to avoid allocations per chunk. */
        val featsDirectBuf: java.nio.FloatBuffer = directFloatBuffer(SravaaniStreamingState.CHUNK_FRAMES * MEL_BINS)

        /** Reusable direct buffer for encoder feature lengths. */
        val featLenDirectBuf: java.nio.LongBuffer = directLongBuffer(longArrayOf(0L))

        fun newStream(language: SttLanguage? = null): StreamNative {
            val effLang = language ?: _currentLanguage ?: _config?.language ?: SttLanguage.ENGLISH
            return StreamNative(effLang)
        }

        fun close() {
            runCatching { encoderSession.close() }
            runCatching { ctcSession.close() }
        }
    }

    /** One live stream: streaming state + RNN-T decoder states. */
    private inner class StreamNative(val language: SttLanguage) {
        val state = SravaaniStreamingState(MEL_BINS).also {
            it.attach(SravaaniFeaturizer(internals!!.tables))
        }

        /** Precomputed language mask for constraining vocabulary argmax to the target language script. */
        val allowedMask: BooleanArray? = internals?.tokenizer?.getAllowedMask(language)

        /** Frame acoustic buffer for decoder step (1 x 1024 x 1). */
        val fBuf: java.nio.FloatBuffer = directFloatBuffer(ENC_D_MODEL.toInt())

        /** Predictor target token buffer (1 x 1). */
        val tgtBuf: java.nio.IntBuffer = directIntBuffer(1)

        /** Target length buffer (1). */
        val tlenBuf: java.nio.IntBuffer = directIntBuffer(1)

        /** Predictor LSTM hidden state buffer (1 x 1 x 640). */
        val hBuf: java.nio.FloatBuffer = directFloatBuffer(RNNT_HIDDEN_DIM.toInt())

        /** Predictor LSTM cell state buffer (1 x 1 x 640). */
        val cBuf: java.nio.FloatBuffer = directFloatBuffer(RNNT_HIDDEN_DIM.toInt())

        /** Last emitted token, initialized to blank (5000) on fresh stream. */
        var prevToken: Int = BLANK_ID

        init {
            tgtBuf.put(0, prevToken)
            tlenBuf.put(0, 1)
        }

        /** Push a slice of [pcm] (converted to f32) into the stream's featurizer. */
        fun pushPcm(pcm: ShortArray, offset: Int, count: Int) {
            if (count > internalBlock.size) internalBlock = FloatArray(count)
            for (i in 0 until count) internalBlock[i] = pcm[offset + i] / 32768f
            state.pushPcm(internalBlock, count)
        }

        private var internalBlock = FloatArray(PUSH_SAMPLES)

        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Idempotent: finish() and release() may both attempt the close. */
        fun close() {
            closed.set(true)
        }
    }

    private companion object {
        const val TAG = "SW_SRAVAANI"

        /** Asset dir for tables/tokens + graphs (P7 owns final packaging). */
        const val ASSET_DIR = "sravaani"
        const val TABLES_ASSET = "tables.bin"
        const val TOKENS_ASSET = "tokens.txt"
        const val ENCODER_ASSET = "sravaani_encoder.onnx"
        const val CTC_ASSET = "sravaani_ctc.onnx"

        /** App-private extraction dir under filesDir (mmap source). */
        const val EXTRACT_DIR = "stt/sravaani"

        /** Re-extract graphs when this changes (runtime pin + model revision + artifact selection). */
        const val GRAPHS_VERSION = "ort-1.27.0-rev-29a15303-int8-prod"

        /** Exact asset byte counts from production manifest (for SttModelInfo). */
        const val GRAPH_ENCODER_BYTES = 638_281_605L
        const val GRAPH_CTC_BYTES = 43_033_419L

        const val MEL_BINS = 128
        const val BLANK_ID = 5000
        const val VOCAB_WITH_BLANK = 5001
        const val TOTAL_OUTPUT_DIM = 5006
        const val ENC_D_MODEL = 1024L
        const val RNNT_HIDDEN_DIM = 640L
        const val MAX_SYMBOLS_PER_FRAME = 10

        /**
         * PCM push granularity: bounded so the featurizer ring (32768 samples) always covers
         * the chunk read-back (~19.8k samples) with margin, and small enough for live-ish
         * chunk draining.
         */
        const val PUSH_SAMPLES = 8_192

        /**
         * Live-session push granularity ([SravaaniLiveStream.pushPcm]): like [PUSH_SAMPLES] but
         * also bounded by a 200 ms window, so live partial latency stays ≤ ~200 ms + chunk time.
         */
        const val FEED_SAMPLES = 3_200 // 200 ms @ 16 kHz — also < PUSH_SAMPLES

        /** Mid-range big-core default; P10/P11 measure and tune. */
        const val INTRA_OP_THREADS = 4

        private fun directFloatBuffer(elems: Int): java.nio.FloatBuffer =
            ByteBuffer.allocateDirect(elems * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun directIntBuffer(elems: Int): java.nio.IntBuffer =
            ByteBuffer.allocateDirect(elems * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

        private fun directLongBuffer(values: LongArray): java.nio.LongBuffer {
            val buf = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
            buf.put(values)
            buf.rewind()
            return buf
        }
    }
}
