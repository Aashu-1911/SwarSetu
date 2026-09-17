package app.swarsetu.stt.eval

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand-computed WER cases: every expected S/D/I below is derivable by inspection. These pin
 * the metric — a wrong WER implementation silently corrupts every benchmark decision.
 */
class WerCalculatorTest {
    @Test
    fun `perfect match is wer zero`() {
        val r = WerCalculator.evaluate("the quick brown fox", "the quick brown fox")
        assertEquals(0, r.substitutions)
        assertEquals(0, r.deletions)
        assertEquals(0, r.insertions)
        assertEquals(0.0, r.wer, 1e-12)
    }

    @Test
    fun `pure substitution counts against N`() {
        // "the cat sat" vs "the bat sat": 1 SUB / N=3 → 0.333
        val r = WerCalculator.evaluate("the cat sat", "the bat sat")
        assertEquals(1, r.substitutions)
        assertEquals(0, r.deletions)
        assertEquals(0, r.insertions)
        assertEquals(1.0 / 3, r.wer, 1e-12)
    }

    @Test
    fun `pure deletion`() {
        // ref 4 words, hyp 3: 1 DEL → 0.25
        val r = WerCalculator.evaluate("a b c d", "a b c")
        assertEquals(1, r.deletions)
        assertEquals(0.25, r.wer, 1e-12)
    }

    @Test
    fun `pure insertion`() {
        // ref 3 words, hyp 4: 1 INS → 0.333 (WER may exceed 1 with insertions vs N)
        val r = WerCalculator.evaluate("a b c", "a x b c")
        assertEquals(1, r.insertions)
        assertEquals(1.0 / 3, r.wer, 1e-12)
    }

    @Test
    fun `mixed case computes all three ops`() {
        // ref:  "the cat sat on the mat"  (6)
        // hyp:  "the black cat sat mat"   (5)
        // Minimal edit distance is 3. Two minimal alignments exist (S1 D2 I0 or S0 D2 I1
        // depending on backtrace tie-breaking) — the metric invariant is the TOTAL error
        // count and WER, not the op decomposition.
        val r = WerCalculator.evaluate("the cat sat on the mat", "the black cat sat mat")
        assertEquals(3, r.substitutions + r.deletions + r.insertions)
        assertEquals(3.0 / 6, r.wer, 1e-12)
    }

    @Test
    fun `wer can exceed one when insertions dominate`() {
        // ref 1 word, hyp 3 unrelated words: 1 SUB? No — 2 INS + 1 SUB = 3 errors / N=1 → 3.0
        val r = WerCalculator.evaluate("hello", "a b c")
        assertEquals(3, r.substitutions + r.insertions + r.deletions)
        assertEquals(3.0, r.wer, 1e-12)
    }

    @Test
    fun `case is folded for latin`() {
        val r = WerCalculator.evaluate("The Quick Brown Fox", "the quick brown fox")
        assertEquals(0.0, r.wer, 1e-12)
    }

    @Test
    fun `punctuation is stripped including indic danda`() {
        // "नमस्ते दोस्त।" vs "नमस्ते दोस्त" — danda must not count as an error.
        val r = WerCalculator.evaluate("नमस्ते दोस्त।", "नमस्ते दोस्त")
        assertEquals(0.0, r.wer, 1e-12)
    }

    @Test
    fun `nfc normalization composes equivalent indic strings`() {
        // "क़" U+0958 (precomposed) vs "क" + U+093C (decomposed) — canonically equivalent.
        val decomposed = "क" + "\u093C"
        val r = WerCalculator.evaluate("\u0958", decomposed)
        assertEquals(0.0, r.wer, 1e-12)
    }

    @Test
    fun `missing vowel sign is a real error not normalized away`() {
        // "राम" (रा + म) vs "रम" (र + म): the aa-matra is missing → 1 SUB (or 1 D+1 I);
        // either way NOT zero.
        val r = WerCalculator.evaluate("राम", "रम")
        assertEquals(1, r.substitutions + r.deletions + r.insertions)
        assertEquals(1.0, r.wer, 1e-12)
    }

    @Test
    fun `empty reference with empty hypothesis is zero`() {
        val r = WerCalculator.evaluate("", "")
        assertEquals(0.0, r.wer, 1e-12)
    }

    @Test
    fun `empty reference with non-empty hypothesis is wer one`() {
        val r = WerCalculator.evaluate("", "words appeared")
        assertEquals(1.0, r.wer, 1e-12)
    }

    @Test
    fun `empty hypothesis against non-empty reference is wer one`() {
        val r = WerCalculator.evaluate("some words here", "")
        assertEquals(1.0, r.wer, 1e-12)
        assertEquals(3, r.referenceWords)
    }

    @Test
    fun `aggregate sums errors and denominators not means`() {
        val a = WerCalculator.evaluate("a b c d", "a b c d") // 0/4
        val b = WerCalculator.evaluate("e f", "e x") // 1/2
        val agg = WerCalculator.aggregate(listOf(a, b))
        assertEquals(1, agg.substitutions + agg.deletions + agg.insertions)
        assertEquals(6, agg.referenceWords)
        assertEquals(1.0 / 6, agg.wer, 1e-12)
    }

    @Test
    fun `number mismatch is an error not normalized`() {
        val r = WerCalculator.evaluate("मेरे पास 2 किताबें हैं", "मेरे पास 5 किताबें हैं")
        assertEquals(1, r.substitutions)
        assertEquals(1.0 / 5, r.wer, 1e-12)
    }
}
