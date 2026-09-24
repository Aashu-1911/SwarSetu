package app.swarsetu.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.swarsetu.data.settings.SettingsStore
import app.swarsetu.stt.SttModelManager
import app.swarsetu.translation.TranslatorEngine
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

import androidx.compose.ui.res.stringResource
import app.swarsetu.R

@Composable
fun LanguageInitScreen(
    onInitializationComplete: () -> Unit,
    settingsStore: SettingsStore = koinInject(),
    translatorEngine: TranslatorEngine = koinInject(),
) {
    var completedModels by remember { mutableIntStateOf(0) }
    var totalModels by remember { mutableIntStateOf(10) }
    var currentDownloading by remember { mutableStateOf("") }
    var downloadStage by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val stageTranslation = stringResource(R.string.lang_init_stage_translation)
    val speechEn = stringResource(R.string.lang_init_speech_en)
    val speechHi = stringResource(R.string.lang_init_speech_hi)
    val errorDefault = stringResource(R.string.lang_init_error_default)

    val downloadModels = {
        isError = false
        isDownloading = true
        errorMessage = ""
        scope.launch {
            try {
                val translationCount = 8
                totalModels = translationCount

                // Speech models: SraVaani 1.0 offline STT assets (tables.bin, tokens.txt) packaged in APK;
                // ONNX weights managed via SttModelManager (PS 26173 requirement).
                downloadStage = stageTranslation
                translatorEngine.downloadAllRequiredModels { completed, total, currentLang ->
                    currentDownloading = "Translation: $currentLang"
                    completedModels = completed
                }

                settingsStore.setLanguageInitComplete(true)
                onInitializationComplete()
            } catch (e: Exception) {
                isError = true
                isDownloading = false
                errorMessage = e.message ?: errorDefault
            }
        }
    }

    val skipInit = {
        scope.launch {
            settingsStore.setLanguageInitComplete(true)
            onInitializationComplete()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.lang_init_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.lang_init_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (!isDownloading && !isError) {
            Button(onClick = { downloadModels() }) {
                Text(stringResource(R.string.lang_init_download_button))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = { skipInit() }) {
                Text(stringResource(R.string.lang_init_skip_button))
            }
        } else if (isError) {
            Text(
                text = errorMessage.ifBlank { errorDefault },
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Button(onClick = { downloadModels() }) {
                    Text(stringResource(R.string.lang_init_retry_button))
                }
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedButton(onClick = { skipInit() }) {
                    Text(stringResource(R.string.lang_init_skip_button))
                }
            }
        } else {
            val progress = if (totalModels > 0) completedModels.toFloat() / totalModels.toFloat() else 0f
            LinearProgressIndicator(progress = { progress })
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "$downloadStage ($completedModels of $totalModels total)...",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (currentDownloading.isNotBlank()) {
                Text(
                    text = currentDownloading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = { skipInit() }) {
                Text(stringResource(R.string.lang_init_skip_button))
            }
        }
    }
}
