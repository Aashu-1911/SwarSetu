package app.swarsetu.stt.sravaani

/**
 * Exact Kotlin port of the official SraVaani 0.5 featurization
 * (`hf/modeling_sravaani_streaming.py::extract_features` + `_normalize_per_feature`).
 *
 * Math (constants verified against the official artifact by
 * `scripts/sravaani/gen_android_assets.py` and the P3 ONNX equivalence gate):
 * ```
 * preemph(0.97) -> centered STFT(n_fft=512, hop=160, win=400, official window)
 *   -> |X|^2 -> mel(128x257, official fb) -> log(x + 5.96e-8)
 *   -> per-feature normalization: per mel-bin mean/std over ALL valid frames
 *      (sample variance, denominator N-1; std += 1e-5; std NaN -> 0 first)
 * ```
 *
 * **Frame validity (official `seq_len = wavLen // hop` exactly):** frame f is valid once its
 * full hop slot fits in the signal, `(f+1)*HOP <= written` — NOT once its window start has
 * arrived. `stft(center=True)` zero-pads the signal, so the LAST valid frame's window
 * typically extends past the signal end and is computed with zeros there (a *partial* frame).
 * The official path consumes those partial-tail values as-is.
 *
 * **Normalization statistics (the exact official quantity):** the official path recomputes
 * features over the ENTIRE buffer on every call, so at any chunk-consumption moment the mean/
 * std cover all valid frames with each frame's CURRENT value — final for every frame whose
 * window has fully arrived, partial (zero-padded tail) for the trailing 1 frame still inside
 * its window. This class reproduces that exactly:
 * - every frame enters the running per-bin sum/sumsq exactly ONCE, at the moment its window
 *   completes (`written >= f*HOP + 200`), with its final value;
 * - the at-most-one produced-but-incomplete frame contributes its CURRENT partial value at
 *   query time (recomputed lazily, invalidated by pushes).
 * A frame is produced at `written = (f+1)*HOP` and completes at `written = f*HOP + 200`
 * (= production + 40 samples), so at most ONE frame is pending at any time.
 *
 * **Why a hand featurizer at all (design decision, evidence in
 * `docs/STT_SRAVAANI_GRAPH_VALIDATION.md`):** the official live path recomputes features over
 * the ENTIRE audio buffer on every push — O(n²) overall. This class computes each frame at
 * most twice (once partial while pending, once final) — O(n) total, O(ring) peak memory —
 * while being value-identical to the official statistics at every chunk boundary.
 *
 * **Retention contract:** raw preemphasized samples are kept in a ring of [RING_CAPACITY]
 * samples (~203 frames). The engine reads frames `[chunkStart - 9, chunkStart + 112)` right
 * after each push (a ≤121-frame lookback ≈ 19.8k samples) and must bound its pushes so that
 * lookback stays inside the ring; violations fail loudly via [getNormalizedFrame].
 *
 * Correctness is pinned by `SravaaniFeaturizerGoldenTest` against vectors generated from the
 * official Python math (1e-3 abs tolerance; the desktop reference-vs-graph gate measured 4.4e-4).
 *
 * Not thread-safe; the engine serializes access per stream.
 */
class SravaaniFeaturizer(private val tables: SravaaniTables) {

    /** Total raw samples pushed (preemph is 1:1, so this is also the preemph length). */
    private var written: Long = 0L

    /** Previous RAW sample for the preemphasis recurrence (persists across pushes). */
    private var prevRaw: Double = 0.0

    /** Valid frames produced so far — equals the official `seq_len = written // HOP`. */
    private var nextFrame: Long = 0L

    // ---- statistics over COMPLETE frames (each enters exactly once, with its final value) ----
    private var completeCount: Long = 0L
    private val sum = DoubleArray(tables.melBins)
    private val sumsq = DoubleArray(tables.melBins)

    // ---- the at-most-one produced-but-incomplete frame ----
    private var pendingIdx: Long = -1L
    private var pendingValid: Boolean = false
    private var pendingDirty: Boolean = false
    private val pendingValue = FloatArray(tables.melBins)

    /** Preemphasized sample ring. Slot of preemph position p: (p % RING_CAPACITY). */
    private val ring = DoubleArray(RING_CAPACITY)

    /** Power-spectrum scratch, reused. */
    private val spectrum = FloatArray(tables.fbBins)

    /** Analysis-window scratch (preemphasized samples of the frame being computed). */
    private val windowScratch = DoubleArray(tables.winLength)

    /** Raw log-mel scratch for complete-frame reads. */
    private val frameScratch = FloatArray(tables.melBins)

    /**
     * Push raw PCM and update frame production.
     *
     * @param samples raw PCM in [-1, 1] f32 @16 kHz mono; any block size (the engine bounds
     *   block size to keep the [getNormalizedFrame] lookback inside the ring).
     * @param sampleCount number of valid samples in [samples].
     */
    fun push(
        samples: FloatArray,
        sampleCount: Int,
    ) {
        require(sampleCount in 0..samples.size)
        for (i in 0 until sampleCount) {
            val raw = samples[i].toDouble()
            // x'[0] = x[0]; x'[n] = x[n] - 0.97 * x[n-1]  (exact official recurrence)
            val preemph = raw - PREEMPH * prevRaw
            prevRaw = raw

            ring[(written % RING_CAPACITY).toInt()] = preemph
            written++

            // 1) Complete the pending frame first — its window just finished arriving.
            if (pendingValid && written >= pendingIdx * HOP + PAD_EFFECTIVE) {
                completePending()
            }
            // 2) Produce newly valid frames (official seq_len = wavLen // HOP): frame f is
            //    valid once its full hop slot fits: (f+1)*HOP <= written.
            while ((nextFrame + 1) * HOP <= written) {
                pendingIdx = nextFrame
                pendingValid = true
                pendingDirty = true
                nextFrame++
            }
        }
    }

