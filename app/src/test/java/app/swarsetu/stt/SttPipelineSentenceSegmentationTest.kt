package app.swarsetu.stt

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * P5 integration acceptance: through the REAL [SttPipeline] loop (capture → VAD → engine),
 * speech → pause → speech produces TWO finalized sentences, and no captured audio is lost.
 *
 * Uses a scripted [AudioCapture] fixture and a recording engine; the JVM has no microphone,
 * so the [PcmCapture] implementation itself runs on-device (P10/P11).
 */
class SttPipelineSentenceSegmentationTest {

    /** Context whose RECORD_AUDIO check is deterministically DENIED (keeps PcmCapture unused). */
    private fun noMicContext(): Context =
        mockk(relaxed = true) {
            every { checkSelfPermission(any<String>()) } returns PackageManager.PERMISSION_DENIED
            every { checkPermission(any<String>(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        }

    /**
     * Scripted capture: replays a fixed sequence of frames then goes dry ([readChunk] returns
     * null, exactly like a stopped [PcmCapture]). Implements the production [AudioCapture] seam.
     */
    private class ScriptedCapture(
        frames: List<ShortArray>,
    ) : AudioCapture {
        private val frames = frames.toMutableList()
        private val _state = MutableStateFlow(PcmCapture.State.IDLE)
        override val state = _state
        override val capturedSamples: Int get() = consumed * FRAME_SIZE
        private var consumed = 0

        override fun hasPermission() = true

        // The pipeline checks canCapture() BEFORE calling start(), so "not currently
        // capturing" is the contract — mirroring PcmCapture.canCapture().
        override fun canCapture() = _state.value != PcmCapture.State.CAPTURING
        override fun start(): Boolean {
            _state.value = PcmCapture.State.CAPTURING
            return true
        }

        override suspend fun readChunk(maxSamples: Int): ShortArray? {
            if (consumed >= frames.size) return null
            val f = frames[consumed++]
            return if (f.size <= maxSamples) f else f.copyOf(maxSamples)
        }

        override fun stop() {
            _state.value = PcmCapture.State.IDLE
        }

        override fun cancel() {
            _state.value = PcmCapture.State.IDLE
        }

        /** Re-arm the fixture for the next conversation turn. */
        fun refill(newFrames: List<ShortArray>) {
            frames.clear()
            frames.addAll(newFrames)
            consumed = 0
        }

        companion object {
            const val FRAME_SIZE = 480
        }
    }

    /** Engine that records every pushed sample and finalizes per live session. */
    private class RecordingEngine : SttEngine {
        var ready = false
        var current: SttLanguage? = null
        var liveSessions = 0
            private set
        var totalPushed = 0
            private set
        var finals = 0
            private set
        val partials = mutableListOf<String>()

        override val config: SttConfig? get() = current?.let { SttConfig(language = it) }
        override val isReady: Boolean get() = ready
        override val currentLanguage: SttLanguage? get() = current

        override suspend fun initialize(config: SttConfig) {
            ready = true
            current = config.language
        }

        override suspend fun setLanguage(language: SttLanguage) {
            current = language
        }

        override suspend fun transcribe(pcm: ShortArray, language: SttLanguage): SttResult =
            SttResult("batch", SttResultType.FINAL, language)

        override fun transcribeStream(pcm: ShortArray, language: SttLanguage): Flow<SttResult> =
            flow { emit(transcribe(pcm, language)) }

        override fun startLiveStream(language: SttLanguage): SttLiveStream {
            check(ready) { "not initialized" }
            liveSessions++
            var done = false
            return object : SttLiveStream {
                override var onPartial: ((String) -> Unit)? = null

                override suspend fun pushPcm(samples: ShortArray) {
                    check(!done) { "live stream already finished" }
                    totalPushed += samples.size
                    onPartial?.invoke("partial $totalPushed")
                    partials.add("partial $totalPushed")
                }

                override suspend fun finish(): SttResult {
                    check(!done) { "live stream already finished" }
                    done = true
                    finals++
                    return SttResult("final $finals", SttResultType.FINAL, language, durationMs = 0L)
                }

                override fun release() {
                    done = true
                }
            }
        }

        override suspend fun release() {
            ready = false
        }
    }

    // ------------------------------- fixture builders -------------------------------

    /** Frame of constant RMS [rms] (alternating +/- to be speech-like for an energy VAD). */
    private fun frameAt(rms: Int): ShortArray =
        ShortArray(480) { (rms * if (it % 2 == 0) 1 else -1).toShort() }

    private fun silenceFrame(): ShortArray = ShortArray(480)

    /**
     * One "sentence": 1 s of syllabic speech followed by 2 s of silence (the VAD finalize
     * pause). Speech is syllabic (RMS varies frame-to-frame) like real phonation — a
     * constant-RMS "speech" fixture is indistinguishable from machine hum to the VAD's
     * steady-noise rejection (and would be reclassified as noise, by design).
     */
    private fun sentenceFrames(): List<ShortArray> {
        val frames = mutableListOf<ShortArray>()
        repeat(33) { i ->
            val modulation = 0.75 + 0.5 * abs(((i % 6) / 5.0) * 2 - 1)
            frames.add(frameAt((2_000 * modulation).toInt()))
        }
        repeat(67) { frames.add(silenceFrame()) }
        return frames
    }

    // ------------------------------------ tests -------------------------------------

    @Test
    fun `speech pause speech yields two finalized sentences across two turns with no lost audio`() = runTest {
        val engine = RecordingEngine()
        val capture = ScriptedCapture(sentenceFrames())
        val pipeline = SttPipeline(noMicContext(), engine, capture)

        // Turn 1: 1 s speech + 2 s pause → finalized at the pause, session closes.
        pipeline.startCapture(SttLanguage.HINDI, silenceTimeoutMs = 2_000)
        awaitIdle(pipeline)
        assertEquals(1, engine.finals)
        assertEquals("final 1", pipeline.latestResult.value?.text)

        // Refill the fixture (the app re-arms capture per conversation turn) and speak again.
        capture.refill(sentenceFrames())
        pipeline.startCapture(SttLanguage.HINDI, silenceTimeoutMs = 2_000)
        awaitIdle(pipeline)

        assertEquals(2, engine.liveSessions)
        assertEquals(2, engine.finals)
        assertEquals("final 2", pipeline.latestResult.value?.text)
        // Audio fidelity: every scripted frame from BOTH turns reached the engine.
        assertEquals(2 * sentenceFrames().size * ScriptedCapture.FRAME_SIZE, engine.totalPushed)
        // Partials streamed (not batch): a partial per push.
        assertTrue(engine.partials.isNotEmpty())
    }

    @Test
    fun `capture ends on VAD silence timeout and finalizes once`() = runTest {
        val engine = RecordingEngine()
        val frames = sentenceFrames()
        val capture = ScriptedCapture(frames)
        val pipeline = SttPipeline(noMicContext(), engine, capture)

        pipeline.startCapture(SttLanguage.HINDI, silenceTimeoutMs = 2_000)
        awaitIdle(pipeline)

        assertEquals(1, engine.finals)
        assertEquals(frames.size * ScriptedCapture.FRAME_SIZE, engine.totalPushed)
        assertEquals(SttPipeline.PipelineState.IDLE, pipeline.state.value)
        assertEquals("final 1", pipeline.latestResult.value?.text)
    }

    /** Pump until the pipeline returns to IDLE (bounded; the scripted capture never blocks). */
    private fun awaitIdle(pipeline: SttPipeline) {
        repeat(10_000) {
            if (pipeline.state.value == SttPipeline.PipelineState.IDLE) return
            Thread.sleep(1)
        }
        error("pipeline never returned to IDLE")
    }
}
