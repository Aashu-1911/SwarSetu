package app.swarsetu.stt.sravaani

import app.swarsetu.stt.SttLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Sravaani10TdtDecoderTest {

    @Test
    fun `tdt model constants match SraVaani 1_0 architecture specification`() {
        assertEquals(5000, SravaaniTdtDecoder.VOCAB_SIZE)
        assertEquals(5000, SravaaniTdtDecoder.BLANK_ID)
        assertEquals(5001, SravaaniTdtDecoder.VOCAB_WITH_BLANK)
        assertEquals(5, SravaaniTdtDecoder.NUM_DURATIONS)
        assertEquals(5006, SravaaniTdtDecoder.TOTAL_LOGITS)
        assertEquals(640, SravaaniTdtDecoder.PRED_HIDDEN)
        assertEquals(1, SravaaniTdtDecoder.PRED_RNN_LAYERS)
        assertEquals(10, SravaaniTdtDecoder.MAX_SYMBOLS_PER_FRAME)
        assertEquals(5, SravaaniTdtDecoder.DURATIONS.size)
        assertEquals(0, SravaaniTdtDecoder.DURATIONS[0])
        assertEquals(1, SravaaniTdtDecoder.DURATIONS[1])
        assertEquals(2, SravaaniTdtDecoder.DURATIONS[2])
        assertEquals(3, SravaaniTdtDecoder.DURATIONS[3])
        assertEquals(4, SravaaniTdtDecoder.DURATIONS[4])
    }

    @Test
    fun `tokenizer script mask allows blank token and language specific scripts`() {
        val pieces = listOf(
            "<unk>",
            "\u2581नमस्ते", // Devanagari (Hindi/Marathi)
            "\u2581નમસ્તે", // Gujarati
            "\u2581வணக்கம்", // Tamil
            "\u2581hello",  // Latin (English)
        )
        val tokenizer = SravaaniTokenizer(pieces)

        // Hindi mask
        val hindiMask = tokenizer.getAllowedMask(SttLanguage.HINDI)
        assertNotNull(hindiMask)
        assertTrue("Blank token must always be allowed", hindiMask!![SravaaniTokenizer.BLANK_ID])
        assertTrue("Devanagari must be allowed for Hindi", hindiMask[1])
        assertFalse("Gujarati must not be allowed for Hindi", hindiMask[2])
        assertFalse("Tamil must not be allowed for Hindi", hindiMask[3])
        assertTrue("Latin/Common should be allowed", hindiMask[4])

        // Gujarati mask
        val gujaratiMask = tokenizer.getAllowedMask(SttLanguage.GUJARATI)
        assertNotNull(gujaratiMask)
        assertTrue(gujaratiMask!![SravaaniTokenizer.BLANK_ID])
        assertFalse(gujaratiMask[1])
        assertTrue(gujaratiMask[2])
        assertFalse(gujaratiMask[3])

        // Tamil mask
        val tamilMask = tokenizer.getAllowedMask(SttLanguage.TAMIL)
        assertNotNull(tamilMask)
        assertTrue(tamilMask!![SravaaniTokenizer.BLANK_ID])
        assertFalse(tamilMask[1])
        assertFalse(tamilMask[2])
        assertTrue(tamilMask[3])

        // English mask
        val englishMask = tokenizer.getAllowedMask(SttLanguage.ENGLISH)
        assertNotNull(englishMask)
        assertTrue(englishMask!![SravaaniTokenizer.BLANK_ID])
        assertFalse(englishMask[1])
        assertFalse(englishMask[2])
        assertFalse(englishMask[3])
        assertTrue(englishMask[4])
    }

    @Test
    fun `encoder output frames slicing works properly`() {
        val numFrames = 5
        val dModel = 1024
        val frames = FloatArray(numFrames * dModel) { it.toFloat() }
        val output = SravaaniOnnxEncoder.EncoderOutput(frames, numFrames, dModel)

        val scratch = FloatArray(dModel)
        output.getFrame(2, scratch)

        val expectedOffset = 2 * dModel
        for (d in 0 until dModel) {
            assertEquals((expectedOffset + d).toFloat(), scratch[d], 0.0001f)
        }
    }
}
