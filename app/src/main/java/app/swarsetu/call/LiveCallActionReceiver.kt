package app.swarsetu.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class LiveCallActionReceiver : BroadcastReceiver(), KoinComponent {
    private val callManager: CallManager by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MUTE -> callManager.toggleMute()
            ACTION_SPEAKER -> callManager.toggleSpeaker()
            ACTION_END -> {
                scope.launch {
                    callManager.endCall(CallEndReason.USER_HANGUP)
                }
            }
            ACTION_ACCEPT -> {
                scope.launch {
                    callManager.acceptCall()
                }
                try {
                    val launchIntent = Intent(context, app.swarsetu.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(launchIntent)
                } catch (_: Exception) {}
            }
            ACTION_REJECT -> {
                scope.launch {
                    callManager.rejectCall(CallEndReason.USER_REJECTED)
                }
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "app.swarsetu.call.ACTION_MUTE"
        const val ACTION_SPEAKER = "app.swarsetu.call.ACTION_SPEAKER"
        const val ACTION_END = "app.swarsetu.call.ACTION_END"
        const val ACTION_ACCEPT = "app.swarsetu.call.ACTION_ACCEPT"
        const val ACTION_REJECT = "app.swarsetu.call.ACTION_REJECT"
    }
}
