package app.swarsetu.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import app.swarsetu.ui.theme.BrandPrimaryDark
import app.swarsetu.ui.theme.BrandPrimaryLight

data class LanguageItem(
    val code: String,
    val nativeName: String,
    val englishName: String,
)

val SUPPORTED_LANGUAGES = listOf(
    LanguageItem("en", "English", "English"),
    LanguageItem("hi", "हिन्दी", "Hindi"),
    LanguageItem("mr", "मराठी", "Marathi"),
    LanguageItem("gu", "ગુજરાતી", "Gujarati"),
    LanguageItem("kn", "ಕನ್ನಡ", "Kannada"),
    LanguageItem("ml", "മലയാളം", "Malayalam"),
    LanguageItem("ta", "தமிழ்", "Tamil"),
    LanguageItem("te", "తెలుగు", "Telugu"),
    LanguageItem("or", "ଓଡ଼ିଆ", "Odia"),
    LanguageItem("bn", "বাংলা", "Bengali"),
)

@Composable
fun LanguagePickerDialog(
    title: String,
    currentCode: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val accentColor = if (isDark) BrandPrimaryDark else BrandPrimaryLight

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                ),
            )
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(SUPPORTED_LANGUAGES, key = { it.code }) { lang ->
                    val isSelected = lang.code.equals(currentCode, ignoreCase = true)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onLanguageSelected(lang.code)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onLanguageSelected(lang.code)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = accentColor),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = lang.nativeName,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                            if (lang.nativeName != lang.englishName) {
                                Text(
                                    text = lang.englishName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiText(UiTextKey.ACTION_CANCEL), color = accentColor)
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
