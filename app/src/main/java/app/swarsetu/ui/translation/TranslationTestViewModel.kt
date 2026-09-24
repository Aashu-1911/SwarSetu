package app.swarsetu.ui.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttPipeline
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicLanguage
import app.swarsetu.translation.indictrans.IndicTransModelBundle
import app.swarsetu.translation.indictrans.IndicTransModelManager
import app.swarsetu.tts.TtsLanguage
import app.swarsetu.tts.TtsManager
import app.swarsetu.tts.TtsPriority
import app.swarsetu.tts.TtsRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslationTestUiState(
    val selectedBundle: IndicTransModelBundle = IndicTransModelBundle.INDIC_INDIC,
    val autoSelectedBundle: IndicTransModelBundle = IndicTransModelBundle.INDIC_INDIC,
    val downloadedBundles: Set<IndicTransModelBundle> = emptySet(),
    val isModelDownloaded: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = IndicTransModelBundle.INDIC_INDIC.totalExpectedBytes,
    val downloadPercent: Int = 0,
    val isDownloading: Boolean = false,
    val currentDownloadFile: String = "",
    val isSttModelDownloaded: Boolean = false,
    val sttDownloadedBytes: Long = 0L,
    val sttTotalBytes: Long = 908_456_855L,
    val sttDownloadPercent: Int = 0,
    val isSttDownloading: Boolean = false,
    val sttCurrentDownloadFile: String = "",
    val sourceLanguage: IndicLanguage = IndicLanguage.HINDI,
    val targetLanguage: IndicLanguage = IndicLanguage.MARATHI,
    val inputText: String = "नमस्ते, यह स्वरासेतु ऑफ़लाइन अनुवाद परीक्षण है।",
    val outputText: String = "",
    val isTranslating: Boolean = false,
    val latencyMs: Long? = null,
    val tokensGenerated: Int? = null,
    val errorMessage: String? = null,
    val isRecording: Boolean = false,
    val sttLivePreview: String = "",
    val isPlayingTts: Boolean = false,
)

