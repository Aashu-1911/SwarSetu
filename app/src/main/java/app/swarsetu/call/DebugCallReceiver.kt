package app.swarsetu.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/**
 * BroadcastReceiver for automated testing and single-phone demonstration of the
 * Live Translated Call subsystem.
 */
class DebugCallReceiver : BroadcastReceiver(), KoinComponent {
    private val callManager: CallManager by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Received debug call action: $action")

        when (action) {
            ACTION_SIMULATE_INCOMING_CALL -> {
                val callerName = intent.getStringExtra("caller_name") ?: "Aarav Sharma"
                val callerId = intent.getStringExtra("caller_id") ?: "debug_peer_aarav_sharma"
                val callId = intent.getStringExtra("call_id") ?: UUID.randomUUID().toString()
                val srcLang = intent.getStringExtra("src_lang") ?: "hi"
                val tgtLang = intent.getStringExtra("tgt_lang") ?: "en"

                val offer = CallSignal.Offer(
                    callId = callId,
                    callerNodeId = callerId,
                    callerName = callerName,
                    callerSourceLanguage = srcLang,
                    callerTargetLanguage = tgtLang,
                    sessionNonce = CallSecurity.generateNonce(),
                    wifiAwareIpv6 = "127.0.0.1",
                    wifiAwarePort = 42424,
                )
                callManager.onSignalReceived(offer, callerId)
            }
            ACTION_ACCEPT_CALL -> {
                scope.launch {
                    val res = callManager.acceptCall()
                    Log.i(TAG, "acceptCall result: $res")
                }
            }
            ACTION_SIMULATE_VOICE -> {
                val current = callManager.currentSession.value
                if (current != null) {
                    val text = intent.getStringExtra("text") ?: "नमस्ते! आप कैसे हैं?"
                    val origText = intent.getStringExtra("orig_text") ?: "Hello! How are you?"
                    val voice = CallSignal.VoiceSegment(
                        callId = current.callId,
                        senderNodeId = current.peerId,
                        sourceLanguage = current.localTargetLanguage,
                        targetLanguage = current.localSourceLanguage,
                        originalText = origText,
                        text = text,
                        timestampMs = System.currentTimeMillis()
                    )
                    callManager.onSignalReceived(voice, current.peerId)
                } else {
                    Log.w(TAG, "No active session for SIMULATE_VOICE")
                }
            }
            ACTION_SIMULATE_HANGUP -> {
                val current = callManager.currentSession.value
                if (current != null) {
                    val hangup = CallSignal.Hangup(
                        callId = current.callId,
                        senderNodeId = current.peerId,
                        reason = CallEndReason.REMOTE_HANGUP.name
                    )
                    callManager.onSignalReceived(hangup, current.peerId)
                } else {
                    Log.w(TAG, "No active session for SIMULATE_HANGUP")
                }
            }
            ACTION_END_CALL -> {
                scope.launch {
                    callManager.endCall(CallEndReason.USER_HANGUP)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DebugCallReceiver"
        const val ACTION_SIMULATE_INCOMING_CALL = "app.swarsetu.debug.SIMULATE_INCOMING_CALL"
        const val ACTION_ACCEPT_CALL = "app.swarsetu.debug.ACCEPT_CALL"
        const val ACTION_SIMULATE_VOICE = "app.swarsetu.debug.SIMULATE_VOICE"
        const val ACTION_SIMULATE_HANGUP = "app.swarsetu.debug.SIMULATE_HANGUP"
        const val ACTION_END_CALL = "app.swarsetu.debug.END_CALL"
    }
}
