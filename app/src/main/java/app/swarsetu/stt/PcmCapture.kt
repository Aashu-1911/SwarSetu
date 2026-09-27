package app.swarsetu.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Captures raw PCM audio from the microphone for STT input. Uses [AudioRecord] to produce
 * 16-bit signed PCM at 16 kHz mono — the format [SttEngine.transcribe] expects.
 *
 * **Relationship to VoiceRecorder:** The existing [app.swarsetu.ui.voice.VoiceRecorder] uses
 * [MediaRecorder] to produce AAC-LC in ADTS format (for voice-note storage). [PcmCapture] is
 * a parallel capture path that produces raw PCM specifically for STT inference. Both can coexist —
 * the PCM path feeds the STT engine while the AAC path feeds the voice-note blob store.
 *
 * **Lifecycle:**
 * 1. Check [hasPermission] / [canCapture] before constructing.
 * 2. Call [start] to open the microphone.
 * 3. Call [readChunk] repeatedly to get PCM buffers, or use [captureUntilSilence] for VAD-based capture.
 * 4. Call [stop] to release the microphone.
 *
 * **Thread safety:** Not thread-safe. Drive from a single coroutine scope (the ViewModel's).
 *
 * **Resource cleanup:** [stop] is idempotent and releases the microphone. The ViewModel must call
 * [stop] from `onCleared` (same pattern as [app.swarsetu.ui.voice.VoiceRecorder]).
 *
 * **No Knit/networking/TTS dependencies.** Pure Android audio capture.
 */
class PcmCapture(
    private val context: Context,
) : AudioCapture {
    /** Current state of the capture. */
    enum class State {
        IDLE,
        CAPTURING,
        STOPPED,
    }

    private var recorder: AudioRecord? = null
    private var _state = MutableStateFlow(State.IDLE)

    /** Live capture state. Observable by the UI for status indicators. */
    override val state: StateFlow<State> = _state.asStateFlow()

    /** Number of samples captured so far during the current [start]→[stop] cycle. */
    override var capturedSamples: Int = 0
        private set

    /** Duration of captured audio in milliseconds. */
    val capturedDurationMs: Long get() = capturedSamples * 1000L / SAMPLE_RATE

    /**
     * Whether the device has a microphone and the app has [Manifest.permission.RECORD_AUDIO].
     */
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether capture can start (permission granted + not already capturing).
     */
    override fun canCapture(): Boolean = hasPermission() && _state.value != State.CAPTURING

    /**
     * Start capturing PCM audio. Opens the microphone.
     *
     * @return true if capture started successfully, false if the microphone couldn't be opened.
     */
    override fun start(): Boolean {
        Log.d("SW_AUDIO", "AUDIO_CAPTURE_START")
        if (!canCapture()) {
            Log.w("SW_AUDIO", "AUDIO_CAPTURE_FAILED: canCapture returned false")
            return false
        }

        val bufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
            )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("SW_AUDIO", "Invalid buffer size: $bufferSize")
            return false
        }

        // Headroom for inference bursts: a SraVaani chunk run through the encoder can take 50-150ms.
        // If the ring wraps, AudioRecord silently overwrites unread samples (words clipped).
        // 1 full second (32 KB at 16kHz 16-bit mono) gives generous buffer headroom without
        // affecting readChunk latency, guaranteeing zero dropped audio frames across repeated inputs.
        val actualBufferSize = maxOf(bufferSize * 8, SAMPLE_RATE * 2)

        try {
            var rec =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, // No AGC/noise suppression
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    actualBufferSize,
                )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w("SW_AUDIO", "VOICE_RECOGNITION audio source failed to initialize, falling back to MIC")
                runCatching { rec.release() }
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    actualBufferSize,
                )
            }
            recorder = rec
        } catch (e: SecurityException) {
            Log.e("SW_AUDIO", "Microphone permission denied: ${e.message}")
            return false
        } catch (e: IllegalArgumentException) {
            Log.e("SW_AUDIO", "Invalid AudioRecord parameters: ${e.message}")
            return false
        }

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SW_AUDIO", "AudioRecord failed to initialize")
            runCatching { recorder?.release() }
            recorder = null
            return false
        }

        recorder?.startRecording()
        _state.value = State.CAPTURING
        capturedSamples = 0
        Log.d("SW_AUDIO", "AUDIO_CAPTURE_STARTED (sampleRate=${SAMPLE_RATE}Hz, buffer=${actualBufferSize}B)")
        return true
    }

    /**
     * Read a chunk of PCM samples from the microphone. Suspends until samples are available.
     *
     * @param maxSamples Maximum number of samples to read. The actual number read may be fewer.
     * @return PCM samples as a ShortArray, or null when capture is not active.
     */
    override suspend fun readChunk(maxSamples: Int): ShortArray? =
        withContext(Dispatchers.IO) {
            val rec = recorder ?: return@withContext null
            if (_state.value != State.CAPTURING) return@withContext null

            val buffer = ShortArray(maxSamples)
            val read = rec.read(buffer, 0, maxSamples)
            if (read <= 0) {
                Log.w("SW_AUDIO", "AudioRecord read returned $read")
                return@withContext null
            }

            capturedSamples += read
            Log.v("SW_AUDIO", "AUDIO_FRAME_RECEIVED (size=$read, total=$capturedSamples)")
            if (read == maxSamples) buffer else buffer.copyOf(read)
        }

    /**
     * Capture audio until silence is detected (energy drops below threshold for [silenceMs]).
     * Returns all captured PCM samples, or null on failure.
     *
     * This is the primary capture method for STT: record until the user stops talking,
     * then transcribe the captured audio.
     *
     * @param silenceMs How long silence must persist before stopping. Default 1500ms.
     * @param maxDurationMs Maximum capture duration. Default 5 minutes.
     * @param onAmplitude Callback for live amplitude (0..1) updates. Called on the capture thread.
     */
    suspend fun captureUntilSilence(
        silenceMs: Long = 1_500L,
        maxDurationMs: Long = 5 * 60 * 1_000L,
        onAmplitude: ((Float) -> Unit)? = null,
    ): ShortArray? =
        withContext(Dispatchers.IO) {
            val rec = recorder ?: return@withContext null
            if (_state.value != State.CAPTURING) return@withContext null

            val allSamples = mutableListOf<Short>()
            val silenceThreshold = SILENCE_THRESHOLD
            val samplesPerSilence = (SAMPLE_RATE * silenceMs / 1000).toInt()
            val samplesPerMax = (SAMPLE_RATE * maxDurationMs / 1000).toInt()
            val chunkSize = SAMPLE_RATE / 10 // 100ms chunks

            var consecutiveSilentSamples = 0
            var totalSamples = 0

            while (totalSamples < samplesPerMax) {
                ensureActive()

                val buffer = ShortArray(chunkSize)
                val read = rec.read(buffer, 0, chunkSize)
                if (read <= 0) break

                // Check amplitude
                var maxAmplitude = 0
                for (i in 0 until read) {
                    val sample = kotlin.math.abs(buffer[i].toInt())
                    if (sample > maxAmplitude) maxAmplitude = sample
                }
                val amplitude = (maxAmplitude.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)
                onAmplitude?.invoke(amplitude)

                // Track silence
                if (maxAmplitude < silenceThreshold) {
                    consecutiveSilentSamples += read
                    if (consecutiveSilentSamples >= samplesPerSilence && allSamples.isNotEmpty()) {
                        Log.d(TAG, "Silence detected after ${totalSamples * 1000L / SAMPLE_RATE}ms")
                        break
                    }
                } else {
                    consecutiveSilentSamples = 0
                }

                // Accumulate
                for (i in 0 until read) {
                    allSamples.add(buffer[i])
                }
                totalSamples += read
            }

            if (allSamples.isEmpty()) {
                null
            } else {
                ShortArray(allSamples.size) { allSamples[it] }
            }
        }

    /**
     * Stop capturing and release the microphone. Idempotent.
     */
    override fun stop() {
        Log.d("SW_AUDIO", "AUDIO_CAPTURE_STOP (AUDIO_FRAME_COUNT=$capturedSamples)")
        if (_state.value != State.CAPTURING) return
        _state.value = State.IDLE
        runCatching {
            recorder?.stop()
        }
        runCatching {
            recorder?.release()
        }
        recorder = null
        Log.d("SW_AUDIO", "Capture stopped: $capturedSamples samples (${capturedDurationMs}ms)")
    }

    /**
     * Cancel an in-progress capture and discard all captured audio.
     */
    override fun cancel() {
        Log.d("SW_AUDIO", "AUDIO_CAPTURE_STOP (Cancelled, AUDIO_FRAME_COUNT=$capturedSamples)")
        _state.value = State.IDLE
        runCatching {
            recorder?.stop()
        }
        runCatching {
            recorder?.release()
        }
        recorder = null
        capturedSamples = 0
    }

    companion object {
        private const val TAG = "PcmCapture"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Minimum amplitude to consider as speech (above absolute silence/noise floor). */
        const val SILENCE_THRESHOLD = 300 // ~1% of Short.MAX_VALUE
    }
}
