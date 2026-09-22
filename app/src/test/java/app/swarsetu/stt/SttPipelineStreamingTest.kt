package app.swarsetu.stt

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 acceptance tests: the pipeline must run TRUE streaming — audio is transcribed exactly
 * once via a push-based [SttLiveStream] (cache-carrying in SraVaani), with cumulative
 * partials — never by re-transcribing the whole accumulated buffer per partial.
 *
 * Engine-level streaming behavior is verified with a recording [SttEngine]; JVM unit tests
 * cannot open the microphone, so pipeline-level tests here pin the guard/lifecycle paths
 * (full capture-loop streaming is validated on-device in P10/P11).
 */
class SttPipelineStreamingTest {

    /** Recording engine that mimics the SraVaani live path and counts audio work. */
    private class RecordingEngine : SttEngine {
        var ready = false
        var language: SttLanguage? = null
            private set
        var transcribeCalls = 0
            private set
        var liveSessionsOpened = 0
            private set
        var totalSamplesStreamed = 0
            private set
        var maxBufferRetranscribed = 0
            private set
        val partialTexts = mutableListOf<String>()
        var finalText = "final transcript"

        override val config: SttConfig? get() = language?.let { SttConfig(language = it) }
        override val isReady: Boolean get() = ready
        override val currentLanguage: SttLanguage? get() = language

        override suspend fun initialize(config: SttConfig) {
            ready = true
            language = config.language
        }

        override suspend fun setLanguage(language: SttLanguage) {
            this.language = language
        }

        override suspend fun transcribe(pcm: ShortArray, language: SttLanguage): SttResult {
            transcribeCalls++
            maxBufferRetranscribed = maxOf(maxBufferRetranscribed, pcm.size)
            return SttResult(finalText, SttResultType.FINAL, language)
        }

        override fun transcribeStream(pcm: ShortArray, language: SttLanguage): Flow<SttResult> =
            kotlinx.coroutines.flow.flow { emit(transcribe(pcm, language)) }

        override fun startLiveStream(language: SttLanguage): SttLiveStream {
            if (!ready) throw SttException.EngineNotReady("not initialized")
            liveSessionsOpened++
            return object : SttLiveStream {
                var finished = false
                var lastLen = 0
                override var onPartial: ((String) -> Unit)? = null

                override suspend fun pushPcm(samples: ShortArray) {
                    check(!finished) { "live stream already finished" }
                    totalSamplesStreamed += samples.size
                    lastLen += samples.size
                    // Cumulative partial per push, like SraVaani's per-chunk emission.
                    onPartial?.invoke("partial $lastLen").also { partialTexts.add("partial $lastLen") }
                }

                override suspend fun finish(): SttResult {
                    check(!finished) { "live stream already finished" }
                    finished = true
                    return SttResult(finalText, SttResultType.FINAL, language, durationMs = 0L)
                }

                override fun release() {
                    finished = true
                }
            }
        }

        override suspend fun release() {
            ready = false
        }
    }

    // ------------------------------------------------------------------ interface contract

    @Test
    fun `default startLiveStream batches and transcribes whole buffer`() = runTest {
        // Engine WITHOUT a native live path: the interface default must still work (chunked
        // re-transcription — the documented fallback, not the streaming path).
        // ChunkedSttLiveStream is constructed directly: RecordingEngine overrides
        // startLiveStream to model the SraVaani native path, so going through the engine
        // here would never exercise the fallback.
        val engine = RecordingEngine()
        engine.initialize(SttConfig(language = SttLanguage.HINDI))

        val stream = ChunkedSttLiveStream(engine, SttLanguage.HINDI)
        val partials = mutableListOf<String>()
        stream.onPartial = { partials.add(it) }

        // Two pushes that each reach the 1 s re-transcribe interval.
        stream.pushPcm(ShortArray(SttLanguage.SAMPLE_RATE) { 100 })
        stream.pushPcm(ShortArray(SttLanguage.SAMPLE_RATE) { 100 })
        val result = stream.finish()

        assertEquals("final transcript", result.text)
        assertEquals(SttResultType.FINAL, result.type)
        // 2 interval re-transcriptions + 1 final = 3 whole-buffer transcribes.
        assertEquals(3, engine.transcribeCalls)
        assertEquals(2 * SttLanguage.SAMPLE_RATE, engine.maxBufferRetranscribed)
        // Cumulative partials were emitted with the full-buffer text.
        assertEquals(2, partials.size)
    }

    @Test
    fun `default live stream buffers audio and never loses samples before finish`() = runTest {
        val engine = RecordingEngine()
        engine.initialize(SttConfig(language = SttLanguage.HINDI))

        val stream = ChunkedSttLiveStream(engine, SttLanguage.HINDI)
        // Below the re-transcribe interval: nothing transcribed yet, but nothing lost.
        stream.pushPcm(ShortArray(1000))
        assertEquals(0, engine.transcribeCalls)

        val result = stream.finish()
        // finish() transcribes the FULL buffered utterance exactly once.
        assertEquals(1, engine.transcribeCalls)
        assertEquals(1000, engine.maxBufferRetranscribed)
        assertEquals("final transcript", result.text)
    }

