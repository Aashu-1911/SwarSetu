package app.swarsetu.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [SttLanguage] — the language enum and its helpers. */
class SttLanguageTest {
    @Test
    fun `all ten target languages are declared`() {
        assertEquals(10, SttLanguage.entries.size)
    }

    @Test
    fun `all target languages have asset directories`() {
        // Every language must have a non-null assetDir — models are bundled per-language
        SttLanguage.entries.forEach { lang ->
            assertNotNull(
                "Language ${lang.code} must have an assetDir",
                lang.assetDir,
            )
        }
    }

    @Test
    fun `all languages use 16 kHz sample rate`() {
        SttLanguage.entries.forEach { lang ->
            assertEquals(
                "Language ${lang.code} must use 16 kHz",
                16_000,
                lang.sampleRate,
            )
        }
    }

    @Test
    fun `fromCode is case-insensitive`() {
        assertEquals(SttLanguage.HINDI, SttLanguage.fromCode("hi"))
        assertEquals(SttLanguage.HINDI, SttLanguage.fromCode("HI"))
        assertEquals(SttLanguage.HINDI, SttLanguage.fromCode("Hi"))
        assertEquals(SttLanguage.ENGLISH, SttLanguage.fromCode("en"))
        assertEquals(SttLanguage.ENGLISH, SttLanguage.fromCode("EN"))
    }

    @Test
    fun `fromCode returns null for unknown code`() {
        assertNull(SttLanguage.fromCode("xx"))
        assertNull(SttLanguage.fromCode(""))
        assertNull(SttLanguage.fromCode("fr"))
    }

    @Test
    fun `supported set is non-empty`() {
        assertTrue(SttLanguage.supported.isNotEmpty())
    }

    @Test
    fun `displayName is non-blank for all languages`() {
        SttLanguage.entries.forEach { lang ->
            assertTrue(
                "Language ${lang.code} must have a non-blank displayName",
                lang.displayName.isNotBlank(),
            )
        }
    }

    @Test
    fun `sample rate constant is 16000`() {
        assertEquals(16_000, SttLanguage.SAMPLE_RATE)
    }

    @Test
    fun `English is included in entries`() {
        assertNotNull(SttLanguage.fromCode("en"))
        assertEquals("English", SttLanguage.ENGLISH.displayName)
    }

    @Test
    fun `Hindi is included in entries`() {
        assertNotNull(SttLanguage.fromCode("hi"))
        assertEquals("हिन्दी", SttLanguage.HINDI.displayName)
    }

    @Test
    fun `localizedNameIn returns valid non-blank strings for all 100 language pairings`() {
        SttLanguage.entries.forEach { target ->
            SttLanguage.entries.forEach { userLang ->
                val localized = target.localizedNameIn(userLang)
                assertTrue(
                    "localizedNameIn for target ${target.name} in userLang ${userLang.name} must not be blank",
                    localized.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `localizedNameIn matches expected names in Hindi context`() {
        assertEquals("हिन्दी", SttLanguage.HINDI.localizedNameIn(SttLanguage.HINDI))
        assertEquals("गुजराती", SttLanguage.GUJARATI.localizedNameIn(SttLanguage.HINDI))
        assertEquals("तमिल", SttLanguage.TAMIL.localizedNameIn(SttLanguage.HINDI))
        assertEquals("तेलुगु", SttLanguage.TELUGU.localizedNameIn(SttLanguage.HINDI))
        assertEquals("अंग्रेज़ी", SttLanguage.ENGLISH.localizedNameIn(SttLanguage.HINDI))
    }

    @Test
    fun `localizedNameIn matches expected names in Gujarati context`() {
        assertEquals("હિન્દી", SttLanguage.HINDI.localizedNameIn(SttLanguage.GUJARATI))
        assertEquals("ગુજરાતી", SttLanguage.GUJARATI.localizedNameIn(SttLanguage.GUJARATI))
        assertEquals("તમિલ", SttLanguage.TAMIL.localizedNameIn(SttLanguage.GUJARATI))
        assertEquals("અંગ્રેજી", SttLanguage.ENGLISH.localizedNameIn(SttLanguage.GUJARATI))
    }

    @Test
    fun `localizedNameIn matches expected names in Tamil context`() {
        assertEquals("இந்தி", SttLanguage.HINDI.localizedNameIn(SttLanguage.TAMIL))
        assertEquals("தமிழ்", SttLanguage.TAMIL.localizedNameIn(SttLanguage.TAMIL))
        assertEquals("ஆங்கிலம்", SttLanguage.ENGLISH.localizedNameIn(SttLanguage.TAMIL))
    }

    @Test
    fun `subtitleIn provides informative subtitle`() {
        // When user is English, subtitle shows native name
        assertEquals("हिन्दी", SttLanguage.HINDI.subtitleIn(SttLanguage.ENGLISH))
        assertEquals("தமிழ்", SttLanguage.TAMIL.subtitleIn(SttLanguage.ENGLISH))
        assertEquals("", SttLanguage.ENGLISH.subtitleIn(SttLanguage.ENGLISH))

        // When user is Hindi, subtitle for Tamil shows native + english
        assertEquals("தமிழ் • Tamil", SttLanguage.TAMIL.subtitleIn(SttLanguage.HINDI))
        // When user is Hindi, subtitle for Hindi shows english name
        assertEquals("Hindi", SttLanguage.HINDI.subtitleIn(SttLanguage.HINDI))
    }

    @Test
    fun `fullDisplayName and fullDisplayNameIn are well-formed`() {
        assertEquals("हिन्दी (Hindi)", SttLanguage.HINDI.fullDisplayName)
        assertEquals("English", SttLanguage.ENGLISH.fullDisplayName)
        assertEquals("तमिल (தமிழ் • Tamil)", SttLanguage.TAMIL.fullDisplayNameIn(SttLanguage.HINDI))
    }
}