class TranslationTestViewModel(
    private val modelManager: IndicTransModelManager,
    private val translatorEngine: TranslatorEngine,
    private val sttPipeline: SttPipeline,
    private val ttsManager: TtsManager,
    private val sttModelManager: app.swarsetu.stt.SttModelManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslationTestUiState())
    val uiState: StateFlow<TranslationTestUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null
    private var sttDownloadJob: Job? = null

    init {
        updateAutoSelectedBundle()
        refreshModelStatus()
        refreshSttModelStatus()

        viewModelScope.launch {
            sttPipeline.state.collect { state ->
                val capturing = state == SttPipeline.PipelineState.CAPTURING
                _uiState.update { it.copy(isRecording = capturing) }
            }
        }

        viewModelScope.launch {
            sttPipeline.partialText.collect { partial ->
                if (partial.isNotBlank()) {
                    _uiState.update { it.copy(sttLivePreview = partial) }
                }
            }
        }

        viewModelScope.launch {
            sttPipeline.latestResult.collect { result ->
                if (result != null && result.text.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            sttLivePreview = "",
                            inputText = result.text,
                        )
                    }
                    translate()
                }
            }
        }
    }

    private fun updateAutoSelectedBundle() {
        val src = _uiState.value.sourceLanguage
        val tgt = _uiState.value.targetLanguage
        val optimal = translatorEngine.indicTransEngine.selectBundle(src, tgt)
        _uiState.update {
            it.copy(
                autoSelectedBundle = optimal,
                selectedBundle = optimal,
            )
        }
    }

    fun refreshModelStatus() {
        val bundle = _uiState.value.selectedBundle
        val downloaded = modelManager.isModelDownloaded(bundle)
        val bytes = modelManager.getDownloadedBytes(bundle)
        val total = bundle.totalExpectedBytes
        val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0

        val downloadedSet = IndicTransModelBundle.entries
            .filter { modelManager.isModelDownloaded(it) }
            .toSet()

        _uiState.update {
            it.copy(
                isModelDownloaded = downloaded,
                downloadedBytes = bytes,
                totalBytes = total,
                downloadPercent = if (downloaded) 100 else pct,
                downloadedBundles = downloadedSet,
                errorMessage = null,
            )
        }
    }

    fun selectBundle(bundle: IndicTransModelBundle) {
        _uiState.update { it.copy(selectedBundle = bundle) }
        refreshModelStatus()
    }

    fun setSourceLanguage(lang: IndicLanguage) {
        val target = _uiState.value.targetLanguage
        val optimal = translatorEngine.indicTransEngine.selectBundle(lang, target)
        _uiState.update {
            it.copy(
                sourceLanguage = lang,
                selectedBundle = optimal,
                autoSelectedBundle = optimal,
            )
        }
        refreshModelStatus()
    }

    fun setTargetLanguage(lang: IndicLanguage) {
        val source = _uiState.value.sourceLanguage
        val optimal = translatorEngine.indicTransEngine.selectBundle(source, lang)
        _uiState.update {
            it.copy(
                targetLanguage = lang,
                selectedBundle = optimal,
                autoSelectedBundle = optimal,
            )
        }
        refreshModelStatus()
    }

    fun swapLanguages() {
        val newSource = _uiState.value.targetLanguage
        val newTarget = _uiState.value.sourceLanguage
        val optimal = translatorEngine.indicTransEngine.selectBundle(newSource, newTarget)
        _uiState.update {
            it.copy(
                sourceLanguage = newSource,
                targetLanguage = newTarget,
                selectedBundle = optimal,
                autoSelectedBundle = optimal,
            )
        }
        refreshModelStatus()
    }

    fun setInputText(text: String) {
        _uiState.update { it.copy(inputText = text, errorMessage = null) }
    }

    fun toggleVoiceRecording() {
        if (_uiState.value.isRecording) {
            _uiState.update { it.copy(sttLivePreview = "Transcribing audio...") }
            sttPipeline.stopCapture()
        } else {
            val src = _uiState.value.sourceLanguage
            val sttLang = SttLanguage.fromCode(src.code) ?: SttLanguage.HINDI
            _uiState.update {
                it.copy(
                    isRecording = true,
                    sttLivePreview = "Listening in ${sttLang.displayName} (${sttLang.englishName})...",
                    errorMessage = null,
                )
            }
            if (sttPipeline.canCapture) {
                sttPipeline.startCapture(sttLang, silenceTimeoutMs = 2500L)
            } else {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        errorMessage = "Microphone permission required for voice input.",
                    )
                }
            }
        }
    }

    fun cancelVoiceRecording() {
        _uiState.update { it.copy(isRecording = false, sttLivePreview = "") }
        sttPipeline.cancelCapture()
    }

    fun speakOutput() {
        val state = _uiState.value
        val text = state.outputText.trim()
        if (text.isEmpty()) return

        val ttsLang = TtsLanguage.fromLanguageCode(state.targetLanguage.code, text) ?: TtsLanguage.HINDI
        viewModelScope.launch {
            _uiState.update { it.copy(isPlayingTts = true) }
            ttsManager.speak(
                TtsRequest(
                    requestId = java.util.UUID.randomUUID().toString(),
                    text = text,
                    language = ttsLang,
                    priority = TtsPriority.NORMAL,
                )
            )
            _uiState.update { it.copy(isPlayingTts = false) }
        }
    }

    fun stopTts() {
        ttsManager.stopAll()
        _uiState.update { it.copy(isPlayingTts = false) }
    }

    fun downloadSelectedModel() {
        if (_uiState.value.isDownloading) return
        val bundle = _uiState.value.selectedBundle

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    errorMessage = null,
                )
            }

            try {
                val success = modelManager.downloadModel(bundle) { bytes, total, pct, file ->
                    _uiState.update {
                        it.copy(
                            downloadedBytes = bytes,
                            totalBytes = total,
                            downloadPercent = pct,
                            currentDownloadFile = file,
                        )
                    }
                }

                _uiState.update {
                    val updatedSet = IndicTransModelBundle.entries
                        .filter { b -> modelManager.isModelDownloaded(b) }
                        .toSet()
                    it.copy(
                        isDownloading = false,
                        isModelDownloaded = success,
                        downloadPercent = if (success) 100 else it.downloadPercent,
                        currentDownloadFile = if (success) "Completed" else "Incomplete",
                        downloadedBundles = updatedSet,
                        errorMessage = if (!success) "Download incomplete. Please try again." else null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        errorMessage = "Download error: ${e.message}",
                    )
                }
            }
        }
    }

    fun deleteSelectedModel() {
        val bundle = _uiState.value.selectedBundle
        modelManager.deleteModel(bundle)
        refreshModelStatus()
    }

    fun refreshSttModelStatus() {
        val isDownloaded = sttModelManager.isSraVaaniAvailable()
        val bytes = sttModelManager.getDownloadedBytes()
        val total = sttModelManager.totalExpectedBytes
        val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0

        _uiState.update {
            it.copy(
                isSttModelDownloaded = isDownloaded,
                sttDownloadedBytes = bytes,
                sttTotalBytes = total,
                sttDownloadPercent = if (isDownloaded) 100 else pct,
            )
        }
    }

    fun downloadSttModel() {
        if (_uiState.value.isSttDownloading) return

        sttDownloadJob?.cancel()
        sttDownloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSttDownloading = true,
                    errorMessage = null,
                )
            }

            try {
                val success = sttModelManager.downloadSraVaaniModel { bytes, total, pct, file ->
                    _uiState.update {
                        it.copy(
                            sttDownloadedBytes = bytes,
                            sttTotalBytes = total,
                            sttDownloadPercent = pct,
                            sttCurrentDownloadFile = file,
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        isSttDownloading = false,
                        isSttModelDownloaded = success,
                        sttDownloadPercent = if (success) 100 else it.sttDownloadPercent,
                        sttCurrentDownloadFile = if (success) "Completed" else "Incomplete",
                        errorMessage = if (!success) "STT Download incomplete. Please try again." else null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSttDownloading = false,
                        errorMessage = "STT Download error: ${e.message}",
                    )
                }
            }
        }
    }

    fun deleteSttModel() {
        val dir = sttModelManager.getModelDir()
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        refreshSttModelStatus()
    }

    fun translate() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty()) return

        val requiredBundle = translatorEngine.indicTransEngine.selectBundle(state.sourceLanguage, state.targetLanguage)
        val isDownloaded = modelManager.isModelDownloaded(requiredBundle)

        if (!isDownloaded) {
            _uiState.update {
                it.copy(
                    selectedBundle = requiredBundle,
                    errorMessage = "Translation from ${state.sourceLanguage.displayName} to ${state.targetLanguage.displayName} requires the '${requiredBundle.displayName}' model (~${requiredBundle.approxSizeMb} MB). Please download it above.",
                )
            }
            refreshModelStatus()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true, errorMessage = null) }
            try {
                val result = translatorEngine.indicTransEngine.translate(
                    text = text,
                    source = state.sourceLanguage,
                    target = state.targetLanguage,
                    preferredBundle = requiredBundle,
                )

                _uiState.update {
                    it.copy(
                        outputText = result.translatedText,
                        latencyMs = result.latencyMs,
                        tokensGenerated = result.tokensGenerated,
                        isTranslating = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        errorMessage = "Translation failed: ${e.message}",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        sttPipeline.cancelCapture()
        ttsManager.stopAll()
    }
}
