package app.swarsetu.tts

import java.util.Locale

/**
 * The specific ten Indian languages required for ISRO PS 26173 Phase 1 TTS.
 */
enum class TtsLanguage(
    val locale: Locale,
    val displayName: String,
) {
    HINDI(Locale.forLanguageTag("hi-IN"), "Hindi"),
    GUJARATI(Locale.forLanguageTag("gu-IN"), "Gujarati"),
    MARATHI(Locale.forLanguageTag("mr-IN"), "Marathi"),
    KANNADA(Locale.forLanguageTag("kn-IN"), "Kannada"),
    MALAYALAM(Locale.forLanguageTag("ml-IN"), "Malayalam"),    
    TAMIL(Locale.forLanguageTag("ta-IN"), "Tamil"),
    TELUGU(Locale.forLanguageTag("te-IN"), "Telugu"),
    ODIA(Locale.forLanguageTag("or-IN"), "Odia"),
    BENGALI(Locale.forLanguageTag("bn-IN"), "Bengali"),
    ENGLISH(Locale.forLanguageTag("en-IN"), "English"),
    ;

    companion object {
        /**
         * Infers the expected [TtsLanguage] from the Unicode script of [text].
         */
        fun inferFromText(text: String): TtsLanguage? {
            for (char in text) {
                when (char.code) {
                    in 0x0A80..0x0AFF -> return GUJARATI
                    in 0x0980..0x09FF -> return BENGALI
                    in 0x0B80..0x0BFF -> return TAMIL
                    in 0x0C00..0x0C7F -> return TELUGU
                    in 0x0C80..0x0CFF -> return KANNADA
                    in 0x0D00..0x0D7F -> return MALAYALAM
                    in 0x0B00..0x0B7F -> return ODIA
                    in 0x0900..0x097F -> return HINDI
                }
            }
            if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                return ENGLISH
            }
            return null
        }

        /**
         * Resolves a [TtsLanguage] from a language code (e.g., "hi", "en-IN", "english"),
         * with fallback or cross-validation against [textFallback].
         */
        fun fromLanguageCode(code: String?, textFallback: String? = null): TtsLanguage? {
            val scriptInferred = textFallback?.let { inferFromText(it) }
            if (code.isNullOrBlank()) return scriptInferred

            val raw = code.trim()
            val clean = raw.lowercase().split("-", "_").first()
            val matched = entries.firstOrNull {
                it.locale.language.equals(clean, ignoreCase = true) ||
                    it.name.equals(clean, ignoreCase = true) ||
                    it.name.equals(raw, ignoreCase = true) ||
                    it.displayName.equals(raw, ignoreCase = true) ||
                    it.displayName.equals(clean, ignoreCase = true)
            }

            return matched ?: scriptInferred
        }
    }
}
