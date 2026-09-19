package app.swarsetu.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * P5 acceptance matrix: the energy VAD must segment sentences around pauses WITHOUT clipping
 * words, under the condition spread required by the production doc (§16 conditions mapped to
 * synthetic, deterministic fixtures):
 *
 * short / long speech, intra-sentence pauses (0.5 / 1.0 / 1.5 s), final pause, multi-sentence,
 * fan-like steady noise, quiet speech (-30 dBFS), loud speech (-3 dBFS), noise-floor adaptation.
 *
 * **Fixtures model real phonation:** speech frames are SYLLABIC (frame RMS varies ±25%
 * around a base level) — real speech is never constant-RMS, and constant-RMS "speech" is
 * indistinguishable from machine hum by energy alone (documented limit of the detector).
 * Steady noise fixtures are exactly that: constant RMS.
 *
 * **Clipping contract:** the pipeline streams EVERY frame to the engine regardless of VAD
 * state — the VAD only drives the end-of-utterance timeout. "No clipping" here means the VAD
 * must not finalize while speech is ongoing (steady speech stays SPEECH; the pipeline
 * finalizes only after silenceTimeoutMs of VAD silence), and must re-enter SPEECH for a new
 * sentence after a real pause.
 */
class VoiceActivityDetectorMatrixTest {

    /** ~30 ms frame at 16 kHz — the size SttPipeline feeds (sampleRate / 33). */
    private fun frameAt(rms: Int): ShortArray =
        ShortArray(480) { (rms * if (it % 2 == 0) 1 else -1).coerceIn(-32768, 32767).toShort() }

    /**
     * Syllabic speech: [count] frames whose RMS oscillates ±25% around [base] with a 6-frame
     * period (≈5 Hz syllabic rate — realistic phonation energy variation).
     */
    private fun syllabicFrames(
        count: Int,
        base: Int,
    ): List<ShortArray> =
        List(count) { i ->
            val modulation = 0.75 + 0.5 * abs(((i % 6) / 5.0) * 2 - 1) // 0..1 triangle
            frameAt((base * modulation).toInt().coerceAtLeast(1))
        }

    /** Sine tone frame with the given peak amplitude. */
    private fun toneFrame(peak: Int): ShortArray = ShortArray(480) { (peak * sin(2 * PI * it / 20.0)).toInt().toShort() }

    /** Steady machine noise: constant RMS — flat by construction. */
    private fun steadyNoiseFrames(
        count: Int,
        rms: Int,
    ): List<ShortArray> = List(count) { frameAt(rms) }

    private fun feedAll(vad: VoiceActivityDetector, frames: List<ShortArray>): Int {
        var speechFrames = 0
        for (f in frames) {
            if (vad.processFrame(f) == VoiceActivityDetector.State.SPEECH) speechFrames++
        }
        return speechFrames
    }

    private fun feedSilence(vad: VoiceActivityDetector, frames: Int): Int = feedAll(vad, List(frames) { ShortArray(480) })

    // -------------------------------------------------------------- speech conditions

    @Test
    fun `short speech under 1s is detected and released after final pause`() {
        val vad = VoiceActivityDetector()
        // 0.5 s speech (17 frames), then final silence.
        val speechFrames = feedAll(vad, syllabicFrames(17, 2_000))
        assertTrue("short speech must trigger (got $speechFrames)", speechFrames >= 14)
        assertEquals(VoiceActivityDetector.State.SPEECH, vad.state)

        // 2 s silence (67 frames) — hangover (20) must be exceeded, no earlier.
        feedSilence(vad, 19)
        assertEquals("hangover must hold through <600 ms silence", VoiceActivityDetector.State.SPEECH, vad.state)
        feedSilence(vad, 48)
        assertEquals("2 s pause must end the utterance", VoiceActivityDetector.State.SILENT, vad.state)
    }

    @Test
    fun `long 30s syllabic speech stays in speech state after trigger`() {
        val vad = VoiceActivityDetector()
        val speechFrames = feedAll(vad, syllabicFrames(1_000, 2_000)) // ~30 s
        // 3-frame onset delay (trigger fires ON the 3rd consecutive speech frame), then
        // EVERY frame reads speech — no mid-speech dropouts (syllabic variation keeps the
        // steady-noise rejection idle).
        assertEquals(1_000 - VoiceActivityDetector.DEFAULT_SPEECH_FRAMES + 1, speechFrames)
    }

    @Test
    fun `intra-sentence pauses up to 1_5s do not cause audio loss and speech resumes`() {
        for (pauseMs in intArrayOf(500, 1_000, 1_500)) {
            val vad = VoiceActivityDetector()
            feedAll(vad, syllabicFrames(17, 2_000)) // 0.5 s speech
            val pauseFrames = pauseMs * 16 / 480 // frames of ~30 ms
            feedSilence(vad, pauseFrames)
            // VAD state may drop to SILENT during pauses ≥ the 600 ms hangover — that is
            // BY DESIGN: the VAD is not an audio gate. The pipeline streams every frame and
            // only finalizes on silenceTimeoutMs (2 s), so a 1.5 s pause never cuts words.
            val resumed = feedAll(vad, syllabicFrames(17, 2_000))
            assertTrue(
                "speech must resume after a ${pauseMs} ms pause (got $resumed speech frames)",
                resumed >= 14,
            )
        }
    }