    /** Valid frames produced so far — equals the official `seq_len` for the same audio. */
    val producedFrames: Long get() = nextFrame

    /**
     * The normalized frame [index] as the CURRENT statistics define it — the exact value the
     * official path would produce for this frame at this audio position. The engine calls this
     * while assembling encoder chunks, immediately after a push.
     */
    fun getNormalizedFrame(index: Long, out: FloatArray) {
        require(index in 0 until nextFrame) {
            "frame $index not produced (have $nextFrame)"
        }
        val hasPending = pendingValid
        val n = completeCount + (if (hasPending) 1L else 0L)
        if (n <= 1L) {
            // Official edge (N=1): mean == the only value -> (x - mean) == 0 for every bin.
            java.util.Arrays.fill(out, 0f)
            return
        }
        // Refresh the pending frame's partial value (its window gains samples on every push).
        if (hasPending && pendingDirty) {
            computeFrameInto(pendingValue, pendingIdx)
            pendingDirty = false
        }
        // Requested frame's raw log-mel value (final for complete frames, current partial
        // value for the pending frame itself — both exactly what the official path holds).
        val value: FloatArray =
            if (hasPending && index == pendingIdx) {
                pendingValue
            } else {
                computeFrameInto(frameScratch, index)
                frameScratch
            }
        val melBins = tables.melBins
        for (m in 0 until melBins) {
            val pv = if (hasPending) pendingValue[m].toDouble() else 0.0
            val s = sum[m] + pv
            val ss = sumsq[m] + pv * pv
            val mean = s / n
            var variance = (ss - n * mean * mean) / (n - 1.0)
            if (variance < 0.0 || variance.isNaN()) variance = 0.0
            val std = kotlin.math.sqrt(variance)
            out[m] = ((value[m].toDouble() - mean) / (std + CONSTANT)).toFloat()
        }
    }

    /** Reset all per-stream state (new utterance). Tables and scratch are retained. */
    fun reset() {
        written = 0
        prevRaw = 0.0
        nextFrame = 0
        completeCount = 0
        java.util.Arrays.fill(sum, 0.0)
        java.util.Arrays.fill(sumsq, 0.0)
        pendingValid = false
        pendingIdx = -1L
        pendingDirty = false
    }

    // ------------------------------------------------------------------ internals

    /** Move the pending frame into the permanent statistics with its now-final value. */
    private fun completePending() {
        computeFrameInto(pendingValue, pendingIdx)
        completeCount++
        for (m in 0 until tables.melBins) {
            val v = pendingValue[m].toDouble()
            sum[m] += v
            sumsq[m] += v * v
        }
        pendingValid = false
        pendingIdx = -1L
        pendingDirty = false
    }

    /** Compute raw log-mel for frame [f] into [dest]; window starts at `f*HOP - 200`. */
    private fun computeFrameInto(dest: FloatArray, f: Long) {
        copyWindow(f * HOP - PAD_EFFECTIVE)
        computeFromScratch(dest)
    }

    /** Read preemph samples [start, start+win) into [windowScratch], zero-filling absent. */
    private fun copyWindow(start: Long) {
        val lastArrived = written - 1
        for (j in 0 until tables.winLength) {
            val p = start + j
            windowScratch[j] =
                if (p < 0 || p > lastArrived) 0.0 else ring[(p % RING_CAPACITY).toInt()]
        }
    }

    /** DFT via the fused basis + mel projection + log; writes into [dest]. */
    private fun computeFromScratch(dest: FloatArray) {
        val win = tables.winLength
        val fbBins = tables.fbBins
        val cosTab = tables.cosTab
        val sinTab = tables.sinTab
        for (k in 0 until fbBins) {
            val row = k * win
            var re = 0.0
            var im = 0.0
            for (j in 0 until win) {
                val s = windowScratch[j]
                re += s * cosTab[row + j]
                im += s * sinTab[row + j]
            }
            spectrum[k] = (re * re + im * im).toFloat()
        }
        val melFb = tables.melFb
        for (m in 0 until tables.melBins) {
            val row = m * fbBins
            var acc = 0.0
            for (k in 0 until fbBins) acc += melFb[row + k] * spectrum[k]
            dest[m] = kotlin.math.ln(acc + LOG_GUARD).toFloat()
        }
    }

    companion object {
        private const val PREEMPH = 0.97
        private const val LOG_GUARD = 5.960464477539063e-08
        private const val CONSTANT = 1e-05

        /** hop_length from the official preproc params. */
        const val HOP = 160

        /**
         * Net centering pad per side for stft(center=True, win=400, n_fft=512): the stft pads
         * n_fft//2 = 256, then centers the 400-window inside the 512 frame leaving
         * (512-400)/2 = 56 zeros each side, so the signal pad is 256 - 56 = 200.
         * Also the window tail length past the frame start (completion threshold).
         */
        const val PAD_EFFECTIVE = 200

        /**
         * Raw-sample ring capacity: must cover the engine's worst read-back — a chunk assembly
         * reads frames [chunkStart-9, chunkStart+112), i.e. 121 frame starts back (~19,360
         * samples) plus one window (400). 32768 gives ~1.6x margin (~2.05 s of audio).
         */
        const val RING_CAPACITY = 32_768
    }
}
