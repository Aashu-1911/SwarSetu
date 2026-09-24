package app.swarsetu.ui.translation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.swarsetu.translation.indictrans.IndicLanguage
import app.swarsetu.translation.indictrans.IndicTransModelBundle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationTestScreen(
    onBack: () -> Unit,
    viewModel: TranslationTestViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var srcExpanded by remember { mutableStateOf(false) }
    var tgtExpanded by remember { mutableStateOf(false) }

    val samplePhrases = listOf(
        "नमस्ते, आप कैसे हैं?" to IndicLanguage.HINDI,
        "Hello, how can I help you today?" to IndicLanguage.ENGLISH,
        "आणीबाणी जाहीर करण्यात आली आहे." to IndicLanguage.MARATHI,
        "બચાવ ટીમ આવી રહી છે." to IndicLanguage.GUJARATI,
        "உடனடி உதவி தேவைப்படுகிறது." to IndicLanguage.TAMIL,
        "త్వరగా సురక్షిత ప్రాంతానికి చేరుకోండి." to IndicLanguage.TELUGU,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("STT + IndicTrans2 Pipeline Test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Model Selection & Download Card ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Neural Translation Models (IndicTrans2)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = "Tap a model below to manage, or change languages to auto-select the required model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 3 Selectable Model Cards ensuring ALL 3 are always fully visible
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IndicTransModelBundle.entries.forEach { bundle ->
                            val isSelected = bundle == state.selectedBundle
                            val isDownloaded = state.downloadedBundles.contains(bundle)
                            val isRecommended = bundle == state.autoSelectedBundle

                            Surface(
                                onClick = { viewModel.selectBundle(bundle) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = bundle.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            )
                                            if (isRecommended) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                                ) {
                                                    Text(
                                                        text = "Auto",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = bundle.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    if (isDownloaded) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                        ) {
                                            Text(
                                                text = "✓ Ready",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        ) {
                                            Text(
                                                text = "Download",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Selected Model Status & Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.isModelDownloaded) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Ready",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "${state.selectedBundle.displayName}: Ready (${state.downloadedBytes / (1024 * 1024)} MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (state.isDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Downloading ${state.selectedBundle.displayName} (${state.downloadPercent}%)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Not Downloaded",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Selected: ${state.selectedBundle.displayName} (~${state.selectedBundle.approxSizeMb} MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.isModelDownloaded) {
                                Button(
                                    onClick = { viewModel.downloadSelectedModel() },
                                    enabled = !state.isDownloading,
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (state.isDownloading) "Downloading..." else "Download Model")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.deleteSelectedModel() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }
                        }
                    }

                    if (state.isDownloading) {
                        LinearProgressIndicator(
                            progress = { state.downloadPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Text(
                            text = "Fetching ${state.currentDownloadFile} • ${state.downloadedBytes / (1024 * 1024)} MB / ${state.totalBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Speech-to-Text Model (SraVaani 1.0) Card ─────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Speech Recognition Model (SraVaani 1.0)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "FastConformer-TDT FP16 ONNX • 10 Indian Languages (~867 MB)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (state.isSttModelDownloaded) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = "✓ Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    text = "Download",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.isSttModelDownloaded) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Ready",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Installed (${state.sttDownloadedBytes / (1024 * 1024)} MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (state.isSttDownloading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Downloading SraVaani (${state.sttDownloadPercent}%)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Not Downloaded",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Model weights not downloaded (~867 MB)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.isSttModelDownloaded) {
                                Button(
                                    onClick = { viewModel.downloadSttModel() },
                                    enabled = !state.isSttDownloading,
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (state.isSttDownloading) "Downloading..." else "Download STT")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.deleteSttModel() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }
                        }
                    }

                    if (state.isSttDownloading) {
                        LinearProgressIndicator(
                            progress = { state.sttDownloadPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                        Text(
                            text = "Fetching ${state.sttCurrentDownloadFile} • ${state.sttDownloadedBytes / (1024 * 1024)} MB / ${state.sttTotalBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Language Selector Row ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Source Language Dropdown
                ExposedDropdownMenuBox(
                    expanded = srcExpanded,
                    onExpandedChange = { srcExpanded = !srcExpanded },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = "${state.sourceLanguage.displayName} (${state.sourceLanguage.code})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("From") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = srcExpanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = srcExpanded,
                        onDismissRequest = { srcExpanded = false },
                    ) {
                        IndicLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text("${lang.displayName} • ${lang.nativeName}") },
                                onClick = {
                                    viewModel.setSourceLanguage(lang)
                                    srcExpanded = false
                                },
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.swapLanguages() },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = "Swap Languages")
                }

                // Target Language Dropdown
                ExposedDropdownMenuBox(
                    expanded = tgtExpanded,
                    onExpandedChange = { tgtExpanded = !tgtExpanded },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = "${state.targetLanguage.displayName} (${state.targetLanguage.code})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("To") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tgtExpanded) },
                        modifier = Modifier.menuAnchor(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = tgtExpanded,
                        onDismissRequest = { tgtExpanded = false },
                    ) {
                        IndicLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text("${lang.displayName} • ${lang.nativeName}") },
                                onClick = {
                                    viewModel.setTargetLanguage(lang)
                                    tgtExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            // ── Quick sample chips ───────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Quick Sample Inputs:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    samplePhrases.take(3).forEach { (phrase, lang) ->
                        OutlinedButton(
                            onClick = {
                                viewModel.setSourceLanguage(lang)
                                viewModel.setInputText(phrase)
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text(
                                text = phrase,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // ── Live STT Preview Banner (when recording) ─────────────────────
            if (state.isRecording || state.sttLivePreview.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "Recording",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SraVaani 1.0 Live STT Recognition:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = if (state.sttLivePreview.isNotBlank()) state.sttLivePreview else "Listening...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // ── Input Text Box with Speech-to-Text Mic ────────────────────────
            OutlinedTextField(
                value = state.inputText,
                onValueChange = { viewModel.setInputText(it) },
                label = { Text("Input Text (${state.sourceLanguage.displayName})") },
                placeholder = { Text("Enter or speak text to translate...") },
                modifier = Modifier.fillMaxWidth().height(130.dp),
                maxLines = 5,
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.toggleVoiceRecording() },
                    ) {
                        if (state.isRecording) {
                            Icon(
                                Icons.Default.MicOff,
                                contentDescription = "Stop Recording",
                                tint = Color.Red,
                            )
                        } else {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice Input (SraVaani 1.0)",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )

            // ── Action Buttons Row (Voice & Translate) ────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.toggleVoiceRecording() },
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = if (state.isRecording) {
                        ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Icon(
                        if (state.isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (state.isRecording) "Stop Speaking" else "Speak (STT)")
                }

                Button(
                    onClick = { viewModel.translate() },
                    enabled = !state.isTranslating && state.inputText.isNotBlank(),
                    modifier = Modifier.weight(1.5f).height(50.dp),
                ) {
                    if (state.isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Translating...")
                    } else {
                        Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Translate")
                    }
                }
            }

            // ── Error Message Banner ─────────────────────────────────────────
            state.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── Output Text Box with Copy & TTS Speak ─────────────────────────
            OutlinedTextField(
                value = state.outputText,
                onValueChange = {},
                readOnly = true,
                label = { Text("Translated Output (${state.targetLanguage.displayName})") },
                placeholder = { Text("Translated text will appear here...") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                maxLines = 6,
                trailingIcon = {
                    Row {
                        if (state.outputText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    if (state.isPlayingTts) {
                                        viewModel.stopTts()
                                    } else {
                                        viewModel.speakOutput()
                                    }
                                },
                            ) {
                                Icon(
                                    if (state.isPlayingTts) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = "Speak Translation",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Translation", state.outputText))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                        }
                    }
                },
            )

            // ── Latency & Metrics Footer ─────────────────────────────────────
            if (state.latencyMs != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "⚡ Latency: ${state.latencyMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Tokens: ${state.tokensGenerated ?: 0}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "IndicTrans2 ONNX INT8",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
