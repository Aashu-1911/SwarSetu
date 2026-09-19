package app.swarsetu.stt.sravaani

/**
 * Per-stream state + chunk assembly for SraVaani 0.5 — a faithful Kotlin port of the official
 * streaming loop (`new_stream` / `_chunk_with_cache` / `stream_features` / `finalize` in
 * `hf/modeling_sravaani_streaming.py`), with the featurization replaced by the O(n) incremental
 * [SravaaniFeaturizer] instead of the official O(n²) full-buffer recompute.
 *
 * Official streaming constants (artifact `config.json`):
 * ```
 * chunk_size = 112, shift_size = 112, pre_encode_cache_size = 9
 * min_sampling_frames = 1, feat_in = 128, subsampling_factor = 8
 * ```
 *
 * **Chunk assembly (official `_chunk_with_cache`):** each encoder block is frames
 * `[featIdx - 9, featIdx + chunk)`; on step 0 the missing left context is zero-padded with
 * `pad_value` (0.0). Frames are re-normalized with the CURRENT statistics at assembly time —
 * exactly what the official path does when it re-slices its normalized tensor.
 *
 * **Live vs finalize (official `stream_audio` vs `finalize`):**
 * - live: only full chunks run (`avail - featIdx >= 112`), advancing by `shift` (112 — equal
 *   here, so chunks never overlap);
 * - finalize: the trailing PARTIAL chunk also runs, shorter than 112 frames (the official
 *   slices a tail chunk of `avail - featIdx` frames and passes the true length as `feat_len` —
 *   the graph is dynamic on frames), while at least `min_sampling_frames` remains.
 *
 * Not thread-safe; the engine serializes access.
 */
class SravaaniStreamingState(private val melBins: Int) {

    /** Next feature frame to consume (official `feat_idx`). */
    var featIdx: Long = 0L
        private set

    /** Cumulative token history for this utterance (official `tokens[b]`). */
    val tokens: MutableList<Int> = mutableListOf()

    /** Official `finished` flag: true once [markFinalized] is called. */
    var finished: Boolean = false
        private set

    /** True once [beginFinalize] has switched the machine to tail-flush mode. */
    var finalizing: Boolean = false
        private set

    /** Total raw PCM samples pushed (for RTF accounting). */
    var samplesPushed: Long = 0L
        private set

    private lateinit var featurizer: SravaaniFeaturizer

    /** Wiring done by the engine (it owns tables + featurizer lifecycle). */
    internal fun attach(featurizer: SravaaniFeaturizer) {
        this.featurizer = featurizer
    }

    /** Feature frames produced so far (== official `seq_len` for the same audio). */
    val framesProduced: Long get() = featurizer.producedFrames

    /** Frames not yet consumed. */
    val framesPending: Long get() = framesProduced - featIdx

    /** Push raw PCM into the live stream (invalid after [beginFinalize]). */
    fun pushPcm(samples: FloatArray, sampleCount: Int) {
        check(!finished && !finalizing) { "stream no longer accepts audio" }
        samplesPushed += sampleCount
        featurizer.push(samples, sampleCount)
    }

    /** Switch to tail-flush mode: no more audio; partial trailing chunks may now run. */
    fun beginFinalize() {
        check(!finished)
        finalizing = true
    }

    /**
     * Whether a chunk is ready to run right now: a full chunk in live mode, or any
     * [MIN_SAMPLING_FRAMES]-frame remainder in finalize mode.
     */
    fun hasChunkReady(): Boolean {
        if (finished) return false
        val pending = framesPending
        return if (finalizing) pending >= MIN_SAMPLING_FRAMES else pending >= CHUNK_SIZE
    }

    /**
     * Assemble the next encoder block into [out] (row-major frames x [melBins]).
     *
     * @return the total block length in frames T = cache(9) + chunkFrames, or 0 when no chunk
     *   is ready. The caller passes T as `feat_len` to the encoder graph (the tail chunk is
     *   deliberately shorter than the full block capacity).
     */
    fun assembleChunk(out: FloatArray): Int {
        check(!finished)
        val pending = framesPending
        val chunkFrames =
            when {
                pending >= CHUNK_SIZE -> CHUNK_SIZE
                finalizing && pending >= MIN_SAMPLING_FRAMES -> pending.toInt()
                else -> return 0
            }
        val total = PEC + chunkFrames
        require(out.size >= total * melBins) { "chunk buffer too small: ${out.size} < ${total * melBins}" }
        val frameBuf = FloatArray(melBins)
        var row = 0
        // Pre-encode cache: frames [featIdx-9, featIdx); pad_value 0.0 where index < 0.
        for (i in PEC downTo 1) {
            val idx = featIdx - i
            if (idx >= 0) {
                featurizer.getNormalizedFrame(idx, frameBuf)
                System.arraycopy(frameBuf, 0, out, row * melBins, melBins)
            } else {
                java.util.Arrays.fill(out, row * melBins, (row + 1) * melBins, PAD_VALUE)
            }
            row++
        }
        // Chunk body: frames [featIdx, featIdx + chunkFrames).
        for (i in 0 until chunkFrames) {
            featurizer.getNormalizedFrame(featIdx + i, frameBuf)
            System.arraycopy(frameBuf, 0, out, row * melBins, melBins)
            row++
        }
        return total
    }

    /** Advance the consume cursor by [SHIFT_SIZE] after a successful chunk run. */
    fun advanceChunk() {
        check(!finished)
        featIdx += SHIFT_SIZE
    }

    /** Mark the stream finalized (engine calls after draining trailing chunks). */
    fun markFinalized() {
        finished = true
    }

    /** Drop all per-stream decode state; called between utterances by the engine. */
    fun reset() {
        featIdx = 0
        tokens.clear()
        finished = false
        finalizing = false
        samplesPushed = 0
        if (this::featurizer.isInitialized) featurizer.reset()
    }

    companion object {
        /** Official config.json streaming constants — do not change without re-exporting. */
        const val CHUNK_SIZE = 112
        const val SHIFT_SIZE = 112
        const val PEC = 9
        const val MIN_SAMPLING_FRAMES = 1
        const val PAD_VALUE = 0.0f

        /** Full encoder block frames = pec + chunk (121). */
        const val CHUNK_FRAMES = PEC + CHUNK_SIZE
    }
}
