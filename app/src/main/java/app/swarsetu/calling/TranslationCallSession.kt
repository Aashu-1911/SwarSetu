package app.swarsetu.calling

import app.swarsetu.mesh.MeshController
import app.swarsetu.mesh.protocol.CallContent
import app.swarsetu.stt.SttEngine
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttLiveStream
import app.swarsetu.stt.SttResultType
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CallState(
    val status: CallStatus = CallStatus.IDLE,
    val sourceLanguage: String = "hi",
    val targetLanguage: String = "hi",
    val incomingText: String = "",
    val translatedIncomingText: String = "",
    val outgoingText: String = "",
    val activePeerId: String? = null
)

enum class CallStatus {
    IDLE,
    LISTENING,
    PROCESSING,
    RECEIVING
}

class TranslationCallSession(
    private val meshController: MeshController,
    private val sttEngine: SttEngine,
    private val translatorEngine: TranslatorEngine,
    private val ttsManager: TtsManager,
    private val coroutineScope: CoroutineScope
) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private var sttStream: SttLiveStream? = null
    private var sequenceNumber = 0
    private var receiveJob: Job? = null
    private var translationJob: Job? = null

    init {
        receiveJob = coroutineScope.launch {
            meshController.callFrames.collectLatest { frame ->
                if (frame.callerId == _state.value.activePeerId) {
                    handleIncomingFrame(frame)
                }
            }
        }
    }

    fun startCall(peerId: String, sourceLang: String, targetLang: String) {
        _state.update { 
            it.copy(
                status = CallStatus.IDLE, 
                activePeerId = peerId,
                sourceLanguage = sourceLang,
                targetLanguage = targetLang,
                incomingText = "",
                translatedIncomingText = "",
                outgoingText = ""
            ) 
        }
    }

    fun endCall() {
        stopListening()
        _state.update { it.copy(status = CallStatus.IDLE, activePeerId = null) }
    }

    suspend fun startListening() {
        if (!sttEngine.isReady) return
        _state.update { it.copy(status = CallStatus.LISTENING, outgoingText = "") }
        sttStream = sttEngine.startLiveStream(SttLanguage.fromCode(_state.value.sourceLanguage))
        
        sttStream?.onPartial = { text ->
            _state.update { it.copy(outgoingText = text) }
            sendFrame(text, isFinal = false)
        }
    }

    fun pushAudio(samples: ShortArray) {
        coroutineScope.launch(Dispatchers.Default) {
            sttStream?.pushPcm(samples)
        }
    }

    fun stopListening() {
        coroutineScope.launch {
            val result = sttStream?.finish()
            sttStream?.release()
            sttStream = null
            
            if (result != null && result.text.isNotBlank()) {
                _state.update { it.copy(outgoingText = result.text) }
                sendFrame(result.text, isFinal = true)
            }
            _state.update { it.copy(status = CallStatus.IDLE) }
        }
    }

    private fun sendFrame(text: String, isFinal: Boolean) {
        coroutineScope.launch {
            val peerId = _state.value.activePeerId ?: return@launch
            meshController.sendCallFrame(
                CallContent(
                    sessionId = peerId,
                    callerId = "",
                    sequenceNumber = sequenceNumber++,
                    sourceLanguage = _state.value.sourceLanguage,
                    targetLanguage = _state.value.targetLanguage,
                    sourceText = text,
                    isFinal = isFinal
                ),
                recipientId = peerId
            )
        }
    }

    private fun handleIncomingFrame(frame: CallContent) {
        _state.update { it.copy(status = CallStatus.RECEIVING, incomingText = frame.sourceText) }
        
        translationJob?.cancel()
        translationJob = coroutineScope.launch {
            val translated = translatorEngine.translate(
                frame.sourceText,
                frame.sourceLanguage,
                frame.targetLanguage
            )
            _state.update { it.copy(translatedIncomingText = translated) }

            if (frame.isFinal) {
                _state.update { it.copy(status = CallStatus.IDLE) }
                ttsManager.speak(translated, frame.targetLanguage)
            }
        }
    }
}
