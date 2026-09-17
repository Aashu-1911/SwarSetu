package app.swarsetu.stt

import kotlinx.coroutines.flow.StateFlow

/**
 * Capture seam between [SttPipeline] and the microphone ([PcmCapture]).
 *
 * Exists so the full capture → VAD → engine loop can run in JVM integration tests with a
 * scripted fixture: production always gets [PcmCapture] (Android [android.media.AudioRecord]);
 * tests inject deterministic frame sequences. Members mirror the pipeline's usage of
 * [PcmCapture] exactly — no production behavior depends on this interface.
 */
interface AudioCapture {
    /** Capture state. */
    val state: StateFlow<PcmCapture.State>

    /** Samples captured in the current start→stop cycle. */
    val capturedSamples: Int

    /** Whether the device has a microphone and RECORD_AUDIO is granted. */
    fun hasPermission(): Boolean

    /** Whether capture can start (permission granted + not already capturing). */
    fun canCapture(): Boolean

    /** Open and start the microphone. Returns false if it could not be opened. */
    fun start(): Boolean

    /**
     * Read one chunk of PCM (up to [maxSamples] samples, 16-bit mono 16 kHz), suspending until
     * audio is available. Returns null when capture is not active.
     */
    suspend fun readChunk(maxSamples: Int = PcmCapture.SAMPLE_RATE / 10): ShortArray?

    /** Stop capture and release the microphone. Idempotent. */
    fun stop()

    /** Cancel capture and discard audio. Idempotent. */
    fun cancel()
}
