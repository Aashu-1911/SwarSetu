package app.swarsetu.stt.sravaani

/**
 * CTC greedy decoder for SraVaani 0.5 — exact Kotlin port of the official
 * `_greedy_chunk_ctc` (`hf/modeling_sravaani_streaming.py`):
 *
 * ```
 * for k in ids:
 *     if k != prev and k != blank: emit k
 *     prev = k
 * ```
 *
 * `prev` is carried ACROSS chunk boundaries (`prev_id` in the official StreamState,
 * initialized to `blank_id` on `new_stream`); without it a token straddling a chunk seam
 * (...5 5 | 5 5...) is emitted twice — the official docstring calls this the CTC equivalent
 * of the prednet-state bug that breaks naive chunked RNNT.
 */
class SravaaniCtcDecoder(
    /** SentencePiece blank id (5000 for SraVaani 0.5). */
    private val blankId: Int = BLANK_ID,
) {
    /** Last argmax id seen, carried across chunks. Blank (5000) on a fresh stream. */
    private var prevId: Int = blankId

    /**
     * Feed one chunk's per-frame argmax ids; appends newly emitted token ids to [outTokens].
     */
    fun acceptFrameIds(ids: IntArray, outTokens: MutableList<Int>) {
        for (k in ids) {
            if (k != prevId && k != blankId) outTokens.add(k)
            prevId = k
        }
    }

    /** Reset per-stream state (new utterance): prev id back to blank. */
    fun reset() {
        prevId = blankId
    }

    companion object {
        /** SentencePiece blank id from the official config.json (`blank_id: 5000`). */
        const val BLANK_ID = 5000
    }
}
