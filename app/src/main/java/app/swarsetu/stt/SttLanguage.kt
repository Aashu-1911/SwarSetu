package app.swarsetu.stt

/**
 * Supported STT languages. Each entry maps a BCP-47 code to the language's display name, the expected
 * PCM sample rate (most Indic STT models expect 16 kHz), and a hint for the model manager about which
 * model asset to load.
 *
 * The 10 target languages match PS 26173. The order is deliberate: English is last because it is the
 * fallback when no Indic model is available, not because it is less important.
 *
 * A future version may add more languages here without changing any public API — the enum is the single
 * source of truth, and [SttLanguage.supported] exposes only the ones with bundled models.
 */
enum class SttLanguage(
    /** BCP-47 code (e.g. "hi", "en-IN"). */
    val code: String,
    /** Human-readable name for UI display in native script. */
    val displayName: String,
    /**
     * Expected PCM sample rate in Hz. All target-language models use 16 kHz. Changing this per-language
     * would require a resampler; 16 kHz is the universal floor for speech recognition.
     */
    val sampleRate: Int = SAMPLE_RATE,
    /**
     * Asset directory name under `assets/stt/` where this language's model files live. A null value
     * means the language is declared but no model is bundled yet — [SttModelManager.isAvailable] will
     * return false for it.
     */
    val assetDir: String? = null,
) {
    HINDI("hi", "हिन्दी", assetDir = "stt-hi"),
    GUJARATI("gu", "ગુજરાતી", assetDir = "stt-gu"),
    MARATHI("mr", "मराठी", assetDir = "stt-mr"),
    KANNADA("kn", "ಕನ್ನಡ", assetDir = "stt-kn"),
    MALAYALAM("ml", "മലയാളം", assetDir = "stt-ml"),
    TAMIL("ta", "தமிழ்", assetDir = "stt-ta"),
    TELUGU("te", "తెలుగు", assetDir = "stt-te"),
    ODIA("or", "ଓଡ଼ିଆ", assetDir = "stt-or"),
    BENGALI("bn", "বাংলা", assetDir = "stt-bn"),
    ENGLISH("en", "English", assetDir = "stt-en"),
    ;

    val englishName: String
        get() = when (this) {
            HINDI -> "Hindi"
            GUJARATI -> "Gujarati"
            MARATHI -> "Marathi"
            KANNADA -> "Kannada"
            MALAYALAM -> "Malayalam"
            TAMIL -> "Tamil"
            TELUGU -> "Telugu"
            ODIA -> "Odia"
            BENGALI -> "Bengali"
            ENGLISH -> "English"
        }

    val nativeName: String
        get() = displayName

    val fullDisplayName: String
        get() = if (englishName.equals(nativeName, ignoreCase = true)) englishName else "$nativeName ($englishName)"

    /**
     * Returns the name of this language translated into [userLanguage] script/vernacular.
     * E.g. When [userLanguage] is HINDI, TAMIL returns "तमिल", GUJARATI returns "गुजराती", etc.
     */
    fun localizedNameIn(userLanguage: SttLanguage): String {
        return when (userLanguage) {
            HINDI -> when (this) {
                HINDI -> "हिन्दी"
                GUJARATI -> "गुजराती"
                MARATHI -> "मराठी"
                KANNADA -> "कन्नड़"
                MALAYALAM -> "मलयालम"
                TAMIL -> "तमिल"
                TELUGU -> "तेलुगु"
                ODIA -> "ओडिया"
                BENGALI -> "बंगाली"
                ENGLISH -> "अंग्रेज़ी"
            }
            GUJARATI -> when (this) {
                HINDI -> "હિન્દી"
                GUJARATI -> "ગુજરાતી"
                MARATHI -> "મરાઠી"
                KANNADA -> "કન્નડ"
                MALAYALAM -> "મલયાલમ"
                TAMIL -> "તમિલ"
                TELUGU -> "તેલુગુ"
                ODIA -> "ઓડિયા"
                BENGALI -> "બંગાળી"
                ENGLISH -> "અંગ્રેજી"
            }
            MARATHI -> when (this) {
                HINDI -> "हिंदी"
                GUJARATI -> "गुजराती"
                MARATHI -> "मराठी"
                KANNADA -> "कन्नड"
                MALAYALAM -> "मल्याळम"
                TAMIL -> "तमिळ"
                TELUGU -> "तेलुगू"
                ODIA -> "ओडिया"
                BENGALI -> "बंगाली"
                ENGLISH -> "इंग्रजी"
            }
            KANNADA -> when (this) {
                HINDI -> "ಹಿಂದಿ"
                GUJARATI -> "ಗುಜರಾತಿ"
                MARATHI -> "ಮರಾಠಿ"
                KANNADA -> "ಕನ್ನಡ"
                MALAYALAM -> "ಮಲಯಾಳಂ"
                TAMIL -> "ತಮಿಳು"
                TELUGU -> "ತೆಲುಗು"
                ODIA -> "ಒಡಿಯಾ"
                BENGALI -> "ಬಂಗಾಳಿ"
                ENGLISH -> "ಇಂಗ್ಲಿಷ್"
            }
            MALAYALAM -> when (this) {
                HINDI -> "ഹിന്ദി"
                GUJARATI -> "ഗുജറാത്തി"
                MARATHI -> "മറാഠി"
                KANNADA -> "കന്നഡ"
                MALAYALAM -> "മലയാളം"
                TAMIL -> "തമിഴ്"
                TELUGU -> "തെലുങ്ക്"
                ODIA -> "ഒഡിയ"
                BENGALI -> "ബംഗാളി"
                ENGLISH -> "ഇംഗ്ലീഷ്"
            }
            TAMIL -> when (this) {
                HINDI -> "இந்தி"
                GUJARATI -> "குஜராத்தி"
                MARATHI -> "மராத்தி"
                KANNADA -> "கன்னடம்"
                MALAYALAM -> "மலையாளம்"
                TAMIL -> "தமிழ்"
                TELUGU -> "தெலுங்கு"
                ODIA -> "ஒடியா"
                BENGALI -> "வங்காளம்"
                ENGLISH -> "ஆங்கிலம்"
            }
            TELUGU -> when (this) {
                HINDI -> "హిందీ"
                GUJARATI -> "గుజరాతీ"
                MARATHI -> "మరాఠీ"
                KANNADA -> "కన్నడ"
                MALAYALAM -> "మలయాళం"
                TAMIL -> "తమిళం"
                TELUGU -> "తెలుగు"
                ODIA -> "ఒడియా"
                BENGALI -> "బెంగాలీ"
                ENGLISH -> "ఆంగ్లం"
            }
            ODIA -> when (this) {
                HINDI -> "ହିନ୍ଦୀ"
                GUJARATI -> "ଗୁଜରାଟୀ"
                MARATHI -> "ମରାଠୀ"
                KANNADA -> "କନ୍ନଡ଼"
                MALAYALAM -> "ମାଲାୟାଲମ୍"
                TAMIL -> "ତାମିଲ୍"
                TELUGU -> "ତେଲୁଗୁ"
                ODIA -> "ଓଡ଼ିଆ"
                BENGALI -> "ବଙ୍ଗାଳୀ"
                ENGLISH -> "ଇଂରାଜୀ"
            }
            BENGALI -> when (this) {
                HINDI -> "হিন্দি"
                GUJARATI -> "গুজরাটি"
                MARATHI -> "মারাঠি"
                KANNADA -> "কন্নড়"
                MALAYALAM -> "মালয়ালম"
                TAMIL -> "তামিল"
                TELUGU -> "তেলুগু"
                ODIA -> "ওড়িয়া"
                BENGALI -> "বাংলা"
                ENGLISH -> "ইংরেজি"
            }
            ENGLISH -> englishName
        }
    }

    /**
     * Secondary description showing the native script and English name for maximum clarity.
     */
    fun subtitleIn(userLanguage: SttLanguage): String {
        val native = nativeName
        val eng = englishName
        val localized = localizedNameIn(userLanguage)

        return when {
            userLanguage == ENGLISH -> {
                if (this == ENGLISH) "" else native
            }
            this == userLanguage -> {
                eng
            }
            else -> {
                if (native.equals(localized, ignoreCase = true)) {
                    eng
                } else {
                    "$native • $eng"
                }
            }
        }
    }

    /**
     * Composite display string in context of [userLanguage].
     */
    fun fullDisplayNameIn(userLanguage: SttLanguage): String {
        val localized = localizedNameIn(userLanguage)
        val subtitle = subtitleIn(userLanguage)
        return if (subtitle.isBlank()) localized else "$localized ($subtitle)"
    }

    companion object {
        /** Universal PCM sample rate for all STT models in this project. */
        const val SAMPLE_RATE = 16_000

        /**
         * Languages that have a bundled model (non-null [assetDir]). As models are added, this set
         * grows; the UI should present only these.
         */
        val supported: Set<SttLanguage> =
            entries.filter { it.assetDir != null }.toSet()

        /**
         * Resolve a language from its BCP-47 code, enum name, or display name, or null when unknown.
         * Case-insensitive: "hi", "HI", "HINDI", "Hindi" all map to [HINDI].
         */
        fun fromCode(code: String?): SttLanguage? {
            if (code.isNullOrBlank()) return null
            val clean = code.trim().lowercase().split("-", "_").first()
            return entries.firstOrNull {
                it.code.equals(code.trim(), ignoreCase = true) ||
                    it.code.equals(clean, ignoreCase = true) ||
                    it.name.equals(code.trim(), ignoreCase = true) ||
                    it.name.equals(clean, ignoreCase = true) ||
                    it.displayName.equals(code.trim(), ignoreCase = true) ||
                    it.englishName.equals(code.trim(), ignoreCase = true) ||
                    it.englishName.equals(clean, ignoreCase = true)
            }
        }
    }
}