    @Test
    fun `two sentences separated by a 2s pause segment correctly`() {
        val vad = VoiceActivityDetector()
        feedAll(vad, syllabicFrames(33, 2_000)) // sentence 1: 1 s
        feedSilence(vad, 67) // 2 s pause
        assertEquals(VoiceActivityDetector.State.SILENT, vad.state)

        vad.reset() // pipeline resets per utterance; also validates reset
        val sentence2 = feedAll(vad, syllabicFrames(33, 2_000))
        assertTrue("sentence 2 must trigger again", sentence2 >= 30)
    }

    @Test
    fun `new sentence after 2s pause without reset also triggers`() {
        val vad = VoiceActivityDetector()
        feedAll(vad, syllabicFrames(33, 2_000))
        feedSilence(vad, 67)
        assertEquals(VoiceActivityDetector.State.SILENT, vad.state)
        val second = feedAll(vad, syllabicFrames(10, 2_000))
        assertTrue("VAD must re-trigger without an explicit reset (got $second)", second >= 7)
    }

    // -------------------------------------------------------------- noise conditions

    @Test
    fun `brief noise bursts do not trigger speech`() {
        val vad = VoiceActivityDetector()
        repeat(5) {
            feedAll(vad, steadyNoiseFrames(2, 5_000)) // 2 loud frames (< trigger of 3)
            feedSilence(vad, 10)
        }
        assertEquals(VoiceActivityDetector.State.SILENT, vad.state)
    }

    @Test
    fun `steady fan noise above static speech threshold is rejected and cannot re-trigger`() {
        // Fan RMS ~500: above the static speech threshold (400). Without rejection it would
        // latch SPEECH forever; with rejection it must drop to SILENT and stay there.
        val vad = VoiceActivityDetector()
        feedAll(vad, steadyNoiseFrames(200, 500)) // ~6 s of fan noise alone
        assertEquals(
            "steady fan noise must not be classified as speech",
            VoiceActivityDetector.State.SILENT,
            vad.state,
        )
        assertTrue(
            "fan level must pin the adaptive speech threshold above the hum " +
                "(effSpeech=${vad.effectiveSpeechThreshold})",
            vad.effectiveSpeechThreshold > 500,
        )
        // More of the same fan must NOT re-trigger speech.
        feedAll(vad, steadyNoiseFrames(100, 500))
        assertEquals(VoiceActivityDetector.State.SILENT, vad.state)
    }

    @Test
    fun `noise floor adaptation raises thresholds in noisy room and keeps speech detectable`() {
        val vad = VoiceActivityDetector()
        // Noisy room: ~6 s of fan at RMS 500.
        feedAll(vad, steadyNoiseFrames(200, 500))
        assertTrue(
            "adaptive speech threshold must exceed static 400 (got ${vad.effectiveSpeechThreshold})",
            vad.effectiveSpeechThreshold > 400,
        )
        // Real (syllabic) speech at RMS 3_000 is still far above the adapted threshold.
        val speechFrames = feedAll(vad, syllabicFrames(17, 3_000))
        assertTrue("real speech must still trigger in a noisy room (got $speechFrames)", speechFrames >= 14)
    }

    @Test
    fun `speech does not pump the noise floor`() {
        val vad = VoiceActivityDetector()
        // Loud syllabic speech for 5 s — the floor must stay at its quiet starting value so
        // that a following quiet pause still reads as silence.
        feedAll(vad, syllabicFrames(166, 5_000))
        assertEquals(VoiceActivityDetector.State.SPEECH, vad.state)
        assertTrue(
            "speech must not raise the noise floor (effSilence=${vad.effectiveSilenceThreshold})",
            vad.effectiveSilenceThreshold <= VoiceActivityDetector.DEFAULT_SILENCE_THRESHOLD,
        )
    }

    // -------------------------------------------------------------- level conditions

    @Test
    fun `quiet speech at -30 dBFS still triggers`() {
        val vad = VoiceActivityDetector()
        // -30 dBFS ≈ RMS 830: above the static speech threshold 400.
        val speechFrames = feedAll(vad, syllabicFrames(17, 830))
        assertTrue("quiet speech must trigger (got $speechFrames)", speechFrames >= 14)
    }

    @Test
    fun `loud speech at -3 dBFS does not clip the detector`() {
        val vad = VoiceActivityDetector()
        // Syllabic loud speech: peaks near full scale (RMS base ~23_000 ≈ -3 dBFS).
        val speechFrames = feedAll(vad, syllabicFrames(17, 23_000))
        assertTrue("loud speech must trigger (got $speechFrames)", speechFrames >= 14)
    }

    @Test
    fun `quiet room silence never triggers speech`() {
        val vad = VoiceActivityDetector()
        feedSilence(vad, 500) // ~15 s of room tone
        assertEquals(VoiceActivityDetector.State.SILENT, vad.state)
    }
}
