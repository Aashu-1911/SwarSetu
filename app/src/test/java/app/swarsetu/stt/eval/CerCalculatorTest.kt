package app.swarsetu.stt.eval

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hand-computed CER cases at code-point granularity. */
class CerCalculatorTest {
    @Test
    fun `perfect match is cer zero`() {
        val r = CerCalculator.evaluate("नमस्ते", "नमस्ते")
        assertEquals(0.0, r.cer, 1e-12)
    }

    @Test
    fun `single wrong character is one substitution`() {
        // "राम" = र ा म (3 code points) vs "रात" = र ा त → 1 SUB / 3
        val r = CerCalculator.evaluate("राम", "रात")
        assertEquals(1, r.substitutions)
        assertEquals(3, r.referenceChars)
        assertEquals(1.0 / 3, r.cer, 1e-12)
    }

    @Test
    fun `missing matra is one deletion`() {
        // "राम" (3) vs "रम" (2): ा deleted → 1 D / 3
        val r = CerCalculator.evaluate("राम", "रम")
        assertEquals(1, r.deletions)
        assertEquals(1.0 / 3, r.cer, 1e-12)
    }

    @Test
    fun `extra matra is one insertion`() {
        // "रम" (2) vs "राम" (3): 1 I / 2
        val r = CerCalculator.evaluate("रम", "राम")
        assertEquals(1, r.insertions)
        assertEquals(1.0 / 2, r.cer, 1e-12)
    }

    @Test
    fun `whitespace does not count as a character error`() {
        // Word segmentation differs but the character stream is identical.
        val r = CerCalculator.evaluate("नमस्ते दोस्त", "नमस्ते  दोस्त ")
        assertEquals(0.0, r.cer, 1e-12)
    }

    @Test
    fun `danda punctuation stripped`() {
        val r = CerCalculator.evaluate("सत्यमेव जयते।", "सत्यमेव जयते")
        assertEquals(0.0, r.cer, 1e-12)
    }

    @Test
    fun `nfc equivalence holds at code-point level`() {
        val decomposed = "क" + "\u093C"
        val r = CerCalculator.evaluate("\u0958", decomposed)
        assertEquals(0.0, r.cer, 1e-12)
    }

    @Test
    fun `empty inputs are handled`() {
        assertEquals(0.0, CerCalculator.evaluate("", "").cer, 1e-12)
        assertEquals(1.0, CerCalculator.evaluate("", "abc").cer, 1e-12)
        assertEquals(1.0, CerCalculator.evaluate("abc", "").cer, 1e-12)
    }

    @Test
    fun `aggregate sums not means`() {
        val a = CerCalculator.evaluate("abcd", "abcd") // 0/4
        val b = CerCalculator.evaluate("ef", "ex") // 1/2
        val agg = CerCalculator.aggregate(listOf(a, b))
        assertEquals(6, agg.referenceChars)
        assertEquals(1.0 / 6, agg.cer, 1e-12)
    }

    @Test
    fun `kannada conjunct partial error counts at code-point level`() {
        // "ಅಪ್ಪ" = ಅ ಪ ್ ಪ (4 code points) vs "ಅಪ" (2) → 2 D / 4
        val r = CerCalculator.evaluate("ಅಪ್ಪ", "ಅಪ")
        assertEquals(2, r.deletions)
        assertEquals(1.0 / 2, r.cer, 1e-12)
    }
}
