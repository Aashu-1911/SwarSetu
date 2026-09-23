package app.swarsetu.translation.indictrans

/**
 * Supported languages in SwarSetu with their FLORES-200 / IndicTrans2 language tags,
 * script properties, and Unicode ranges.
 */
enum class IndicLanguage(
    val code: String,          // ISO 639-1 / short code (e.g. "hi", "en", "mr")
    val floresTag: String,     // IndicTrans2 BCP-47 tag (e.g. "hin_Deva", "eng_Latn")
    val displayName: String,   // English display name
    val nativeName: String,    // Native script name
    val scriptName: String,    // Script identifier
    val unicodeBlockStart: Int? = null,
    val unicodeBlockEnd: Int? = null,
) {
    HINDI(
        code = "hi",
        floresTag = "hin_Deva",
        displayName = "Hindi",
        nativeName = "हिन्दी",
        scriptName = "Devanagari",
        unicodeBlockStart = 0x0900,
        unicodeBlockEnd = 0x097F,
    ),
    ENGLISH(
        code = "en",
        floresTag = "eng_Latn",
        displayName = "English",
        nativeName = "English",
        scriptName = "Latin",
    ),
    MARATHI(
        code = "mr",
        floresTag = "mar_Deva",
        displayName = "Marathi",
        nativeName = "मराठी",
        scriptName = "Devanagari",
        unicodeBlockStart = 0x0900,
        unicodeBlockEnd = 0x097F,
    ),
    GUJARATI(
        code = "gu",
        floresTag = "guj_Gujr",
        displayName = "Gujarati",
        nativeName = "ગુજરાતી",
        scriptName = "Gujarati",
        unicodeBlockStart = 0x0A80,
        unicodeBlockEnd = 0x0AFF,
    ),
    TAMIL(
        code = "ta",
        floresTag = "tam_Taml",
        displayName = "Tamil",
        nativeName = "தமிழ்",
        scriptName = "Tamil",
        unicodeBlockStart = 0x0B80,
        unicodeBlockEnd = 0x0BFF,
    ),
    TELUGU(
        code = "te",
        floresTag = "tel_Telu",
        displayName = "Telugu",
        nativeName = "తెలుగు",
        scriptName = "Telugu",
        unicodeBlockStart = 0x0C00,
        unicodeBlockEnd = 0x0C7F,
    ),
    KANNADA(
        code = "kn",
        floresTag = "kan_Knda",
        displayName = "Kannada",
        nativeName = "ಕನ್ನಡ",
        scriptName = "Kannada",
        unicodeBlockStart = 0x0C80,
        unicodeBlockEnd = 0x0CFF,
    ),
    MALAYALAM(
        code = "ml",
        floresTag = "mal_Mlym",
        displayName = "Malayalam",
        nativeName = "മലയാളം",
        scriptName = "Malayalam",
        unicodeBlockStart = 0x0D00,
        unicodeBlockEnd = 0x0D7F,
    ),
    BENGALI(
        code = "bn",
        floresTag = "ben_Beng",
        displayName = "Bengali",
        nativeName = "বাংলা",
        scriptName = "Bengali",
        unicodeBlockStart = 0x0980,
        unicodeBlockEnd = 0x09FF,
    ),
    ODIA(
        code = "or",
        floresTag = "ory_Orya",
        displayName = "Odia",
        nativeName = "ଓଡ଼ିଆ",
        scriptName = "Odia",
        unicodeBlockStart = 0x0B00,
        unicodeBlockEnd = 0x0B7F,
    ),
    PUNJABI(
        code = "pa",
        floresTag = "pan_Guru",
        displayName = "Punjabi",
        nativeName = "ਪੰਜਾਬੀ",
        scriptName = "Gurmukhi",
        unicodeBlockStart = 0x0A00,
        unicodeBlockEnd = 0x0A7F,
    ),
    URDU(
        code = "ur",
        floresTag = "urd_Arab",
        displayName = "Urdu",
        nativeName = "اردو",
        scriptName = "Arabic",
        unicodeBlockStart = 0x0600,
        unicodeBlockEnd = 0x06FF,
    ),
    ASSAMESE(
        code = "as",
        floresTag = "asm_Beng",
        displayName = "Assamese",
        nativeName = "অসমীয়া",
        scriptName = "Bengali",
        unicodeBlockStart = 0x0980,
        unicodeBlockEnd = 0x09FF,
    ),
    NEPALI(
        code = "ne",
        floresTag = "npi_Deva",
        displayName = "Nepali",
        nativeName = "नेपाली",
        scriptName = "Devanagari",
        unicodeBlockStart = 0x0900,
        unicodeBlockEnd = 0x097F,
    ),
    SANSKRIT(
        code = "sa",
        floresTag = "san_Deva",
        displayName = "Sanskrit",
        nativeName = "संस्कृतम्",
        scriptName = "Devanagari",
        unicodeBlockStart = 0x0900,
        unicodeBlockEnd = 0x097F,
    );

    val isIndic: Boolean get() = this != ENGLISH

    companion object {
        private val CODE_MAP = entries.associateBy { it.code.lowercase() }
        private val FLORES_MAP = entries.associateBy { it.floresTag.lowercase() }

        fun fromCode(code: String?): IndicLanguage? {
            if (code.isNullOrBlank()) return null
            val clean = code.trim().lowercase().split("-", "_").first()
            return CODE_MAP[clean] ?: entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        }

        fun fromFloresTag(tag: String?): IndicLanguage? {
            if (tag.isNullOrBlank()) return null
            return FLORES_MAP[tag.trim().lowercase()] ?: fromCode(tag)
        }

        fun detectFromScript(text: String): IndicLanguage? {
            for (char in text) {
                val code = char.code
                when (code) {
                    in 0x0A80..0x0AFF -> return GUJARATI
                    in 0x0980..0x09FF -> return BENGALI
                    in 0x0B80..0x0BFF -> return TAMIL
                    in 0x0C00..0x0C7F -> return TELUGU
                    in 0x0C80..0x0CFF -> return KANNADA
                    in 0x0D00..0x0D7F -> return MALAYALAM
                    in 0x0B00..0x0B7F -> return ODIA
                    in 0x0A00..0x0A7F -> return PUNJABI
                    in 0x0600..0x06FF -> return URDU
                    in 0x0900..0x097F -> return HINDI // Devanagari default
                }
            }
            if (text.any { it in 'a'..'z' || it in 'A'..'Z' } && text.none { it.code in 0x0900..0x0D7F }) {
                return ENGLISH
            }
            return null
        }

        /**
         * Transliterates between Devanagari and any Brahmic Indic script via direct Unicode offset mapping.
         * The Unicode Standard preserves character positions across Indic blocks (offset base: 0x0900 for Devanagari).
         */
        fun transliterateIndic(text: String, src: IndicLanguage, tgt: IndicLanguage): String {
            if (src == tgt || src.unicodeBlockStart == null || tgt.unicodeBlockStart == null) return text
            val srcBase = src.unicodeBlockStart
            val tgtBase = tgt.unicodeBlockStart
            val offset = tgtBase - srcBase

            val sb = java.lang.StringBuilder(text.length)
            for (char in text) {
                val code = char.code
                if (code in srcBase..(srcBase + 0x7F)) {
                    val targetChar = (code + offset).toChar()
                    sb.append(targetChar)
                } else {
                    sb.append(char)
                }
            }
            return sb.toString()
        }
    }
}
