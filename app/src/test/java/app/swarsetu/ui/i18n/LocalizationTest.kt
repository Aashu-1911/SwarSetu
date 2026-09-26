package app.swarsetu.ui.i18n

import app.swarsetu.stt.SttLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the SwarSetu hard-coded UI localization system.
 * Ensures completeness, non-emptiness, correctness of fallbacks,
 * and string formatting across all 10 supported Indian languages.
 */
class LocalizationTest {

    private val supportedLanguages = listOf(
        SttLanguage.ENGLISH,
        SttLanguage.HINDI,
        SttLanguage.MARATHI,
        SttLanguage.GUJARATI,
        SttLanguage.KANNADA,
        SttLanguage.MALAYALAM,
        SttLanguage.TAMIL,
        SttLanguage.TELUGU,
        SttLanguage.BENGALI,
        SttLanguage.ODIA,
    )

    @Test
    fun allTenLanguagesAreSupported() {
        assertEquals(10, supportedLanguages.size)
        val codes = supportedLanguages.map { it.code }.toSet()
        val expectedCodes = setOf("en", "hi", "mr", "gu", "kn", "ml", "ta", "te", "bn", "or")
        assertEquals(expectedCodes, codes)
    }

    @Test
    fun everyUiTextKeyHasTranslationInAllTenLanguages() {
        val keys = UiTextKey.entries

        for (language in supportedLanguages) {
            for (key in keys) {
                val translation = AppStrings.get(key, language)
                assertNotNull(
                    "Translation for key $key in language ${language.name} (${language.code}) must not be null",
                    translation,
                )
                assertFalse(
                    "Translation for key $key in language ${language.name} (${language.code}) must not be blank",
                    translation.isBlank(),
                )
                assertFalse(
                    "Translation for key $key in language ${language.name} (${language.code}) should not fall back to enum name",
                    translation == key.name,
                )
            }
        }
    }

    @Test
    fun fallbackWorksForUnknownLanguageCode() {
        val key = UiTextKey.APP_NAME
        val translation = AppStrings.get(key, "unknown_lang_xyz")
        val englishTranslation = AppStrings.get(key, SttLanguage.ENGLISH)
        assertEquals(englishTranslation, translation)
    }

    @Test
    fun parameterizedFormattingWorksAcrossAllLanguages() {
        val sampleNodeId = "a1b2c3d4"
        for (language in supportedLanguages) {
            val formatted = AppStrings.format(UiTextKey.PROFILE_NODE_ID_LABEL, language, sampleNodeId)
            assertTrue(
                "Formatted string for ${language.code} must contain the parameter $sampleNodeId",
                formatted.contains(sampleNodeId),
            )
        }
    }

    @Test
    fun parameterizedCountFormattingWorks() {
        val count = 5
        for (language in supportedLanguages) {
            val formatted = AppStrings.format(UiTextKey.CONNECTED_TO_NODES, language, count)
            assertTrue(
                "Formatted count string for ${language.code} must contain the count $count",
                formatted.contains(count.toString()),
            )
        }
    }

    @Test
    fun nativeScriptNamesAreAccurate() {
        assertEquals("English", SttLanguage.ENGLISH.displayName)
        assertEquals("हिन्दी", SttLanguage.HINDI.displayName)
        assertEquals("मराठी", SttLanguage.MARATHI.displayName)
        assertEquals("ગુજરાતી", SttLanguage.GUJARATI.displayName)
        assertEquals("ಕನ್ನಡ", SttLanguage.KANNADA.displayName)
        assertEquals("മലയാളം", SttLanguage.MALAYALAM.displayName)
        assertEquals("தமிழ்", SttLanguage.TAMIL.displayName)
        assertEquals("తెలుగు", SttLanguage.TELUGU.displayName)
        assertEquals("বাংলা", SttLanguage.BENGALI.displayName)
        assertEquals("ଓଡ଼ିଆ", SttLanguage.ODIA.displayName)
    }
}
