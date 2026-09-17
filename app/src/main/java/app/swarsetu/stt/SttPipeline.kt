package app.swarsetu.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

        _state.value = PipelineState.CAPTURING
        _partialText.value = ""
        _latestResult.value = null
        vad.reset()

        captureJob =
            scope.launch {
                mutex.withLock {
                    Log.d(TAG, "VOICE_START")
                    Log.d(TAG, "AUDIO_RECORD_CREATED")

                    val started = withContext(Dispatchers.IO) { capture.start() }
                    if (!started) {
                        _state.value = PipelineState.IDLE
                        return@withLock
                    }

                    Log.d(TAG, "AUDIO_RECORD_STARTED")

                    if (VOICE_CAPTURE_DIAGNOSTIC_MODE == DiagnosticMode.AUDIO_ONLY) {
                        Log.d(TAG, "AUDIO_ONLY_START")
                        captureAudioWithVad(language, silenceTimeoutMs)
                        return@withLock
                    }

                    // Ensure engine is ready for this language
                    if (!engine.isReady || engine.currentLanguage != language) {
                        try {
                            engine.initialize(SttConfig(language = language))
                            Log.d(TAG, "ENGINE_MODEL_READY")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Engine init threw Throwable for ${language.code}: ${e.javaClass.simpleName} - ${e.message}")
                            // The SraVaani model is multilingual + unconditioned: a failed init is
                            // never a per-language gap, so a language "fallback" would only mislabel
                            // results. Fail loudly instead (blocker policy — no silent substitution).
                            withContext(Dispatchers.IO) { capture.stop() }
                            _state.value = PipelineState.IDLE
                            return@withLock
                        }
                    }

                    captureAudioWithVad(language, silenceTimeoutMs)
                }
            }
    }

    /**
     * Stop capture manually. Transcription of the captured audio will proceed.
     */
    fun stopCapture() {
        if (_state.value != PipelineState.CAPTURING) return
        _state.value = PipelineState.PROCESSING
        capture.stop()
    }

    /**
     * Cancel capture and discard all audio. No transcription result will be produced.
     */
    fun cancelCapture() {
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
        cancelCapture()
        scope.cancel()
        engine.release()
    }

    // --- Internal ---

    private suspend fun captureAudioWithVad(
        language: SttLanguage,
        silenceTimeoutMs: Long,
    ) {
        var liveStream: SttLiveStream? = null
        var lastSpeechSample = 0
        var totalSamples = 0
        val samplesPerSilenceTimeout = (language.sampleRate * silenceTimeoutMs / 1000).toInt()
        val frameSize = language.sampleRate / 33 // ~30ms frames for VAD

        try {
            // ONE streaming session for the whole utterance: audio is transcribed exactly once
            // (cache-carried chunks), partials are cumulative — no full-buffer retranscription.
            // AUDIO_ONLY diagnostic mode never touches the engine (opening a live session would
            // lazily map the model, defeating the audio-diagnostic purpose).
            if (VOICE_CAPTURE_DIAGNOSTIC_MODE != DiagnosticMode.AUDIO_ONLY) {
                val stream = engine.startLiveStream(language)
                liveStream = stream
                stream.onPartial = { text ->
                    _partialText.value = text
                    Log.d(TAG, "Partial: \"$text\"")
                }
            }

            while (_state.value == PipelineState.CAPTURING) {
                val chunk =
                    withContext(Dispatchers.IO) {
                        capture.readChunk(maxSamples = frameSize)
                    }
                if (chunk == null) break

                // Update amplitude for UI
                var maxAmp = 0
                for (s in chunk) {
                    val a = kotlin.math.abs(s.toInt())
                    if (a > maxAmp) maxAmp = a
                }
                _amplitude.value = (maxAmp.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)

                // VAD processing
                val vadState = vad.processFrame(chunk)
                if (vadState == VoiceActivityDetector.State.SPEECH) {
                    lastSpeechSample = totalSamples + chunk.size
                }

                // Stream to the engine immediately — the engine buffers/batches internally.
                liveStream?.pushPcm(chunk)
                totalSamples += chunk.size

                // Check if silence timeout reached after speech (when silenceTimeoutMs > 0)
                if (silenceTimeoutMs > 0 && vadState == VoiceActivityDetector.State.SILENT && lastSpeechSample > 0) {
                    val silenceSamples = totalSamples - lastSpeechSample
                    if (silenceSamples >= samplesPerSilenceTimeout) {
                        Log.d(TAG, "VAD silence timeout after ${totalSamples * 1000L / language.sampleRate}ms")
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            // Abandon the session (frees any native state). finish() below is skipped —
            // the captured audio is unusable once the session errored.
            liveStream?.release()
            liveStream = null
            if (e is CancellationException) {
                // cancelCapture() cancelled this job — propagate instead of swallowing.
                throw e
            }
            Log.e(TAG, "Live streaming failed: ${e.javaClass.simpleName} - ${e.message}")
        }

        if (VOICE_CAPTURE_DIAGNOSTIC_MODE == DiagnosticMode.AUDIO_ONLY) {
            Log.d(TAG, "AUDIO_FRAME_COUNT: $totalSamples")
            Log.d(TAG, "AUDIO_ONLY_STOP")
            _state.value = PipelineState.IDLE
            return
        }

        // Capture complete — finalize the streaming session for the final transcript.
        if (liveStream == null || totalSamples == 0) {
            _state.value = PipelineState.IDLE
            return
        }

        _state.value = PipelineState.PROCESSING
        withContext(Dispatchers.IO) { capture.stop() }

        try {
            val result = liveStream.finish()

            _latestResult.value = result
            _partialText.value = result.text
            _state.value = PipelineState.COMPLETE
            Log.d(TAG, "Transcription: \"${result.text}\" (${result.type}, ${result.durationMs}ms)")
            _state.value = PipelineState.IDLE
        } catch (e: Throwable) {
            Log.e(TAG, "Final transcription failed: ${e.javaClass.simpleName} - ${e.message}")
            _state.value = PipelineState.IDLE
        }
    }

    companion object {
        const val TAG = "SttPipeline"
        val VOICE_CAPTURE_DIAGNOSTIC_MODE = DiagnosticMode.PRODUCTION
    }
}
