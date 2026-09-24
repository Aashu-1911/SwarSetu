package app.swarsetu.ui.calling

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.swarsetu.calling.TranslationCallSession
import app.swarsetu.mesh.MeshController
import app.swarsetu.stt.PcmCapture
import app.swarsetu.stt.SttEngine
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.tts.TtsManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallViewModel(
    private val meshController: MeshController,
    sttEngine: SttEngine,
    translatorEngine: TranslatorEngine,
    ttsManager: TtsManager,
    context: Context,
) : ViewModel() {

    private val capture = PcmCapture(context)

    private val session = TranslationCallSession(
        meshController = meshController,
        sttEngine = sttEngine,
        translatorEngine = translatorEngine,
        ttsManager = ttsManager,
        coroutineScope = viewModelScope
    )

    val state = session.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = session.state.value
    )

    fun startCall(peerId: String, sourceLang: String, targetLang: String) {
        session.startCall(peerId, sourceLang, targetLang)
    }

    fun endCall() {
        session.endCall()
    }

    fun startListening() {
        if (!capture.hasPermission()) return
        viewModelScope.launch {
            if (capture.start()) {
                session.startListening()
                while (capture.state.value == PcmCapture.State.CAPTURING) {
                    val chunk = capture.readChunk()
                    if (chunk != null) {
                        session.pushAudio(chunk)
                    }
                }
            }
        }
    }

    fun stopListening() {
        capture.stop()
        session.stopListening()
    }

    fun pushAudio(samples: ShortArray) {
        session.pushAudio(samples)
    }
}
