package app.swarsetu.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageInitScreen(
    onInitializationComplete: () -> Unit,
    onBack: (() -> Unit)? = null,
    settingsStore: SettingsStore = koinInject(),
    translatorEngine: TranslatorEngine = koinInject(),
    sttModelManager: SttModelManager = koinInject(),
) {
    var progressFraction by remember { mutableStateOf(0f) }
    var currentDownloading by remember { mutableStateOf("") }
    var downloadStage by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val stageStt = uiText(UiTextKey.STEP_SPEECH_RECOGNITION)
    val stageTranslation = uiText(UiTextKey.STEP_NEURAL_TRANSLATION)
    val errorDefault = uiText(UiTextKey.MODEL_DOWNLOAD_FAILED)

    val downloadModels = {
        isError = false
        isDownloading = true
        errorMessage = ""
        progressFraction = 0f
        scope.launch {
            try {
                // 1. Download Speech-to-Text Model: SraVaani 1.0 FP16 ONNX (Step 1 of 2)
                downloadStage = stageStt
                sttModelManager.downloadSraVaaniModel { bytes, total, pct, file ->
                    val mbDownloaded = bytes / (1024 * 1024)
                    val mbTotal = total / (1024 * 1024)
                    currentDownloading = "$file • $mbDownloaded MB / $mbTotal MB ($pct%)"
                    progressFraction = (pct / 100f) * 0.5f
                }
                progressFraction = 0.5f

                // 2. Download Translation Models: IndicTrans2 (Step 2 of 2)
                downloadStage = stageTranslation
                translatorEngine.downloadAllRequiredModels { completed, total, currentLang ->
                    val transFraction = if (total > 0) completed.toFloat() / total.toFloat() else 0f
                    progressFraction = 0.5f + (transFraction * 0.5f)
                    currentDownloading = "Bundle $completed of $total: $currentLang"
                }
                progressFraction = 1f

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

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = uiText(UiTextKey.ACTION_BACK),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = uiText(UiTextKey.LANGUAGE_INIT_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = uiText(UiTextKey.LANGUAGE_INIT_DESC),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(32.dp))

            if (!isDownloading && !isError) {
                Button(onClick = { downloadModels() }) {
                    Text(uiText(UiTextKey.MODEL_DOWNLOAD_BTN))
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { skipInit() }) {
                    Text(uiText(UiTextKey.ACTION_SKIP))
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
                        Text(uiText(UiTextKey.ACTION_RETRY))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    OutlinedButton(onClick = { skipInit() }) {
                        Text(uiText(UiTextKey.ACTION_SKIP))
                    }
                }
            } else {
                LinearProgressIndicator(progress = { progressFraction.coerceIn(0f, 1f) })
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "$downloadStage (${(progressFraction * 100).toInt()}%)...",
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
                    Text(uiText(UiTextKey.ACTION_SKIP))
                }
            }
        }
    }
}
