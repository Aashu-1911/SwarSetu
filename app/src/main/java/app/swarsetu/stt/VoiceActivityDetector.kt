package app.swarsetu.stt

/**
 * Simple energy-based Voice Activity Detector (VAD). Detects speech vs silence in PCM audio
 * by computing the short-time energy of each frame.
 *
 * Used by the STT pipeline to determine speech activity for pause/timeout handling. The
 * pipeline streams EVERY captured frame to the engine regardless of VAD state — the VAD is
 * an activity signal, never an audio gate — so it cannot clip words by construction.
 *
 * **Algorithm:** Computes the root-mean-square (RMS) energy of each frame. When the energy
 * drops below the effective silence threshold for [silenceFrames] consecutive frames, speech
 * is considered ended. When it rises above the effective speech threshold for [speechFrames]
 * consecutive frames, speech is considered started. Energy between the two thresholds is a
 * hysteresis dead zone (no state change).
 *
 * **Adaptive noise floor (P5):** while not in speech, a slow EMA tracks the ambient RMS and
 * RAISES both effective thresholds above their static floors ([silenceThreshold] /
 * [speechThreshold]) when the environment is noisy — e.g. traffic whose RMS sits above the
 * static speech threshold would otherwise trigger continuously. Thresholds never drop below
 * the configured constants, so quiet-room behavior is unchanged. The floor freezes during
 * speech and decays back during silence.
 *
 * **Steady-noise rejection (P5):** energy alone cannot distinguish continuous fan/traffic
 * hum from speech once the hum exceeds the speech threshold (it would latch SPEECH forever).
 * Speech is syllabic — frame-to-frame RMS varies; steady machine noise is flat. While in
 * SPEECH, a sliding ~1 s RMS window with near-zero spread (spread <
 * [NOISE_SPREAD_FRACTION] × mean) is classified as noise: the state drops to SILENT and the
 * noise floor is raised to the window mean so the hum cannot immediately re-trigger.
 * Known limits (documented in `docs/STT_SRAVAANI_AUDIO_CONTRACT.md`): a genuinely
 * constant-RMS sustained vowel for a full window is theoretically misclassified — the audio
 * still streams to the engine, so the worst case is an early finalize, never lost audio;
 * background SPEECH and sudden transients remain indistinguishable by energy alone.
 *
 * **Calibration:** The static floors are calibrated for 16-bit PCM at 16 kHz with the
 * `VOICE_RECOGNITION` audio source (no AGC). Real-world threshold tuning on target devices
 * happens in P11.
 *
 * **Not Knit/networking/TTS aware.** Pure signal processing.
 */
