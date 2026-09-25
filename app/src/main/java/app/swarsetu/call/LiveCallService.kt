package app.swarsetu.call

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.swarsetu.MainActivity
import app.swarsetu.R
import app.swarsetu.notifications.NotificationChannels
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service hosting the active Live Translated Call session.
 * Keeps microphone capture and network transport alive even when the app is backgrounded.
 */
class LiveCallService : LifecycleService() {
    private val callManager: CallManager by inject()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        startCallForeground()
        observeCallLifecycle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_CALL -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                updateNotification(callManager.state.value)
            }
        }
        return START_NOT_STICKY
    }

    private fun observeCallLifecycle() {
        lifecycleScope.launch {
            callManager.state.collectLatest { state ->
                when (state) {
                    CallState.IDLE,
                    CallState.ENDED,
                    CallState.FAILED -> {
                        stopSelf()
                    }
                    else -> {
                        updateNotification(state)
                    }
                }
            }
        }
    }

    private fun startCallForeground() {
        val notification = buildCallNotification(callManager.state.value)
        postForeground(notification)
    }

    private fun updateNotification(state: CallState) {
        val notification = buildCallNotification(state)
        postForeground(notification)
    }

    private fun buildCallNotification(state: CallState): Notification {
        val session = callManager.currentSession.value
        val peerId = session?.peerId ?: "Peer"
        val stateText = when (state) {
            CallState.OUTGOING -> getString(R.string.call_state_outgoing)
            CallState.INCOMING -> getString(R.string.call_state_incoming)
            CallState.CONNECTING -> getString(R.string.call_state_connecting)
            CallState.RINGING -> getString(R.string.call_state_ringing)
            CallState.CONNECTED -> getString(R.string.call_state_connected)
            CallState.ENDING -> getString(R.string.call_state_ending)
            CallState.ENDED -> getString(R.string.call_state_ended)
            CallState.FAILED -> getString(R.string.call_state_failed)
            CallState.IDLE -> getString(R.string.call_state_idle)
        }

        val openApp = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val endIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(this, LiveCallActionReceiver::class.java).setAction(LiveCallActionReceiver.ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val muteIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(this, LiveCallActionReceiver::class.java).setAction(LiveCallActionReceiver.ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val speakerIntent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(this, LiveCallActionReceiver::class.java).setAction(LiveCallActionReceiver.ACTION_SPEAKER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val acceptIntent = PendingIntent.getBroadcast(
            this,
            104,
            Intent(this, LiveCallActionReceiver::class.java).setAction(LiveCallActionReceiver.ACTION_ACCEPT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val rejectIntent = PendingIntent.getBroadcast(
            this,
            105,
            Intent(this, LiveCallActionReceiver::class.java).setAction(LiveCallActionReceiver.ACTION_REJECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val isMuted = callManager.isMuted.value
        val isSpeaker = callManager.isSpeakerOn.value

        val builder = NotificationCompat.Builder(this, NotificationChannels.LIVE_CALL)
            .setContentTitle(
                if (state == CallState.INCOMING) getString(R.string.call_notification_incoming_title)
                else getString(R.string.call_notification_title)
            )
            .setContentText("$peerId • $stateText")
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setOngoing(state != CallState.ENDED && state != CallState.FAILED && state != CallState.IDLE)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        if (state == CallState.CONNECTED) {
            builder.setUsesChronometer(true)
            builder.setWhen(session?.connectedAt ?: System.currentTimeMillis())
        }

        if (state == CallState.INCOMING) {
            builder.addAction(0, getString(R.string.call_action_accept), acceptIntent)
            builder.addAction(0, getString(R.string.call_action_reject), rejectIntent)
        } else if (state == CallState.CONNECTED || state == CallState.OUTGOING || state == CallState.CONNECTING || state == CallState.RINGING) {
            builder.addAction(
                0,
                if (isMuted) getString(R.string.call_action_unmute) else getString(R.string.call_action_mute),
                muteIntent,
            )
            builder.addAction(
                0,
                if (isSpeaker) getString(R.string.call_action_earpiece) else getString(R.string.call_action_speaker),
                speakerIntent,
            )
            builder.addAction(0, getString(R.string.call_action_end), endIntent)
        }

        return builder.build()
    }

    private fun postForeground(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_CALL = "app.swarsetu.call.ACTION_START_CALL"
        const val ACTION_UPDATE_STATUS = "app.swarsetu.call.ACTION_UPDATE_STATUS"
        const val ACTION_STOP_CALL = "app.swarsetu.call.ACTION_STOP_CALL"

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveCallService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveCallService::class.java))
        }
    }
}
