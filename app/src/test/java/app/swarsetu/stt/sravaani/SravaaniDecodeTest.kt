package app.swarsetu.stt.sravaani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SraVaani 1.0 tokenizer, tables, and featurizer extraction.
 */
class SravaaniDecodeTest {

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
        val pieces = listOf("<unk>", "\u2581abc", "de", "\u2581fg")
        val tok = SravaaniTokenizer(pieces)
        assertEquals("abcde fg", tok.decode(listOf(1, 2, 3)))
    }

    @Test
    fun `tokenizer formats English acronyms and pronouns properly`() {
        val pieces = listOf("<unk>", "\u2581i", "\u2581am", "\u2581with", "\u2581ndrf")
        val tok = SravaaniTokenizer(pieces)
        assertEquals("I am with NDRF", tok.decode(listOf(1, 2, 3, 4)))
    }

    // ------------------------------------------------------------ featurizer

    private fun fakeTables05(): SravaaniTables {
        val nFft = 2
        val win = 2
        val hop = 1
        val melBins = 2
        val fbBins = nFft / 2 + 1
        val window = FloatArray(win) { 1f }
        val melFb = FloatArray(melBins * fbBins) { 0.5f }
        val buf = java.nio.ByteBuffer.allocate(7 * 4 + window.size * 4 + melFb.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SravaaniTables.MAGIC_05)
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

    private fun fakeTables10(): SravaaniTables {
        val nFft = 2
        val win = 2
        val hop = 1
        val melBins = 2
        val fbBins = nFft / 2 + 1
        val window = FloatArray(win) { 1f }
        val melFb = FloatArray(melBins * fbBins) { 0.5f }
        val buf = java.nio.ByteBuffer.allocate(5 * 4 + 4 * 4 + window.size * 4 + melFb.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(SravaaniTables.MAGIC_10)
        buf.putInt(nFft)
        buf.putInt(hop)
        buf.putInt(win)
        buf.putInt(melBins)
        // 4 float scalars: preemph, guard, pad_val, constant
        buf.putFloat(0.97f)
        buf.putFloat(1e-5f)
        buf.putFloat(0.0f)
        buf.putFloat(1e-5f)
        window.forEach(buf::putFloat)
        melFb.forEach(buf::putFloat)
        return SravaaniTables.read(buf.array().inputStream())
    }

    @Test
    fun `featurizer extractUtterance produces finite normalized frames with 0_5 tables`() {
        val tables = fakeTables05()
        val featurizer = SravaaniFeaturizer(tables)
        val pcm = ShortArray(3200) { 1000 }
        val feats = featurizer.extractUtterance(pcm)

        assertTrue("Expected produced frames > 0", feats.numFrames > 0)
        assertEquals(tables.melBins * feats.numFrames, feats.feats.size)

        for (f in feats.feats) {
            assertFalse("Features must not be NaN", f.isNaN())
            assertFalse("Features must not be Infinite", f.isInfinite())
        }
    }

    @Test
    fun `featurizer extractUtterance produces finite normalized frames with 1_0 tables`() {
        val tables = fakeTables10()
        val featurizer = SravaaniFeaturizer(tables)
        val pcm = ShortArray(3200) { 1000 }
        val feats = featurizer.extractUtterance(pcm)

        assertTrue("Expected produced frames > 0", feats.numFrames > 0)
        assertEquals(tables.melBins * feats.numFrames, feats.feats.size)

        for (f in feats.feats) {
            assertFalse("Features must not be NaN", f.isNaN())
            assertFalse("Features must not be Infinite", f.isInfinite())
        }
    }
}
