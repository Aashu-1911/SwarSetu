package app.swarsetu.stt.eval

import java.text.Normalizer

/**
 * Character Error Rate = (S + D + I) / N over CHARACTERS (code points, not UTF-16 chars —
 * Indic scripts live in the BMP so code-point counting is the honest unit for conjuncts and
 * vowel signs; grapheme clustering would be even finer but is not standardized across ICU
 * versions on all API levels, so code points are the documented, deterministic choice).
 *
 * CER complements WER for Indic scripts: agglutinative morphology makes one wrong conjunct
 * produce a "word" WER hit even when the transcript is nearly right — CER reflects that.
 *
 * Normalization mirrors [WerCalculator] (NFC, casefold, punctuation strip) but at character
 * level; whitespace is collapsed to nothing (character streams).
 */
object CerCalculator {
    data class Result(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        /** Reference code-point count after normalization (the CER denominator N). */
        val referenceChars: Int,
        val cer: Double,
    )

    fun evaluate(reference: String, hypothesis: String): Result {
        val ref = normalizeToCodePoints(reference)
        val hyp = normalizeToCodePoints(hypothesis)
        var sub = 0
        var del = 0
        var ins = 0

        val n = ref.size
        val m = hyp.size
        if (n == 0 || m == 0) {
            del = n
            ins = m
        } else {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in 0..n) dp[i][0] = i
            for (j in 0..m) dp[0][j] = j
            for (i in 1..n) {
                for (j in 1..m) {
                    dp[i][j] =
                        if (ref[i - 1] == hyp[j - 1]) {
                            dp[i - 1][j - 1]
                        } else {
                            minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1]) + 1
                        }
                }
            }
            // Backtrace counting ops.
            var i = n
            var j = m
            while (i > 0 || j > 0) {
                when {
                    i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] && dp[i][j] == dp[i - 1][j - 1] -> {
                        i--
                        j--
                    }
                    i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                        sub++
                        i--
                        j--
                    }
                    i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                        del++
                        i--
                    }
                    else -> {
                        ins++
                        j--
                    }
                }
            }
        }
        val cer = if (n == 0) (if (m == 0) 0.0 else 1.0) else (sub + del + ins).toDouble() / n
        return Result(sub, del, ins, n, cer)
    }

    fun cer(reference: String, hypothesis: String): Double = evaluate(reference, hypothesis).cer

    /** Aggregate CER: sum(S+D+I) / sum(N) — never a mean of per-utterance CERs. */
    fun aggregate(results: List<Result>): Result =
        Result(
            substitutions = results.sumOf { it.substitutions },
            deletions = results.sumOf { it.deletions },
            insertions = results.sumOf { it.insertions },
            referenceChars = results.sumOf { it.referenceChars },
            cer = 0.0,
        ).let { r ->
            val den = r.referenceChars
            r.copy(cer = if (den == 0) Double.NaN else (r.substitutions + r.deletions + r.insertions).toDouble() / den)
        }

    private fun normalizeToCodePoints(text: String): List<Int> {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
            .filter { it !in PUNCTUATION && !it.isWhitespace() }
        return nfc.map { it.code }
    }

    private val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~।॥·–—…".toSet()
}
