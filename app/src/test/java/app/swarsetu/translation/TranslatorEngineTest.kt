package app.swarsetu.translation

import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranslatorEngineTest {
    private lateinit var engine: TranslatorEngine

    @Before
    fun setUp() {
        engine = TranslatorEngine()
    }

    @Test
    fun `detectLanguageFromScript detects scripts across all Indian language families`() {
        assertEquals(TranslateLanguage.HINDI, engine.detectLanguageFromScript("नमस्ते भारत"))
        assertEquals(TranslateLanguage.GUJARATI, engine.detectLanguageFromScript("કેમ છો અમદાવાદ"))
        assertEquals(TranslateLanguage.BENGALI, engine.detectLanguageFromScript("নমস্কার কলকাতা"))
        assertEquals(TranslateLanguage.TAMIL, engine.detectLanguageFromScript("வணக்கம் சென்னை"))
        assertEquals(TranslateLanguage.TELUGU, engine.detectLanguageFromScript("నమస్కారం హైదరాబాద్"))
        assertEquals(TranslateLanguage.KANNADA, engine.detectLanguageFromScript("ನಮಸ್ಕಾರ ಬೆಂಗಳೂರು"))
        assertEquals(TranslateLanguage.ENGLISH, engine.detectLanguageFromScript("Hello New Delhi"))
    }

    @Test
    fun `normalizeToLanguageTag normalizes all 10 target languages correctly`() {
        assertEquals(TranslateLanguage.ENGLISH, engine.normalizeToLanguageTag("en"))
        assertEquals(TranslateLanguage.ENGLISH, engine.normalizeToLanguageTag("english"))
        assertEquals(TranslateLanguage.HINDI, engine.normalizeToLanguageTag("hi"))
        assertEquals(TranslateLanguage.HINDI, engine.normalizeToLanguageTag("hindi"))
        assertEquals(TranslateLanguage.GUJARATI, engine.normalizeToLanguageTag("gu"))
        assertEquals(TranslateLanguage.MARATHI, engine.normalizeToLanguageTag("mr"))
        assertEquals(TranslateLanguage.TAMIL, engine.normalizeToLanguageTag("ta"))
        assertEquals(TranslateLanguage.TELUGU, engine.normalizeToLanguageTag("te"))
        assertEquals(TranslateLanguage.BENGALI, engine.normalizeToLanguageTag("bn"))
        assertEquals(TranslateLanguage.KANNADA, engine.normalizeToLanguageTag("kn"))
        assertEquals(TranslateLanguage.HINDI, engine.normalizeToLanguageTag("ml"))
        assertEquals(TranslateLanguage.HINDI, engine.normalizeToLanguageTag("or"))
    }

    @Test
    fun `translate handles blank text and identical languages without remote calls`() = runBlocking {
        assertEquals("", engine.translate("", "en", "hi"))
        assertEquals("   ", engine.translate("   ", "en", "hi"))
        assertEquals("Hello world", engine.translate("Hello world", "en", "en"))
        assertEquals("नमस्ते भारत", engine.translate("नमस्ते भारत", "hi", "hi"))
    }

    @Test
    fun `maskEntities shields multi-word names and introduction patterns`() {
        // Reflection test on private maskEntities via full translate fallback or direct reflection
        val method = TranslatorEngine::class.java.getDeclaredMethod("maskEntities", String::class.java, List::class.java)
        method.isAccessible = true

        val input1 = "My name is Ashish Kumar and I am going to New Delhi."
        val res1 = method.invoke(engine, input1, emptyList<String>())
        assertNotNull(res1)
        val maskedField = res1.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val replacementsField = res1.javaClass.getDeclaredField("replacements").apply { isAccessible = true }

        val maskedText1 = maskedField.get(res1) as String
        @Suppress("UNCHECKED_CAST")
        val replacements1 = replacementsField.get(res1) as Map<String, String>

        assertTrue("Should shield multi-word name Ashish Kumar", replacements1.values.any { it.contains("Ashish Kumar") })
        assertTrue("Should shield location New Delhi", replacements1.values.any { it.contains("New Delhi") })
        assertTrue("Masked text should contain placeholder tags", maskedText1.contains("__N0__"))
    }

    @Test
    fun `maskEntities shields honorifics and Indian self-introduction patterns`() {
        val method = TranslatorEngine::class.java.getDeclaredMethod("maskEntities", String::class.java, List::class.java)
        method.isAccessible = true

        val input = "मेरा नाम राहुल शर्मा है और डॉ. वर्मा मेरे साथ हैं।"
        val res = method.invoke(engine, input, emptyList<String>())
        assertNotNull(res)

        val replacementsField = res.javaClass.getDeclaredField("replacements").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val replacements = replacementsField.get(res) as Map<String, String>

        assertTrue("Should extract राहुल शर्मा from introduction pattern", replacements.values.any { it.contains("राहुल शर्मा") || it.contains("राहुल") })
        assertTrue("Should extract डॉ. वर्मा from honorific pattern", replacements.values.any { it.contains("वर्मा") })
    }

    @Test
    fun `unmaskEntities restores tags across Indic and English variations`() {
        val method = TranslatorEngine::class.java.getDeclaredMethod("unmaskEntities", String::class.java, Map::class.java)
        method.isAccessible = true

        val tagMap = mapOf("0" to "Ashish Kumar", "1" to "New Delhi", "2" to "ISRO")

        // Exact match
        val text1 = "Hello __N0__, welcome to __N1__ with __N2__."
        val unmasked1 = method.invoke(engine, text1, tagMap) as String
        assertEquals("Hello Ashish Kumar, welcome to New Delhi with ISRO.", unmasked1)

        // Spaced and punctuation variation
        val text2 = "Hello __ N 0 __, welcome to __ N 1 __ with [N2]."
        val unmasked2 = method.invoke(engine, text2, tagMap) as String
        assertEquals("Hello Ashish Kumar, welcome to New Delhi with ISRO.", unmasked2)

        // Devanagari transliteration variation
        val text3 = "नमस्ते एन 0, आपका एन 1 में स्वागत है।"
        val unmasked3 = method.invoke(engine, text3, tagMap) as String
        assertEquals("नमस्ते Ashish Kumar, आपका New Delhi में स्वागत है।", unmasked3)

        // Gujarati transliteration variation
        val text4 = "નમસ્તે એન 0, તમારું એન 1 માં સ્વાગત છે."
        val unmasked4 = method.invoke(engine, text4, tagMap) as String
        assertEquals("નમસ્તે Ashish Kumar, તમારું New Delhi માં સ્વાગત છે.", unmasked4)
    }

    @Test
    fun `maskEntities handles lowercase voice STT sentences without swallowing grammar`() {
        val method = TranslatorEngine::class.java.getDeclaredMethod("maskEntities", String::class.java, List::class.java)
        method.isAccessible = true

        val input = "my name is aashu and i am in delhi near rajiv chowk"
        val res = method.invoke(engine, input, emptyList<String>())
        assertNotNull(res)

        val maskedField = res.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val replacementsField = res.javaClass.getDeclaredField("replacements").apply { isAccessible = true }

        val maskedText = maskedField.get(res) as String
        @Suppress("UNCHECKED_CAST")
        val replacements = replacementsField.get(res) as Map<String, String>

        assertTrue("Should extract aashu from intro pattern", replacements.values.any { it.equals("aashu", ignoreCase = true) })
        assertTrue("Should extract delhi from critical entities", replacements.values.any { it.equals("delhi", ignoreCase = true) })
        assertTrue("Should extract rajiv chowk from locations", replacements.values.any { it.equals("rajiv chowk", ignoreCase = true) })
        
        // Ensure conjunctions and prepositions are preserved in maskedText
        assertTrue("Conjunction 'and' must be preserved in maskedText", maskedText.contains(" and "))
        assertTrue("Pronoun 'i am' must be preserved in maskedText", maskedText.contains(" i am ") || maskedText.contains(" I am "))
        assertTrue("Preposition 'in' must be preserved in maskedText", maskedText.contains(" in "))
        assertTrue("Preposition 'near' must be preserved in maskedText", maskedText.contains(" near "))
    }

    @Test
    fun `maskEntities handles action verbs and prepositions with proper nouns`() {
        val method = TranslatorEngine::class.java.getDeclaredMethod("maskEntities", String::class.java, List::class.java)
        method.isAccessible = true

        val input = "Please call Rohit and meet Priya at AIIMS"
        val res = method.invoke(engine, input, emptyList<String>())
        assertNotNull(res)

        val replacementsField = res.javaClass.getDeclaredField("replacements").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val replacements = replacementsField.get(res) as Map<String, String>

        assertTrue("Should extract Rohit from call action", replacements.values.any { it.equals("Rohit", ignoreCase = true) })
        assertTrue("Should extract Priya from meet action", replacements.values.any { it.equals("Priya", ignoreCase = true) })
        assertTrue("Should extract AIIMS from critical entities", replacements.values.any { it.equals("AIIMS", ignoreCase = true) })
    }
}
