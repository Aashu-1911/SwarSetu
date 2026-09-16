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
import app.swarsetu.stt.SttModelInfo
import app.swarsetu.stt.SttModelManager
import app.swarsetu.stt.SttResult
import app.swarsetu.stt.SttResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
 * This engine executes the P3-exported ONNX graphs of exactly those two components
 * (`sravaani_encoder.onnx` 1.77 GB fp32, `sravaani_ctc.onnx` 20.5 MB — weights unchanged
 * from the official fp16 artifact, upcast identically to the official CPU path; equivalence
 * gate: feat rel 2.6e-05, encoder rel 4.7e-05, collapsed token streams identical on all
 * structured trials) via ONNX Runtime, and reimplements the thin streaming brain in Kotlin
 * ([SravaaniFeaturizer], [SravaaniStreamingState], [SravaaniCtcDecoder], [SravaaniTokenizer]) —
 * each class is an exact port of the official Python, pinned by golden-vector tests.
 *
 * **Graph I/O contract (from `scripts/sravaani/export_onnx.py`, the source of truth):**
 * - encoder: `feats(B,128,T)` mel-major | `feat_len(1)` i64 | `cache_last_channel(1,17,70,1024)`
 *   | `cache_last_time(1,17,1024,8)` | `cache_last_channel_len(1)` i64
 *   → `enc(1,1024,T')` | `enc_len(1)` i64 | `cache_next_*` (same shapes as inputs).
 * - ctc: `enc(1,1024,T')` → `log_probs(1,5001,T')` — vocab-major, time on axis 2.
 * - The graph's `cache_next_*` outputs stay alive only while its [OrtSession.Result] is open,
 *   so they are COPIED into per-stream persistent tensors inside the result scope.
 *
 * **True streaming:** per-chunk encoder caches are carried across chunks (the graph's
 * `cache_next_*` outputs become the next `cache_last_*` inputs) and the CTC `prev_id` collapse
 * state spans chunk seams — the exact mechanism the official docstring identifies as what
 * naive chunked ASR drops. Unlike the official live path, featurization is O(n) incremental
 * (the official one recomputes features over the whole buffer per push).
 *
 * **Languages:** one multilingual, language-UNCONDITIONED model covering all 10 required
 * languages (verified from the model card tags + official demo — the model takes NO language
 * input and emits the spoken language's script). There is deliberately no per-language model,
 * no aliasing, and no auto-detection claim: [SttConfig.autoDetectLanguage] is a documented
 * no-op (the engine never conditions on language; transcripts come out in whatever language
 * was spoken).
 *
 * **Memory reality (measured, P2):** the fp32 encoder is ~1.77 GB on disk; mmap'd by ORT
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
            check(!released) { throw SttException.EngineNotReady("engine was released") }
            _config = config
            _currentLanguage = config.language
            // Verify packaging presence so failures surface at init, not mid-conversation.
            // NOTE: until P7 packages the graphs, this fails loudly on-device by design —
            // the missing artifact is a packaging gap, not a silent fallback.
            // (Sessions are NOT built here — see class KDoc.)
            requireGraphsPresent()
            modelManager.markLoaded(
                SttModelInfo(
                    language = config.language,
                    modelPath = ASSET_DIR,
                    sizeBytes = GRAPH_ENCODER_BYTES + GRAPH_CTC_BYTES,
                    description = "SraVaani 0.5-live (multilingual, 29a15303, MIT)",
                ),
            )
            Log.i(TAG, "SraVaani 0.5 initialized (packaging verified, sessions lazy)")
            Unit
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

    override suspend fun release() =
        mutex.withLock {
            released = true
            runCatching { internals?.close() }
            internals = null
            _currentLanguage?.let { modelManager.markReleased(it) }
            _config = null
            Log.i(TAG, "SraVaani engine released")
            Unit
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
                val stream = internal.newStream()
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
                val stream = internal.newStream()
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

    // ------------------------------------------------------------------ core loop

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
        val featsBuf = directFloatBuffer(total * MEL_BINS)
        featsBuf.put(internal.scratch, 0, total * MEL_BINS)
        featsBuf.rewind()
        val featsTensor = OnnxTensor.createTensor(
            env,
            featsBuf,
            longArrayOf(1, MEL_BINS.toLong(), total.toLong()),
        )
        val featLenTensor = OnnxTensor.createTensor(
            env,
            directLongBuffer(longArrayOf(total.toLong())),
            longArrayOf(1),
        )
        val results = try {
            internal.encoderSession.run(
                mapOf(
                    "feats" to featsTensor,
                    "feat_len" to featLenTensor,
                    "cache_last_channel" to stream.cacheCh,
                    "cache_last_time" to stream.cacheTc,
                    "cache_last_channel_len" to stream.cacheLen,
                ),
            )
        } finally {
            featsTensor.close()
            featLenTensor.close()
        }

        // The result scope owns `enc` and the cache_next_* outputs — everything that touches
        // them must happen inside this block. (enc feeds the CTC session directly.)
        // Access is BY INDEX: Result.get(String) returns OptionalOnnxValue in modern ORT,
        // while get(Int) returns OnnxValue directly. Index order is pinned by the export's
        // output_names: [enc, enc_len, cache_next_channel, cache_next_time, cache_next_len].
        results.use { r ->
            val encLen = (r.get(1) as OnnxTensor).longBuffer.get(0)

            // ---- CTC head over the raw encoder output tensor (B, 1024, T') ----
            val logitsResult = internal.ctcSession.run(mapOf("enc" to (r.get(0) as OnnxTensor)))
            logitsResult.use { lr ->
                val lp = (lr.get(0) as OnnxTensor).floatBuffer
                lp.rewind()
                val frames = lp.remaining() / VOCAB_PLUS_BLANK
                require(lp.remaining() % VOCAB_PLUS_BLANK == 0) {
                    "ctc logits size ${lp.remaining()} not a multiple of $VOCAB_PLUS_BLANK"
                }
                val valid = minOf(encLen, frames.toLong()).toInt()
                // Layout (V+1, T) row-major: element (v, t) at v * frames + t.
                val ids = IntArray(valid)
                for (t in 0 until valid) {
                    var best = 0
                    var bestVal = Float.NEGATIVE_INFINITY
                    for (v in 0 until VOCAB_PLUS_BLANK) {
                        val x = lp.get(v * frames + t)
                        if (x > bestVal) {
                            bestVal = x
                            best = v
                        }
                    }
                    ids[t] = best
                }
                stream.decoder.acceptFrameIds(ids, state.tokens)
            }

            // ---- adopt next-caches BY COPY (outputs die with this result) ----
            val nextCh = (r.get(2) as OnnxTensor).floatBuffer
            val nextTc = (r.get(3) as OnnxTensor).floatBuffer
            val nextLen = (r.get(4) as OnnxTensor).longBuffer
            require(nextCh.remaining() == CACHE_CH_ELEMS) {
                "cache_next_channel has ${nextCh.remaining()} elems, expected $CACHE_CH_ELEMS"
            }
            require(nextTc.remaining() == CACHE_TC_ELEMS) {
                "cache_next_time has ${nextTc.remaining()} elems, expected $CACHE_TC_ELEMS"
            }
            nextCh.rewind()
            nextTc.rewind()
            stream.chBuf.clear()
            stream.chBuf.put(nextCh)
            stream.chBuf.rewind() // ORT reads inputs from the buffer's current position
            stream.tcBuf.clear()
            stream.tcBuf.put(nextTc)
            stream.tcBuf.rewind()
            stream.lenBuf.put(0, nextLen.get(0))
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
        for (name in listOf(ENCODER_ASSET, CTC_ASSET)) {
            try {
                context.assets.open("$ASSET_DIR/$name").use { stream ->
                    require(stream.read() >= 0) { "empty asset" }
                }
            } catch (e: Exception) {
                throw SttException.ModelLoadError(
                    "$ASSET_DIR/$name (graphs not packaged yet — see P7 packaging milestone)",
                    e,
                )
            }
        }
    }

    /** Build (or reuse) the native sessions + tables. Heavy: first call maps ~1.8 GB. */
    private fun lazyInternals(): Internals {
        internals?.let { return it }
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
            Log.i(TAG, "ORT sessions created (encoder + ctc), tables + tokenizer loaded")
            return internals!!
        } catch (e: SttException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw SttException.OutOfMemory(cause = e)
        } catch (e: Exception) {
            throw SttException.ModelLoadError(ASSET_DIR, e)
        }
    }

    /**
     * Copy the ONNX graphs from assets to app-private storage (once) so ORT can mmap them —
     * assets cannot be mapped directly. Copy is version-guarded by a marker file; P7 owns the
     * final packaging decision (install-time extraction vs on-demand).
     */
    private fun extractGraphs(): File {
        val dir = File(context.filesDir, EXTRACT_DIR)
        val marker = File(dir, "graphs.meta")
        if (marker.exists() && marker.readText().trim() == GRAPHS_VERSION) return dir
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
        }
        marker.writeText(GRAPHS_VERSION)
        Log.i(TAG, "Graphs extracted to ${dir.absolutePath}")
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

        fun newStream(): StreamNative = StreamNative()

        fun close() {
            runCatching { encoderSession.close() }
            runCatching { ctcSession.close() }
        }
    }

    /** One live stream: streaming state + per-stream CTC decoder + persistent cache tensors. */
    private inner class StreamNative {
        val state = SravaaniStreamingState(MEL_BINS).also {
            it.attach(SravaaniFeaturizer(internals!!.tables))
        }

        /** Per-stream collapse state: prev id must NOT leak between utterances. */
        val decoder = SravaaniCtcDecoder()

        /** Persistent cache tensors wrapping direct buffers (refilled in-place after runs). */
        val cacheCh: OnnxTensor
        val cacheTc: OnnxTensor
        val cacheLen: OnnxTensor
        // Accessed from the outer engine's chunk loop — Kotlin private-in-inner would hide them.
        val chBuf: java.nio.FloatBuffer
        val tcBuf: java.nio.FloatBuffer
        val lenBuf: java.nio.LongBuffer

        init {
            val env = internals!!.env
            chBuf = directFloatBuffer(CACHE_CH_ELEMS) // direct buffers are zero-initialized
            tcBuf = directFloatBuffer(CACHE_TC_ELEMS)
            lenBuf = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asLongBuffer()
            cacheCh = OnnxTensor.createTensor(
                env,
                chBuf,
                longArrayOf(1, ENC_LAYERS, CACHE_CH_SIZE, ENC_D_MODEL),
            )
            cacheTc = OnnxTensor.createTensor(
                env,
                tcBuf,
                longArrayOf(1, ENC_LAYERS, ENC_D_MODEL, CACHE_TC_SIZE),
            )
            cacheLen = OnnxTensor.createTensor(env, lenBuf, longArrayOf(1))
        }

        /** Push a slice of [pcm] (converted to f32) into the stream's featurizer. */
        fun pushPcm(pcm: ShortArray, offset: Int, count: Int) {
            if (count > internalBlock.size) internalBlock = FloatArray(count)
            for (i in 0 until count) internalBlock[i] = pcm[offset + i] / 32768f
            state.pushPcm(internalBlock, count)
        }

        private var internalBlock = FloatArray(PUSH_SAMPLES)

        fun close() {
            runCatching { cacheCh.close() }
            runCatching { cacheTc.close() }
            runCatching { cacheLen.close() }
        }
    }

    private companion object {
        const val TAG = "SravaaniEngine"

        /** Asset dir for tables/tokens + graphs (P7 owns final packaging). */
        const val ASSET_DIR = "sravaani"
        const val TABLES_ASSET = "tables.bin"
        const val TOKENS_ASSET = "tokens.txt"
        const val ENCODER_ASSET = "sravaani_encoder.onnx"
        const val CTC_ASSET = "sravaani_ctc.onnx"

        /** App-private extraction dir under filesDir (mmap source). */
        const val EXTRACT_DIR = "stt/sravaani"

        /** Re-extract graphs when this changes (runtime pin + model revision). */
        const val GRAPHS_VERSION = "ort-1.27.0-rev-29a15303"

        /** Exact asset byte counts from the P3 export manifest (for SttModelInfo). */
        const val GRAPH_ENCODER_BYTES = 1_774_013_452L
        const val GRAPH_CTC_BYTES = 20_504_707L

        const val MEL_BINS = 128
        const val VOCAB_PLUS_BLANK = 5_001
        const val ENC_LAYERS = 17L
        const val ENC_D_MODEL = 1024L
        const val CACHE_CH_SIZE = 70L
        const val CACHE_TC_SIZE = 8L
        const val CACHE_CH_ELEMS = (1 * ENC_LAYERS * CACHE_CH_SIZE * ENC_D_MODEL).toInt()
        const val CACHE_TC_ELEMS = (1 * ENC_LAYERS * ENC_D_MODEL * CACHE_TC_SIZE).toInt()

        /**
         * PCM push granularity: bounded so the featurizer ring (32768 samples) always covers
         * the chunk read-back (~19.8k samples) with margin, and small enough for live-ish
         * chunk draining.
         */
        const val PUSH_SAMPLES = 8_192

        /** Mid-range big-core default; P10/P11 measure and tune. */
        const val INTRA_OP_THREADS = 4

        private fun directFloatBuffer(elems: Int): java.nio.FloatBuffer =
            ByteBuffer.allocateDirect(elems * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun directLongBuffer(values: LongArray): java.nio.LongBuffer {
            val buf = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
            buf.put(values)
            buf.rewind()
            return buf
        }
    }
}
