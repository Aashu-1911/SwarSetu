package app.swarsetu.stt.sravaani

import app.swarsetu.stt.SttLanguage

/**
 * SentencePiece-piece decoder for SraVaani 0.5 with language-aware script vocabulary masking.
 *
 * The model's tokenizer is a 5000-piece SentencePiece vocab (verified from the official
 * `tokenizer.model`: `piece_size=5000`, `unk_id=0`, no BOS/EOS, no byte fallback, no control
 * symbols). The CTC / TDT head emits piece ids; the official decoding is SentencePiece `decode`,
 * which for this vocab is exactly:
 *
 * 1. join the pieces,
 * 2. replace U+2581 (▁, LOWER ONE EIGHTH BLOCK) with a space,
 * 3. strip the leading space.
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

    private enum class Script {
        DEVANAGARI, BENGALI, GUJARATI, ODIA, TAMIL, TELUGU, KANNADA, MALAYALAM, LATIN, COMMON
    }

    private fun classifyPiece(piece: String): Script {
        val clean = piece.replace("\u2581", "").trim()
        if (clean.isEmpty() || clean == UNK_PIECE) return Script.COMMON
        var hasDevanagari = false
        var hasBengali = false
        var hasGujarati = false
        var hasOdia = false
        var hasTamil = false
        var hasTelugu = false
        var hasKannada = false
        var hasMalayalam = false
        var hasLatin = false

        for (i in clean.indices) {
            val cp = clean[i].code
            when (cp) {
                in 0x0900..0x097F -> hasDevanagari = true
                in 0x0980..0x09FF -> hasBengali = true
                in 0x0A80..0x0AFF -> hasGujarati = true
                in 0x0B00..0x0B7F -> hasOdia = true
                in 0x0B80..0x0BFF -> hasTamil = true
                in 0x0C00..0x0C7F -> hasTelugu = true
                in 0x0C80..0x0CFF -> hasKannada = true
                in 0x0D00..0x0D7F -> hasMalayalam = true
                in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F -> hasLatin = true
            }
        }

        return when {
            hasDevanagari -> Script.DEVANAGARI
            hasBengali -> Script.BENGALI
            hasGujarati -> Script.GUJARATI
            hasOdia -> Script.ODIA
            hasTamil -> Script.TAMIL
            hasTelugu -> Script.TELUGU
            hasKannada -> Script.KANNADA
            hasMalayalam -> Script.MALAYALAM
            hasLatin -> Script.LATIN
            else -> Script.COMMON
        }
    }

    private val scriptMasks: Map<SttLanguage, BooleanArray> = SttLanguage.entries.associateWith { lang ->
        val maskSize = maxOf(this.pieces.size, BLANK_ID + 1)
        val mask = BooleanArray(maskSize) { false }
        if (BLANK_ID < mask.size) {
            mask[BLANK_ID] = true // Blank is always allowed
        }
        for (i in this.pieces.indices) {
            val script = classifyPiece(this.pieces[i])
            val allowed = when (lang) {
                SttLanguage.ENGLISH -> script == Script.LATIN || script == Script.COMMON
                SttLanguage.HINDI, SttLanguage.MARATHI -> script == Script.DEVANAGARI || script == Script.LATIN || script == Script.COMMON
                SttLanguage.BENGALI -> script == Script.BENGALI || script == Script.LATIN || script == Script.COMMON
                SttLanguage.GUJARATI -> script == Script.GUJARATI || script == Script.LATIN || script == Script.COMMON
                SttLanguage.ODIA -> script == Script.ODIA || script == Script.LATIN || script == Script.COMMON
                SttLanguage.TAMIL -> script == Script.TAMIL || script == Script.LATIN || script == Script.COMMON
                SttLanguage.TELUGU -> script == Script.TELUGU || script == Script.LATIN || script == Script.COMMON
                SttLanguage.KANNADA -> script == Script.KANNADA || script == Script.LATIN || script == Script.COMMON
                SttLanguage.MALAYALAM -> script == Script.MALAYALAM || script == Script.LATIN || script == Script.COMMON
            }
            mask[i] = allowed
        }
        mask
    }

    /** Returns the precomputed boolean mask of allowed token IDs for [language]. */
    fun getAllowedMask(language: SttLanguage?): BooleanArray? =
        if (language != null) scriptMasks[language] else null

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
        const val BLANK_ID = 5_000
        const val UNK_PIECE = "<unk>"
    }
}