class VoiceActivityDetector(
    /** RMS energy below which a frame is considered silent. */
    private val silenceThreshold: Int = DEFAULT_SILENCE_THRESHOLD,
    /** RMS energy above which a frame is considered speech. */
    private val speechThreshold: Int = DEFAULT_SPEECH_THRESHOLD,
    /** Number of consecutive silent frames required to declare end of speech. */
    private val silenceFrames: Int = DEFAULT_SILENCE_FRAMES,
    /** Number of consecutive speech frames required to declare start of speech. */
    private val speechFrames: Int = DEFAULT_SPEECH_FRAMES,
) {
    /** Current VAD state. */
    enum class State {
        /** No speech detected. */
        SILENT,

        /** Speech is in progress. */
        SPEECH,
    }

    private var consecutiveSilent = 0
    private var consecutiveSpeech = 0
    private var _state = State.SILENT

    /** Ambient RMS estimate (EMA over non-speech frames). 0 = perfectly quiet room. */
    private var noiseFloorRms = 0f

    /** Sliding window of recent frame RMS values for steady-noise detection. */
    private val rmsWindow = ArrayDeque<Int>(NOISE_WINDOW_FRAMES)

    /** Current state. */
    val state: State get() = _state

    /** Effective silence threshold this frame: static floor, or noise floor × [SILENCE_FLOOR_MULTIPLIER]. */
    val effectiveSilenceThreshold: Int
        get() = maxOf(silenceThreshold, (noiseFloorRms * SILENCE_FLOOR_MULTIPLIER).toInt())

    /** Effective speech threshold this frame: static floor, or noise floor × [SPEECH_FLOOR_MULTIPLIER]. */
    val effectiveSpeechThreshold: Int
        get() = maxOf(speechThreshold, (noiseFloorRms * SPEECH_FLOOR_MULTIPLIER).toInt())

    /**
     * Process a PCM frame and update the VAD state. Returns the current state after processing.
     *
     * @param pcm 16-bit signed PCM samples (one frame, typically 10–30 ms worth).
     * @return Current VAD state after processing this frame.
     */
    fun processFrame(pcm: ShortArray): State {
        val energy = computeRmsEnergy(pcm)
        val silenceLimit = effectiveSilenceThreshold
        val speechLimit = effectiveSpeechThreshold

        if (energy < silenceLimit) {
            consecutiveSilent++
            consecutiveSpeech = 0
            if (consecutiveSilent >= silenceFrames && _state == State.SPEECH) {
                _state = State.SILENT
            }
        } else if (energy > speechLimit) {
            consecutiveSpeech++
            consecutiveSilent = 0
            if (consecutiveSpeech >= speechFrames && _state == State.SILENT) {
                _state = State.SPEECH
            }
        }
        // Energy between thresholds: no state change (hysteresis)

        // Steady-noise rejection: syllabic speech varies frame-to-frame; machine hum does not.
        rmsWindow.addLast(energy)
        if (rmsWindow.size > NOISE_WINDOW_FRAMES) rmsWindow.removeFirst()
        if (_state == State.SPEECH && rmsWindow.size == NOISE_WINDOW_FRAMES) {
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            var sum = 0L
            for (v in rmsWindow) {
                if (v < min) min = v
                if (v > max) max = v
                sum += v
            }
            val mean = sum / rmsWindow.size
            if (max - min < NOISE_SPREAD_FRACTION * mean) {
                // Flat ~1 s window while "in speech" = steady noise that tripped the
                // threshold. Drop to SILENT and pin the floor at the hum level so it
                // cannot re-trigger until the ambient sound actually changes.
                _state = State.SILENT
                consecutiveSpeech = 0
                noiseFloorRms = maxOf(noiseFloorRms, mean.toFloat())
            }
        }

        // Adapt the ambient estimate ONLY outside speech (speech must not pump the floor)
        // and only for plausibly-ambient energy (a loud onset is speech, not ambience).
        if (_state != State.SPEECH && energy <= speechLimit * ONSET_SKIP_FACTOR) {
            noiseFloorRms =
                ADAPTATION_ALPHA * energy + (1f - ADAPTATION_ALPHA) * noiseFloorRms
        }

        return _state
    }

    /**
     * Reset the VAD to its initial silent state. Call when starting a new utterance.
     */
    fun reset() {
        consecutiveSilent = 0
        consecutiveSpeech = 0
        noiseFloorRms = 0f
        rmsWindow.clear()
        _state = State.SILENT
    }

    /**
     * Compute the RMS energy of a PCM frame. Returns a value in [0, Short.MAX_VALUE].
     */
    private fun computeRmsEnergy(pcm: ShortArray): Int {
        if (pcm.isEmpty()) return 0
        var sumSquares = 0L
        for (sample in pcm) {
            val s = sample.toLong()
            sumSquares += s * s
        }
        return kotlin.math.sqrt(sumSquares.toDouble() / pcm.size).toInt()
    }

    companion object {
        /**
         * Default silence threshold. Calibrated for 16-bit PCM with VOICE_RECOGNITION source.
         * Below this, a frame is considered silence. Tune on target devices.
         */
        const val DEFAULT_SILENCE_THRESHOLD = 200

        /**
         * Default speech threshold. Above this, a frame is considered speech.
         * Set higher than silence threshold for hysteresis.
         */
        const val DEFAULT_SPEECH_THRESHOLD = 400

        /**
         * Number of consecutive silent frames (at 30ms/frame = ~600ms) to declare end of speech.
         */
        const val DEFAULT_SILENCE_FRAMES = 20

        /**
         * Number of consecutive speech frames to declare start of speech.
         * Prevents brief noise from triggering speech detection.
         */
        const val DEFAULT_SPEECH_FRAMES = 3

        /** EMA weight per frame for the ambient noise estimate (~2 s time constant at 30 ms frames). */
        const val ADAPTATION_ALPHA = 0.05f

        /** Effective silence threshold ≥ noise floor × this. */
        const val SILENCE_FLOOR_MULTIPLIER = 2f

        /** Effective speech threshold ≥ noise floor × this (must exceed the silence multiplier). */
        const val SPEECH_FLOOR_MULTIPLIER = 3f

        /** Ambient-energy update skip: energy above effective speech × this is an onset, not ambience. */
        const val ONSET_SKIP_FACTOR = 2

        /** Steady-noise window length (~1 s at 30 ms frames). */
        const val NOISE_WINDOW_FRAMES = 33

        /** A window whose RMS spread is below this fraction of its mean is steady noise, not speech. */
        const val NOISE_SPREAD_FRACTION = 0.08f
    }
}
