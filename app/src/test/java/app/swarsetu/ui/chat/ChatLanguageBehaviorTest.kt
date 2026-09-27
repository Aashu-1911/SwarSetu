package app.swarsetu.ui.chat

import app.swarsetu.data.message.MessageEntity
import app.swarsetu.mesh.crypto.MessageContent
import app.swarsetu.stt.SttLanguage
import app.swarsetu.ui.i18n.AppStrings
import app.swarsetu.ui.i18n.UiTextKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLanguageBehaviorTest {

    @Test
    fun `test SwarSetu app name remains exact English across all 10 languages`() {
        for (language in SttLanguage.entries) {
            val appName = AppStrings.get(UiTextKey.APP_NAME, language)
            assertEquals("SwarSetu must be in English for ${language.displayName}", "SwarSetu", appName)
        }
    }

    @Test
    fun `test SwarSetu brand identifier is not transliterated in any language string`() {
        for (language in SttLanguage.entries) {
            val shareApp = AppStrings.get(UiTextKey.SHARE_APP, language)
            assertTrue("SHARE_APP for ${language.displayName} must contain English 'SwarSetu'", shareApp.contains("SwarSetu"))

            val hint = AppStrings.get(UiTextKey.CHAT_MESSAGE_HINT, language)
            assertTrue("CHAT_MESSAGE_HINT for ${language.displayName} must contain English 'SwarSetu'", hint.contains("SwarSetu"))

            val welcomeTitle = AppStrings.get(UiTextKey.ONBOARDING_WELCOME_TITLE, language)
            assertTrue("ONBOARDING_WELCOME_TITLE for ${language.displayName} must contain English 'SwarSetu'", welcomeTitle.contains("SwarSetu"))

            val welcomeDesc = AppStrings.get(UiTextKey.ONBOARDING_WELCOME_DESC, language)
            assertTrue("ONBOARDING_WELCOME_DESC for ${language.displayName} must contain English 'SwarSetu'", welcomeDesc.contains("SwarSetu"))
        }
    }

    @Test
    fun `test default preferred language is English`() {
        assertEquals("Default language code must be 'en'", "en", SttLanguage.ENGLISH.code)
        val defaultLang = SttLanguage.fromCode("en")
        assertEquals(SttLanguage.ENGLISH, defaultLang)
    }

    @Test
    fun `test sender bubble displays original approved text and receiver displays translated text`() {
        val originalStt = "I need help near the hospital"
        val translatedText = "मुझे अस्पताल के पास मदद चाहिए"

        // Sender local entity
        val senderEntity = MessageEntity(
            id = "msg-1",
            senderId = "node-sender",
            recipientId = "node-receiver",
            conversationId = "conv-1",
            body = originalStt,
            sentAt = 1000L,
            received = false,
            sourceText = originalStt,
            translatedText = translatedText,
            sourceLanguage = "en",
            targetLanguage = "hi",
            messageType = MessageEntity.TYPE_NORMAL_TEXT,
        )

        // Sender UI representation
        val senderDisplayText = senderEntity.sourceText ?: senderEntity.body
        assertEquals("Sender must see original text", originalStt, senderDisplayText)
        assertFalse("Sender must not see translated text in their bubble", senderDisplayText == translatedText)

        // Wire payload delivered to receiver
        val wirePayload = MessageContent(
            body = translatedText,
            sourceText = originalStt,
            translatedText = translatedText,
            sourceLanguage = "en",
            targetLanguage = "hi",
            messageType = MessageEntity.TYPE_NORMAL_TEXT,
        )

        // Receiver local entity
        val receiverEntity = MessageEntity(
            id = "msg-1",
            senderId = "node-sender",
            recipientId = "node-receiver",
            conversationId = "conv-1",
            body = wirePayload.body,
            sentAt = 1000L,
            received = false,
            sourceText = wirePayload.sourceText,
            translatedText = wirePayload.translatedText,
            sourceLanguage = wirePayload.sourceLanguage,
            targetLanguage = wirePayload.targetLanguage,
            messageType = MessageEntity.TYPE_NORMAL_TEXT,
        )

        // Receiver UI representation on first render
        val receiverDisplayText = receiverEntity.translatedText ?: receiverEntity.body
        assertEquals("Receiver must see translated text on first render", translatedText, receiverDisplayText)
    }
}
