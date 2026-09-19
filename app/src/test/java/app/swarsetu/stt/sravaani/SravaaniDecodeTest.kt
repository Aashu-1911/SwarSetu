package app.swarsetu.stt.sravaani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SravaaniCtcDecoder], [SravaaniTokenizer], and [SravaaniStreamingState] bookkeeping to
 * the official semantics (see class KDocs; constants from the artifact's config.json).
 */
class SravaaniDecodeTest {

    // ------------------------------------------------------------ CTC collapse

    @Test
    fun `ctc collapses repeats and drops blanks`() {
        val dec = SravaaniCtcDecoder()
        val out = mutableListOf<Int>()
        dec.acceptFrameIds(intArrayOf(3, 3, 5000, 3, 7, 7, 7), out)
        assertEquals(listOf(3, 3, 7), out)
    }

    @Test
    fun `ctc carries prev across chunk seam - the 5-5-5-5 case`() {
        // Official docstring case: a token straddling the seam must emit ONCE.
        val dec = SravaaniCtcDecoder()
        val out = mutableListOf<Int>()
        dec.acceptFrameIds(intArrayOf(1, 5, 5), out)
        dec.acceptFrameIds(intArrayOf(5, 5, 9), out)
        assertEquals(listOf(1, 5, 9), out)
    }

    @Test
    fun `ctc fresh stream starts from blank so leading token survives`() {
        // prev_id is initialized to blank_id on new_stream; a leading non-blank id differs
        // from prev, so it IS emitted.
        val dec = SravaaniCtcDecoder()
        val out = mutableListOf<Int>()
        dec.acceptFrameIds(intArrayOf(42, 42, 5000, 42), out)
        assertEquals(listOf(42, 42), out)
    }

    @Test
    fun `ctc blank alone never emits`() {
        val dec = SravaaniCtcDecoder()
        val out = mutableListOf<Int>()
        dec.acceptFrameIds(intArrayOf(5000, 5000, 5000), out)
        assertEquals(emptyList<Int>(), out)
    }

    @Test
    fun `ctc state does not leak between streams after reset`() {
        // prev must return to blank on reset, or the first token of the next utterance
        // could be swallowed by the previous utterance's last id.
        val dec = SravaaniCtcDecoder()
        val out = mutableListOf<Int>()
        dec.acceptFrameIds(intArrayOf(7, 7), out)
        assertEquals(listOf(7), out)
        dec.reset()
        dec.acceptFrameIds(intArrayOf(7, 7), out)
        assertEquals(listOf(7, 7), out)
    }

    // ------------------------------------------------------------ tokenizer

    @Test
    fun `tokenizer joins pieces with underline-to-space and strips leading space`() {
        val pieces = listOf("<unk>", "\u2581क", "\u2581ह", "\u2581म", "\u2581स")
        val tok = SravaaniTokenizer(pieces)
        assertEquals("क ह म स", tok.decode(listOf(1, 2, 3, 4)))
        assertEquals("", tok.decode(emptyList()))
    }

    @Test
    fun `tokenizer underline marks space before piece - SP semantics`() {
        // SentencePiece '▁' prefixes a piece that begins a new word: '▁abc' + 'de' is ONE
        // word ('abcde'), '▁fg' starts a new one. (Verified against the real processor in
        // gen_android_assets.py work.)
        val pieces = listOf("<unk>", "\u2581abc", "de", "\u2581fg")
        val tok = SravaaniTokenizer(pieces)
        assertEquals("abcde fg", tok.decode(listOf(1, 2, 3)))
    }

    // ------------------------------------------------------------ streaming bookkeeping

    private fun fakeTables(): SravaaniTables {
        // Smallest legal geometry (fbBins must equal nFft/2+1). Values are irrelevant for
        // bookkeeping tests — the featurizer reads zeros for absent samples and produces
        // finite normalized values. NOTE: the featurizer's HOP (160) is a fixed constant
        // matching the real model, so sample counts below use 160-sample frame slots.
        val nFft = 2
        val win = 2
        val hop = 1
        val melBins = 2
        val fbBins = nFft / 2 + 1 // 2
        val window = FloatArray(win) { 1f }
        val melFb = FloatArray(melBins * fbBins) { 0.5f }
        val buf = java.nio.ByteBuffer.allocate(7 * 4 + window.size * 4 + melFb.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SravaaniTables.MAGIC)
        buf.putInt(SravaaniTables.VERSION)
        buf.putInt(nFft)
        buf.putInt(win)
        buf.putInt(hop)
        buf.putInt(melBins)
        buf.putInt(fbBins)
        window.forEach(buf::putFloat)
        melFb.forEach(buf::putFloat)
        return SravaaniTables.read(buf.array().inputStream())
    }

    /** A streaming state wired to a real (tiny-table) featurizer, as the engine builds it. */
    private fun newState(): SravaaniStreamingState =
        SravaaniStreamingState(melBins = 2).also {
            it.attach(SravaaniFeaturizer(fakeTables()))
        }

    @Test
    fun `streaming state requires full chunk in live mode`() {
        val state = newState()
        // 111 frames worth of audio -> no chunk (needs 112).
        repeat(111 * 160) { state.pushPcm(FloatArray(1) { 0.1f }, 1) }
        assertEquals(false, state.hasChunkReady())
        // One more frame slot (160 samples) completes frame 111 -> 112 pending -> chunk.
        state.pushPcm(FloatArray(160) { 0.1f }, 160)
        assertEquals(true, state.hasChunkReady())
    }

    @Test
    fun `finalize processes a full chunk first then flushes the short tail`() {
        val state = newState()
        // 130 frames worth of audio, none drained yet (finalize with >= 1 full chunk pending).
        repeat(130 * 160) { state.pushPcm(FloatArray(1) { 0.2f }, 1) }
        state.beginFinalize()
        // Official semantics: full chunks run first.
        assertEquals(true, state.hasChunkReady())
        val out = FloatArray(SravaaniStreamingState.CHUNK_FRAMES * 2)
        assertEquals(SravaaniStreamingState.CHUNK_FRAMES, state.assembleChunk(out))
        state.advanceChunk()
        // Tail: 18 leftover frames + 9 cache = 27.
        assertEquals(true, state.hasChunkReady())
        assertEquals(27, state.assembleChunk(out))
        state.advanceChunk()
        assertEquals(false, state.hasChunkReady())
    }

    @Test
    fun `step-0 chunk left-pads the pre-encode cache with pad value`() {
        val state = newState()
        repeat(112 * 160) { state.pushPcm(FloatArray(1) { 0.3f }, 1) }
        val out = FloatArray(SravaaniStreamingState.CHUNK_FRAMES * 2)
        val frames = state.assembleChunk(out)
        assertEquals(SravaaniStreamingState.CHUNK_FRAMES, frames)
        // First 9 rows (cache) must be exactly pad_value 0.0 on a fresh stream.
        for (r in 0 until 9) {
            for (m in 0 until 2) {
                assertEquals(0.0f, out[r * 2 + m], 0f)
            }
        }
        // And rows 9.. must be finite normalized values.
        for (i in 9 * 2 until frames * 2) {
            assertEquals(false, out[i].isNaN())
        }
    }

    @Test
    fun `chunk-1 pre-encode cache equals previous chunk's tail frames`() {
        val state = newState()
        repeat(224 * 160) { state.pushPcm(FloatArray(1) { 0.4f }, 1) }
        val out = FloatArray(SravaaniStreamingState.CHUNK_FRAMES * 2)
        assertEquals(SravaaniStreamingState.CHUNK_FRAMES, state.assembleChunk(out))
        val chunk0Tail = out.copyOfRange(
            (SravaaniStreamingState.CHUNK_FRAMES - 9) * 2,
            SravaaniStreamingState.CHUNK_FRAMES * 2,
        )
        state.advanceChunk()
        assertEquals(SravaaniStreamingState.CHUNK_FRAMES, state.assembleChunk(out))
        // Cache rows of chunk 1 == last 9 rows of chunk 0 (frames 103..112 re-sliced).
        for (i in 0 until 9 * 2) {
            assertEquals(chunk0Tail[i], out[i], 0f)
        }
    }

    @Test
    fun `reset restores a fresh stream`() {
        val state = newState()
        repeat(112 * 160) { state.pushPcm(FloatArray(1) { 0.5f }, 1) }
        assertTrue(state.hasChunkReady())
        state.beginFinalize()
        state.markFinalized()
        state.reset()
        assertEquals(false, state.hasChunkReady())
        assertEquals(0L, state.samplesPushed)
        assertEquals(false, state.finished)
        assertEquals(false, state.finalizing)
        assertEquals(emptyList<Int>(), state.tokens)
    }
}
