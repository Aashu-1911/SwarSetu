package app.swarsetu.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * End-to-end STT pipeline that ties together [PcmCapture], [VoiceActivityDetector], and
 * [SttEngine] into a single, robust API for the application layer.
 */
class SttPipeline(
    private val context: Context,
    private val engine: SttEngine,
    private val capture: AudioCapture = PcmCapture(context),
) {
    /** Pipeline state. */
    enum class PipelineState {
        /** Idle — no capture in progress. */
        IDLE,

        /** Capturing audio from the microphone. */
        CAPTURING,

        /** Processing captured audio (transcription in progress). */
        PROCESSING,

        /** Transcription complete. Result available in [latestResult]. */
        COMPLETE,
    }

    enum class DiagnosticMode { PRODUCTION, AUDIO_ONLY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val vad = VoiceActivityDetector()

    private var captureJob: Job? = null

    /** Tracks the background prewarm job so startCapture() can cancel it before acquiring
     * the engine mutex, preventing a lock-ordering deadlock between pipeline.mutex and
     * engine.mutex. */
    private var prewarmJob: Job? = null

    private val _state = MutableStateFlow(PipelineState.IDLE)

    /** Current pipeline state. */
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")

    /** Live partial transcription text during capture. Updated in real-time. */
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)

    /** Live microphone amplitude (0..1) during capture. For UI level meter. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _latestResult = MutableStateFlow<SttResult?>(null)

    /** The most recent completed transcription result. */
    val latestResult: StateFlow<SttResult?> = _latestResult.asStateFlow()

    /** Whether the microphone permission is granted. */
    val hasPermission: Boolean get() = capture.hasPermission()

    /** Whether capture can start. */
    val canCapture: Boolean get() = capture.canCapture()

    /**
     * Start capturing audio.
     *
     * The microphone opens **immediately** so the user gets instant feedback. Engine warm-up
     * (ORT session creation / SHA-256 verification) runs concurrently on [Dispatchers.Default].
     * The live-stream session is opened only after warm-up completes, so the first [pushPcm]
     * call is never blocked by session init. Audio captured during warm-up is forwarded to the
     * stream once it opens.
     *
     * @param language Language to transcribe.
     * @param silenceTimeoutMs How long silence persists before auto-stopping. 0 = manual stop only.
     */
    fun startCapture(
        language: SttLanguage,
        silenceTimeoutMs: Long = 2_000L,
    ) {
        if (_state.value != PipelineState.IDLE) {
            Log.w(TAG, "Already capturing/processing")
            return
        }
        if (!capture.canCapture()) {
            Log.w(TAG, "Cannot capture (permission: ${capture.hasPermission()})")
            return
        }

        // Cancel any in-flight prewarm BEFORE touching the engine — prewarm holds engine.mutex
        // while building ORT sessions, and engine.initialize() below also needs that lock.
        // Cancelling here releases the prewarm lock so startCapture never deadlocks.
        prewarmJob?.cancel()
        prewarmJob = null

        _state.value = PipelineState.CAPTURING
        _partialText.value = ""
        _latestResult.value = null
        vad.reset()

        captureJob =
            scope.launch {
                mutex.withLock {
                    Log.d("SW_VOICE", "VOICE_SESSION_CREATED")
                    Log.d("SW_AUDIO", "AUDIO_CAPTURE_START")

                    // Open the microphone immediately for instant PTT response.
                    val started = withContext(Dispatchers.IO) { capture.start() }
                    if (!started) {
                        Log.e("SW_AUDIO", "AUDIO_CAPTURE_FAILED")
                        _state.value = PipelineState.IDLE
                        return@withLock
                    }

                    Log.d("SW_AUDIO", "AUDIO_CAPTURE_STARTED")

                    if (VOICE_CAPTURE_DIAGNOSTIC_MODE == DiagnosticMode.AUDIO_ONLY) {
                        Log.d("SW_AUDIO", "AUDIO_ONLY_START")
                        captureAudioWithVad(language, silenceTimeoutMs)
                        return@withLock
                    }

                    // Warm up (and initialize if needed) the engine concurrently with the first chunk reads
                    // so the mic is live immediately.
                    val warmUpJob = async(Dispatchers.Default) {
                        runCatching {
                            if (!engine.isReady || engine.currentLanguage != language) {
                                Log.d("SW_STT", "STT_ENGINE_INITIALIZE_START")
                                engine.initialize(SttConfig(language = language))
                                Log.d("SW_STT", "STT_ENGINE_INITIALIZE_SUCCESS")
                            }
                            engine.warmUp()
                        }.onFailure {
                            Log.e("SW_STT", "STT_ENGINE_INITIALIZE_FAILED: ${it.message}", it)
                        }
                    }

                    captureAudioWithVad(language, silenceTimeoutMs, warmUpJob)
                }
            }
    }

    /**
     * Pre-warm the engine for [language] before the user presses PTT. Call this when the chat
     * screen becomes visible so the 5–15 s ORT session creation cost is paid in the background,
     * making the first actual PTT press feel instant.
     *
     * Safe to call multiple times (idempotent). Failures are swallowed — the engine will
     * retry lazily on the first real [startCapture].
     */
    fun prewarm(language: SttLanguage) {
        prewarmJob = scope.launch {
            runCatching {
                if (!engine.isReady) {
                    Log.d("SW_STT", "STT_ENGINE_INITIALIZE_START (prewarm)")
                    engine.initialize(SttConfig(language = language))
                    Log.d("SW_STT", "STT_ENGINE_INITIALIZE_SUCCESS (prewarm)")
                }
                engine.warmUp()
                Log.d("SW_STT", "Engine pre-warmed for ${language.code}")
            }.onFailure {
                if (it is CancellationException) return@onFailure // cancelled by startCapture — expected
                Log.w("SW_STT", "Engine pre-warm failed (will retry lazily): ${it.message}")
            }
        }
    }

    /**
     * Stop capture manually. Transcription of the captured audio will proceed.
     */
    fun stopCapture() {
        if (_state.value != PipelineState.CAPTURING) return
        Log.d("SW_VOICE", "VOICE_SESSION_STOPPED")
        _state.value = PipelineState.PROCESSING
        capture.stop()
    }

    /**
     * Cancel capture and discard all audio. No transcription result will be produced.
     */
    fun cancelCapture() {
        Log.d("SW_VOICE", "VOICE_SESSION_CANCELLED")
        capture.cancel()
        captureJob?.cancel()
        captureJob = null
        _state.value = PipelineState.IDLE
        _partialText.value = ""
        _amplitude.value = 0f
    }

    /**
     * Release all resources (engine, capture). Call from ViewModel.onCleared or app shutdown.
     */
    suspend fun release() {
        Log.d("SW_STT", "STT_RELEASE_START")
        try {
            cancelCapture()
            scope.cancel()
            engine.release()
            Log.d("SW_STT", "STT_RELEASE_SUCCESS")
        } catch (e: Exception) {
            Log.e("SW_STT", "STT_RELEASE_FAILED: ${e.message}", e)
        }
    }

    // --- Internal ---

    private suspend fun captureAudioWithVad(
        language: SttLanguage,
        silenceTimeoutMs: Long,
        warmUpJob: kotlinx.coroutines.Deferred<*>? = null,
    ) {
        var liveStream: SttLiveStream? = null
        var lastSpeechSample = 0
        var totalSamples = 0
        var pcmChunksReceived = 0
        val samplesPerSilenceTimeout = (language.sampleRate * silenceTimeoutMs / 1000).toInt()
        val frameSize = language.sampleRate / 33 // ~30ms frames for VAD

        // Holds audio captured while warmUp is still running so no speech is lost.
        // Null when there is no concurrent warmUp (stream opens immediately below).
        val prewarmBuffer: ArrayDeque<ShortArray>? = if (warmUpJob != null) ArrayDeque() else null

        /** Open the live stream, attach the partial callback, and flush the prewarm buffer. */
        suspend fun openStream(): SttLiveStream {
            Log.d("SW_STT", "STT_STREAM_START (lang=${language.code})")
            val s = engine.startLiveStream(language)
            s.onPartial = { text ->
                _partialText.value = text
                Log.d("SW_STT", "STT_PARTIAL_RESULT: \"$text\"")
            }
            prewarmBuffer?.forEach { buffered -> 
                Log.v("SW_STT", "STT_PCM_RECEIVED (prewarm flush size=${buffered.size})")
                s.pushPcm(buffered) 
            }
            prewarmBuffer?.clear()
            return s
        }

        try {
            // AUDIO_ONLY diagnostic: never open a live stream (would trigger lazy model load).
            // For PRODUCTION: open the stream immediately if no concurrent warmUp is pending;
            // otherwise leave liveStream null — the loop below will open it once warmUp is done.
            if (VOICE_CAPTURE_DIAGNOSTIC_MODE != DiagnosticMode.AUDIO_ONLY && warmUpJob == null) {
                liveStream = openStream()
            }

            // ── Capture loop — starts immediately regardless of warmUp status ──────────────
            // Audio is never held waiting for ORT session creation. Chunks go to liveStream
            // as soon as it is open; before that they accumulate in prewarmBuffer.
            while (_state.value == PipelineState.CAPTURING) {
                val chunk = withContext(Dispatchers.IO) {
                    capture.readChunk(maxSamples = frameSize)
                }
                if (chunk == null) break
                pcmChunksReceived++

                // Update amplitude for UI
                var maxAmp = 0
                for (s in chunk) {
                    val a = kotlin.math.abs(s.toInt())
                    if (a > maxAmp) maxAmp = a
                }
                _amplitude.value = (maxAmp.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)

                // VAD
                val vadState = vad.processFrame(chunk)
                if (vadState == VoiceActivityDetector.State.SPEECH) {
                    lastSpeechSample = totalSamples + chunk.size
                }

                if (VOICE_CAPTURE_DIAGNOSTIC_MODE != DiagnosticMode.AUDIO_ONLY) {
                    // Opportunistically open the stream the moment warmUp completes.
                    // isCompleted is non-blocking; await() is only called once it is already true.
                    if (liveStream == null && warmUpJob?.isCompleted == true) {
                        liveStream = openStream()
                    }

                    if (liveStream != null) {
                        Log.v("SW_STT", "STT_PCM_RECEIVED (size=${chunk.size})")
                        liveStream.pushPcm(chunk)
                    } else {
                        // warmUp still in progress — buffer so the audio isn't lost.
                        prewarmBuffer?.add(chunk)
                    }
                }

                totalSamples += chunk.size

                // Silence-timeout auto-stop (only when silenceTimeoutMs > 0)
                if (silenceTimeoutMs > 0 && vadState == VoiceActivityDetector.State.SILENT && lastSpeechSample > 0) {
                    val silenceSamples = totalSamples - lastSpeechSample
                    if (silenceSamples >= samplesPerSilenceTimeout) {
                        Log.d("SW_AUDIO", "VAD silence timeout after ${totalSamples * 1000L / language.sampleRate}ms")
                        break
                    }
                }
            }

            // ── Post-loop: if warmUp didn't finish during recording, await it now and flush ─
            // All audio is already in prewarmBuffer; awaiting here is safe (we're no longer
            // in the capture loop, so blocking a few seconds here is acceptable — the user
            // already released PTT and is just waiting for the transcript).
            if (VOICE_CAPTURE_DIAGNOSTIC_MODE != DiagnosticMode.AUDIO_ONLY && liveStream == null && warmUpJob != null) {
                Log.d("SW_STT", "WarmUp not done at loop exit — awaiting now before finalize")
                warmUpJob.await()
                liveStream = openStream()
            }

        } catch (e: Throwable) {
            // Release any native stream state so we don't leak ORT tensors.
            liveStream?.release()
            liveStream = null
            // Always stop the mic on the error path — if we skip this, PcmCapture stays
            // in State.CAPTURING and the NEXT canCapture() check returns false, causing
            // the "Couldn't record that voice message" error on the very next PTT press.
            runCatching { withContext(Dispatchers.IO) { capture.stop() } }
            _amplitude.value = 0f
            if (e is CancellationException) {
                // cancelCapture() cancelled this job — propagate so the coroutine exits cleanly.
                throw e
            }
            Log.e("SW_STT", "STT_DECODE_ERROR (Live streaming failed: ${e.javaClass.simpleName} - ${e.message})", e)
        }

        // ── AUDIO_ONLY diagnostic path ────────────────────────────────────────────────────
        if (VOICE_CAPTURE_DIAGNOSTIC_MODE == DiagnosticMode.AUDIO_ONLY) {
            Log.d("SW_AUDIO", "AUDIO_FRAME_COUNT: $totalSamples, CHUNKS: $pcmChunksReceived")
            Log.d("SW_AUDIO", "AUDIO_ONLY_STOP")
            withContext(Dispatchers.IO) { capture.stop() }
            _state.value = PipelineState.IDLE
            return
        }

        // ── Early exit: nothing to transcribe ─────────────────────────────────────────────
        if (liveStream == null || totalSamples == 0) {
            Log.w("SW_STT", "STT_EMPTY_RESULT (liveStream=$liveStream, totalSamples=$totalSamples, chunks=$pcmChunksReceived)")
            liveStream?.release()
            // Ensure mic is released even when stopCapture() was NOT called (e.g. chunk==null).
            withContext(Dispatchers.IO) { capture.stop() }
            _state.value = PipelineState.IDLE
            return
        }

        // ── Finalize: flush remaining audio and get the final transcript ──────────────────
        _state.value = PipelineState.PROCESSING
        withContext(Dispatchers.IO) { capture.stop() }
        Log.d("SW_STT", "STT_STREAM_STOP (AUDIO_FRAME_COUNT=$totalSamples, CHUNKS=$pcmChunksReceived)")

        try {
            val result = liveStream.finish()
            _latestResult.value = result
            _partialText.value = result.text
            _state.value = PipelineState.COMPLETE
            if (result.text.isNotBlank()) {
                Log.d("SW_STT", "STT_FINAL_RESULT: \"${result.text}\" (${result.type}, ${result.durationMs}ms)")
            } else {
                Log.d("SW_STT", "STT_EMPTY_RESULT (${result.durationMs}ms)")
            }
            _state.value = PipelineState.IDLE
        } catch (e: Throwable) {
            Log.e("SW_STT", "STT_DECODE_ERROR (Final transcription failed: ${e.javaClass.simpleName} - ${e.message})", e)
            _state.value = PipelineState.IDLE
        }
    }

    companion object {
        const val TAG = "SttPipeline"
        val VOICE_CAPTURE_DIAGNOSTIC_MODE = DiagnosticMode.PRODUCTION
    }
}
