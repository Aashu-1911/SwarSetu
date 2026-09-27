package app.swarsetu.sos

import app.swarsetu.ui.i18n.UiTextKey

/**
 * The 5 supported Emergency SOS alert types in SwarSetu.
 * Each type has hardcoded translations across all 10 supported Indian languages.
 */
enum class SosType(
    val code: String,
    val titleKey: UiTextKey,
    val messageKey: UiTextKey,
) {
    GENERAL("GENERAL", UiTextKey.SOS_TYPE_GENERAL, UiTextKey.SOS_MSG_GENERAL),
    MEDICAL("MEDICAL", UiTextKey.SOS_TYPE_MEDICAL, UiTextKey.SOS_MSG_MEDICAL),
    FIRE("FIRE", UiTextKey.SOS_TYPE_FIRE, UiTextKey.SOS_MSG_FIRE),
    DISASTER("DISASTER", UiTextKey.SOS_TYPE_DISASTER, UiTextKey.SOS_MSG_DISASTER),
    SAFETY("SAFETY", UiTextKey.SOS_TYPE_SAFETY, UiTextKey.SOS_MSG_SAFETY);

    companion object {
        fun fromCode(code: String?): SosType {
            if (code == null) return GENERAL
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: GENERAL
        }

        /**
         * Parses the SOS type from wire text formatted as `[SOS:TYPE] ...` or returns [GENERAL] if unspecified.
         */
        fun parseFromText(text: String?): SosType {
            if (text == null) return GENERAL
            for (type in entries) {
                if (text.startsWith("[SOS:${type.code}]", ignoreCase = true)) {
                    return type
                }
            }
            return GENERAL
        }

        /**
         * Formats wire text with the SOS type header.
         */
        fun formatWireText(type: SosType, rawEnglishText: String): String {
            return "[SOS:${type.code}] $rawEnglishText"
        }

        /**
         * Strips the `[SOS:TYPE]` prefix for plain display if needed.
         */
        fun stripTag(text: String): String {
            return text.replace(Regex("^\\[SOS:[A-Z]+\\]\\s*"), "")
        }
    }
}
