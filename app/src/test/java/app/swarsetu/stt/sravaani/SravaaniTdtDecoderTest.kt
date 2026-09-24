package app.swarsetu.stt.sravaani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SraVaani 1.0 TDT decoding, duration skipping, and tokenizer contracts.
 */
class SravaaniTdtDecoderTest {

    @Test
    fun `tdt decoder constants match SraVaani 1_0 config`() {
        assertEquals(5000, SravaaniTdtDecoder.VOCAB_SIZE)
        assertEquals(5000, SravaaniTdtDecoder.BLANK_ID)
        assertEquals(5001, SravaaniTdtDecoder.VOCAB_WITH_BLANK)
        assertEquals(5, SravaaniTdtDecoder.NUM_DURATIONS)
        assertEquals(5006, SravaaniTdtDecoder.TOTAL_LOGITS)
        assertEquals(640, SravaaniTdtDecoder.PRED_HIDDEN)
        assertEquals(10, SravaaniTdtDecoder.MAX_SYMBOLS_PER_FRAME)
    }

    @Test
    fun `encoder output slices frames accurately`() {
        val numFrames = 4
        val dModel = 1024
        val dummyData = FloatArray(numFrames * dModel) { i -> i.toFloat() }
        val encOut = SravaaniOnnxEncoder.EncoderOutput(dummyData, numFrames, dModel)

        val frameBuf = FloatArray(dModel)
        encOut.getFrame(2, frameBuf)

        assertEquals(2 * dModel.toFloat(), frameBuf[0], 0.001f)
        assertEquals((2 * dModel + 10).toFloat(), frameBuf[10], 0.001f)
    }

    @Test
    fun `tokenizer joins SentencePiece tokens and normalizes transcripts`() {
        val pieces = listOf("<unk>", "\u2581hello", "\u2581world", "\u2581i", "\u2581am")
        val tok = SravaaniTokenizer(pieces)

        assertEquals("hello world", tok.decode(listOf(1, 2)))
        assertEquals("I am", tok.decode(listOf(3, 4)))
    }

    @Test
    fun `tokenizer strips blank tokens and out of bound IDs gracefully`() {
        val pieces = listOf("<unk>", "\u2581test", "\u2581case")
        val tok = SravaaniTokenizer(pieces)

        assertEquals("test case", tok.decode(listOf(1, 5000, 2, 9999)))
    }
}
