package app.swarsetu.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.swarsetu.call.CallEndReason
import app.swarsetu.call.CallManager
import app.swarsetu.call.CallSession
import app.swarsetu.call.CallState
import app.swarsetu.call.CallTranscriptItem
import app.swarsetu.data.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class LiveCallViewModel(
    private val callManager: CallManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val callState: StateFlow<CallState> = callManager.callState
    val currentSession: StateFlow<CallSession?> = callManager.currentSession
    val isMuted: StateFlow<Boolean> = callManager.isMuted
    val isSpeakerOn: StateFlow<Boolean> = callManager.isSpeakerOn

    private val _callDuration = MutableStateFlow("00:00")
    val callDuration: StateFlow<String> = _callDuration.asStateFlow()

    init {
        // Track call duration timer when connected
        viewModelScope.launch {
            while (isActive) {
                val session = callManager.currentSession.value
                val state = callManager.callState.value
                if (state == CallState.CONNECTED && session != null) {
                    val durationSec = (System.currentTimeMillis() - session.connectedAt) / 1000
                    val minutes = durationSec / 60
                    val seconds = durationSec % 60
                    _callDuration.value = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                } else if (state == CallState.IDLE) {
                    _callDuration.value = "00:00"
                }
                delay(1000)
            }
        }
    }

    fun initiateCall(peerId: String, peerName: String) {
        viewModelScope.launch {
            val prefLang = settingsStore.sttLanguageCode.first()
            val targetLang = if (prefLang == "mr") "hi" else "mr"
            callManager.initiateCall(
                peerId = peerId,
                peerName = peerName,
                localSourceLangCode = prefLang,
                localTargetLangCode = targetLang,
            )
        }
    }

    fun acceptCall() {
        viewModelScope.launch {
            callManager.acceptCall()
        }
    }

    fun rejectCall() {
        viewModelScope.launch {
            callManager.rejectCall(CallEndReason.USER_REJECTED)
        }
    }

    fun endCall() {
        viewModelScope.launch {
            callManager.endCall(CallEndReason.USER_HANGUP)
        }
    }

    fun toggleMute() {
        callManager.toggleMute()
    }

    fun toggleSpeaker() {
        callManager.toggleSpeaker()
    }
}
