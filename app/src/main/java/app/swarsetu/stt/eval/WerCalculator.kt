package app.swarsetu.stt.eval

import java.text.Normalizer

/**
 * Word Error Rate = (Substitutions + Deletions + Insertions) / N, where N is the number of
 * words in the REFERENCE. This is the genuine ASR accuracy metric — "transcript is non-empty"
 * is never accuracy.
 *
 * **Normalization** (applied identically to hypothesis and reference, in order):
 * 1. Unicode NFC normalization (canonical composition — Indic vowel signs must compose
 *    identically in both strings before any comparison),
 * 2. casefold (locale-invariant, for Latin),
 * 3. tokenize on whitespace,
 * 4. strip surrounding punctuation from each token (Indic danda `।`/`॥` included),
 * 5. drop tokens that become empty.
 *
 * Numbers are NOT normalized (that would hide real transcription errors).
 */
object WerCalculator {
    /** One alignment operation with its position. */
    public enum class Op {
        /** Word matches the reference. */
        MATCH,

        /** Hypothesis word substituted for the reference word. */
        SUB,

        /** Reference word missing from the hypothesis. */
        DEL,

        /** Extra hypothesis word not in the reference. */
        INS,
    }

    /** Detailed result of one comparison. */
    data class Result(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        /** Reference word count after normalization (the WER denominator N). */
        val referenceWords: Int,
        val hypothesisWords: Int,
        val wer: Double,
    )

    /** Compare a hypothesis transcript against a reference transcript. */
    fun evaluate(reference: String, hypothesis: String): Result {
        val ref = normalizeToTokens(reference)
        val hyp = normalizeToTokens(hypothesis)
        val alignment = align(ref, hyp)

        var sub = 0
        var del = 0
        var ins = 0
        for (op in alignment) {
            when (op) {
                Op.SUB -> sub++
                Op.DEL -> del++
                Op.INS -> ins++
                Op.MATCH -> {}
            }
        }
        val n = ref.size
        val wer = if (n == 0) (if (hyp.isEmpty()) 0.0 else 1.0) else (sub + del + ins).toDouble() / n
        return Result(sub, del, ins, n, hyp.size, wer)
    }

    /** WER of a single pair — convenience for batch evaluators. */
    fun wer(reference: String, hypothesis: String): Double = evaluate(reference, hypothesis).wer

    /** Aggregate WER across utterances: sum(S+D+I) / sum(N) — NEVER the mean of per-utterance WERs. */
    fun aggregate(results: List<Result>): Result =
        Result(
            substitutions = results.sumOf { it.substitutions },
            deletions = results.sumOf { it.deletions },
            insertions = results.sumOf { it.insertions },
            referenceWords = results.sumOf { it.referenceWords },
            hypothesisWords = results.sumOf { it.hypothesisWords },
            wer = 0.0,
        ).let { r ->
            val den = r.referenceWords
            r.copy(wer = if (den == 0) Double.NaN else (r.substitutions + r.deletions + r.insertions).toDouble() / den)
        }

    /**
     * Minimal Levenshtein alignment over word tokens. Standard DP; backtrace produces the
     * operation list (MATCH/SUB/DEL/INS) per reference position.
     */
    private fun align(ref: List<String>, hyp: List<String>): List<Op> {
        val n = ref.size
        val m = hyp.size
        if (n == 0) return List(m) { Op.INS }
        if (m == 0) return List(n) { Op.DEL }

        // dp[i][j] = edit distance between ref[0..i) and hyp[0..j)
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

        // Backtrace: collect ops in forward order.
        val ops = mutableListOf<Op>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] && dp[i][j] == dp[i - 1][j - 1] -> {
                    ops.add(Op.MATCH)
                    i--
                    j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    ops.add(Op.SUB)
                    i--
                    j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    ops.add(Op.DEL)
                    i--
                }
                else -> {
                    ops.add(Op.INS)
                    j--
                }
            }
        }
        ops.reverse()
        return ops
    }

    /** NFC → casefold → whitespace tokens → punctuation-stripped, non-empty. */
    internal fun normalizeToTokens(text: String): List<String> {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        return nfc.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { token -> token.trim { c -> c.isWhitespace() || c in PUNCTUATION_CHARS } }
            .filter { it.isNotEmpty() }
    }

    /** Characters stripped from token edges (ASCII punctuation + Indic danda etc.). */
    private val PUNCTUATION_CHARS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~।॥·–—…".toSet()
}