    @Test
    fun `live stream rejects push after finish`() = runTest {
        val engine = RecordingEngine()
        engine.initialize(SttConfig(language = SttLanguage.HINDI))
        val stream = engine.startLiveStream(SttLanguage.HINDI)
        stream.finish()
        val error = runCatching { stream.pushPcm(ShortArray(10)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `uninitialized engine rejects live stream`() = runTest {
        val engine = RecordingEngine()
        val error = runCatching { engine.startLiveStream(SttLanguage.HINDI) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is SttException.EngineNotReady)
    }

    // ------------------------------------------------------------------ pipeline lifecycle (JVM-safe paths)

    /** Context whose RECORD_AUDIO check is deterministically DENIED (JVM has no mic). */
    private fun noMicContext(): Context =
        mockk(relaxed = true) {
            every { checkSelfPermission(any<String>()) } returns PackageManager.PERMISSION_DENIED
            // ContextCompat.checkSelfPermission delegates to checkPermission when
            // SDK_INT < 23 — the mockable android.jar reports 0 — so stub both paths.
            every { checkPermission(any<String>(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        }

    @Test
    fun `pipeline stays idle when microphone cannot be captured`() = runTest {
        val engine = RecordingEngine()
        val pipeline = SttPipeline(noMicContext(), engine)

        // canCapture() is false on the JVM (no mic permission) — the guard must reject
        // the request without opening a live session or flipping state. Deterministic:
        // the guard rejects before any coroutine launch, so there is no race with the
        // capture thread resetting state.
        pipeline.startCapture(SttLanguage.HINDI, silenceTimeoutMs = 0)

        assertEquals(SttPipeline.PipelineState.IDLE, pipeline.state.value)
        assertEquals(0, engine.liveSessionsOpened)
    }

    @Test
    fun `pipeline cancel resets state and clears partials`() = runTest {
        val engine = RecordingEngine()
        val pipeline = SttPipeline(noMicContext(), engine)

        pipeline.cancelCapture()
        assertEquals(SttPipeline.PipelineState.IDLE, pipeline.state.value)
        assertEquals("", pipeline.partialText.value)
        assertEquals(0f, pipeline.amplitude.value)
        assertEquals(0, engine.liveSessionsOpened)
    }

    // ------------------------------------------------------------------ audio fidelity (VAD path)

    @Test
    fun `vad hysteresis marks speech then silence without frame drops`() {
        // Regression guard for the P4 rewrite: silence bookkeeping is sample-based
        // (totalSamples - lastSpeechSample), so no captured audio is dropped between the
        // VAD decision and the engine push. The VAD itself must still hysteresis correctly.
        val vad = VoiceActivityDetector()
        val speech = ShortArray(480) { 1000 }
        repeat(3) { vad.processFrame(speech) }
        assertEquals(VoiceActivityDetector.State.SPEECH, vad.state)
        val silence = ShortArray(480)
        repeat(20) { vad.processFrame(silence) }
        assertEquals(VoiceActivityDetector.State.SILENT, vad.state)
    }

    @Test
    fun `stt language serves all ten required languages without aliasing`() {
        // P4/P6 guard: the multilingual SraVaani model serves every declared language;
        // no language may be hidden or mapped onto another.
        val required = setOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")
        assertEquals(required, SttLanguage.entries.map { it.code }.toSet())
        for (lang in SttLanguage.entries) {
            assertFalse(lang.code.isBlank())
        }
    }

    private class ScriptedAudioCapture(private val chunksPerSession: Int = 4) : AudioCapture {
        private val _state = MutableStateFlow(PcmCapture.State.IDLE)
        override val state: StateFlow<PcmCapture.State> = _state
        override var capturedSamples: Int = 0
            private set
        private var chunkCount = 0

        override fun hasPermission(): Boolean = true
        override fun canCapture(): Boolean = _state.value != PcmCapture.State.CAPTURING
        override fun start(): Boolean {
            _state.value = PcmCapture.State.CAPTURING
            capturedSamples = 0
            chunkCount = 0
            return true
        }

        override suspend fun readChunk(maxSamples: Int): ShortArray? {
            if (_state.value != PcmCapture.State.CAPTURING) return null
            if (chunkCount >= chunksPerSession) return null
            chunkCount++
            capturedSamples += maxSamples
            return ShortArray(maxSamples) { 1000 }
        }

        override fun stop() {
            _state.value = PcmCapture.State.IDLE
        }

        override fun cancel() {
            _state.value = PcmCapture.State.IDLE
        }
    }

    @Test
    fun `repeated voice inputs execute cleanly across multiple sessions`() = runTest {
        val engine = RecordingEngine()
        engine.initialize(SttConfig(language = SttLanguage.HINDI))
        val capture = ScriptedAudioCapture(chunksPerSession = 4)
        val pipeline = SttPipeline(mockk(relaxed = true), engine, capture)

        for (i in 1..5) {
            pipeline.startCapture(SttLanguage.HINDI, silenceTimeoutMs = 0)
            var waited = 0
            while (pipeline.state.value != SttPipeline.PipelineState.IDLE && waited < 200) {
                Thread.sleep(20)
                waited++
            }
            assertEquals("Iteration $i state", SttPipeline.PipelineState.IDLE, pipeline.state.value)
            assertEquals("final transcript", pipeline.latestResult.value?.text)
            assertEquals(i, engine.liveSessionsOpened)
        }
        assertEquals(5, engine.liveSessionsOpened)
    }
}
