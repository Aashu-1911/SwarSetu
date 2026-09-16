package app.swarsetu.stt.sravaani

/**
 * SentencePiece-piece decoder for SraVaani 0.5.
 *
 * The model's tokenizer is a 5000-piece SentencePiece vocab (verified from the official
 * `tokenizer.model`: `piece_size=5000`, `unk_id=0`, no BOS/EOS, no byte fallback, no control
 * symbols). The CTC head emits piece ids; the official decoding is SentencePiece `decode`,
 * which for this vocab is exactly:
 *
 * 1. join the pieces,
 * 2. replace U+2581 (▁, LOWER ONE EIGHTH BLOCK) with a space,
 * 3. strip the leading space.
 *
 * Verified against `SentencePieceProcessor.decode` in `scripts/sravaani/gen_android_assets.py`
 * work ("क ह म स" match on a probe sequence). The unknown id decodes to " ⁇ " exactly like the
 * official processor.
 *
 * Pieces are loaded from `assets/sravaani/tokens.txt` (generated from the official
 * `tokenizer.model`; line index == piece id).
 */
class SravaaniTokenizer(pieces: List<String>) {
    /** 0 == <unk> per the official vocab. */
    val unknownId: Int = 0

    private val pieces: Array<String> = pieces.toTypedArray()

    init {
        require(this.pieces.isNotEmpty()) { "tokenizer: empty piece table" }
        require(this.pieces[unknownId] == "<unk>") { "tokenizer: piece 0 must be <unk>" }
    }

    /** Decode CTC token ids (blanks already removed) to text. */
    fun decode(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in tokenIds) {
            when {
                id in pieces.indices -> sb.append(pieces[id])
                else -> sb.append('\u2581') // out-of-range defensive: behaves as a space
            }
        }
        var text = sb.toString().replace("\u2581", " ")
        if (text.startsWith(" ")) text = text.substring(1)
        return text
    }

    companion object {
        const val PIECE_COUNT = 5_000
        const val UNK_PIECE = "<unk>"
    }
}
