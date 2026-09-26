package app.swarsetu.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import app.swarsetu.stt.SttLanguage

/**
 * CompositionLocal providing the current active preferred UI language.
 * Default is [SttLanguage.ENGLISH].
 */
val LocalAppLanguage = staticCompositionLocalOf { SttLanguage.ENGLISH }

/**
 * Convenience accessor to retrieve the localized string for [key] based on the current
 * [LocalAppLanguage] provided in the Compose hierarchy.
 */
@Composable
@ReadOnlyComposable
fun uiText(key: UiTextKey): String {
    return AppStrings.get(key, LocalAppLanguage.current)
}

/**
 * Convenience accessor to retrieve and format the localized string for [key] based on the current
 * [LocalAppLanguage] provided in the Compose hierarchy with given [args].
 */
@Composable
@ReadOnlyComposable
fun uiText(key: UiTextKey, vararg args: Any): String {
    return AppStrings.format(key, LocalAppLanguage.current, *args)
}
