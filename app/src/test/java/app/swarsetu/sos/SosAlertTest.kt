package app.swarsetu.sos

import app.swarsetu.stt.SttLanguage
import app.swarsetu.ui.i18n.AppStrings
import app.swarsetu.ui.i18n.UiTextKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAlertTest {

    @Test
    fun parseFromText_correctlyIdentifiesAll5Types() {
        assertEquals(SosType.GENERAL, SosType.parseFromText("[SOS:GENERAL] Emergency"))
        assertEquals(SosType.MEDICAL, SosType.parseFromText("[SOS:MEDICAL] Doctor needed"))
        assertEquals(SosType.FIRE, SosType.parseFromText("[SOS:FIRE] Fire outbreak"))
        assertEquals(SosType.DISASTER, SosType.parseFromText("[SOS:DISASTER] Flood trapped"))
        assertEquals(SosType.SAFETY, SosType.parseFromText("[SOS:SAFETY] In danger"))
        assertEquals(SosType.GENERAL, SosType.parseFromText("Unknown prefix message"))
    }

    @Test
    fun formatWireText_prependsCorrectTag() {
        val formatted = SosType.formatWireText(SosType.MEDICAL, "Medical assistance needed")
        assertEquals("[SOS:MEDICAL] Medical assistance needed", formatted)
    }

    @Test
    fun appStrings_all10LanguagesContainNonEmptySosTranslations() {
        val keys = listOf(
            UiTextKey.SOS_CONFIRM_TITLE,
            UiTextKey.SOS_CONFIRM_DESC,
            UiTextKey.SOS_SELECT_TYPE,
            UiTextKey.SOS_COOLDOWN_WAIT,
            UiTextKey.SOS_BUTTON_CONFIRM,
            UiTextKey.SOS_BUTTON_CANCEL,
            UiTextKey.SOS_TYPE_GENERAL,
            UiTextKey.SOS_MSG_GENERAL,
            UiTextKey.SOS_TYPE_MEDICAL,
            UiTextKey.SOS_MSG_MEDICAL,
            UiTextKey.SOS_TYPE_FIRE,
            UiTextKey.SOS_MSG_FIRE,
            UiTextKey.SOS_TYPE_DISASTER,
            UiTextKey.SOS_MSG_DISASTER,
            UiTextKey.SOS_TYPE_SAFETY,
            UiTextKey.SOS_MSG_SAFETY,
        )

        for (lang in SttLanguage.entries) {
            for (key in keys) {
                val translation = AppStrings.get(key, lang)
                assertNotNull("Missing translation for key $key in language $lang", translation)
                assertTrue("Translation is empty for key $key in language $lang", translation.isNotBlank())
            }
        }
    }

    @Test
    fun sosType_keysMapCorrectly() {
        for (type in SosType.entries) {
            val title = AppStrings.get(type.titleKey, SttLanguage.HINDI)
            val msg = AppStrings.get(type.messageKey, SttLanguage.HINDI)
            assertTrue("Hindi title for $type should not be blank", title.isNotBlank())
            assertTrue("Hindi message for $type should not be blank", msg.isNotBlank())
        }
    }
}
