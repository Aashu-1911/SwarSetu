package app.swarsetu.call

import android.util.Log

/**
 * Explicit Lifecycle states of an offline Live Translated Call.
 * Enforces hard transitions to prevent invalid states and duplicate calls.
 */
enum class CallState {
    IDLE,
    OUTGOING_REQUESTED,
    OUTGOING,
    INCOMING,
    RINGING,
    ACCEPTING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ENDING,
    ENDED,
    FAILED;

    val isActive: Boolean
        get() = this == OUTGOING_REQUESTED || this == OUTGOING || this == INCOMING ||
                this == RINGING || this == ACCEPTING || this == CONNECTING ||
                this == CONNECTED || this == RECONNECTING || this == ENDING

    val isEstablished: Boolean
        get() = this == CONNECTED

    fun canTransitionTo(next: CallState): Boolean {
        if (this == next) return true // Idempotent no-op
        return when (this) {
            IDLE -> next == OUTGOING_REQUESTED || next == OUTGOING || next == INCOMING
            OUTGOING_REQUESTED -> next == OUTGOING || next == ENDING || next == ENDED || next == FAILED
            OUTGOING -> next == RINGING || next == CONNECTING || next == CONNECTED || next == ENDING || next == ENDED || next == FAILED
            INCOMING -> next == ACCEPTING || next == CONNECTING || next == ENDING || next == ENDED || next == FAILED
            RINGING -> next == CONNECTING || next == CONNECTED || next == ENDING || next == ENDED || next == FAILED
            ACCEPTING -> next == CONNECTING || next == CONNECTED || next == ENDING || next == ENDED || next == FAILED
            CONNECTING -> next == CONNECTED || next == RECONNECTING || next == ENDING || next == ENDED || next == FAILED
            CONNECTED -> next == RECONNECTING || next == ENDING || next == ENDED || next == FAILED
            RECONNECTING -> next == CONNECTED || next == ENDING || next == ENDED || next == FAILED
            ENDING -> next == ENDED || next == FAILED || next == IDLE
            ENDED -> next == IDLE
            FAILED -> next == IDLE
        }
    }

    companion object {
        private const val TAG = "CallStateMachine"

        fun validateAndLogTransition(current: CallState, next: CallState, callId: String? = null): Boolean {
            val allowed = current.canTransitionTo(next)
            val idTag = if (callId != null) " [$callId]" else ""
            if (allowed) {
                Log.i(TAG, "STATE_TRANSITION$idTag: $current -> $next")
            } else {
                Log.e(TAG, "INVALID_STATE_TRANSITION$idTag: Cannot transition from $current to $next")
            }
            return allowed
        }
    }
}

/**
 * Direction of the call relative to this device.
 */
enum class CallDirection {
    OUTGOING,
    INCOMING
}

/**
 * End or failure reason for a call.
 */
enum class CallEndReason {
    USER_HANGUP,
    USER_REJECTED,
    REMOTE_HANGUP,
    REMOTE_REJECTED,
    BUSY,
    TIMEOUT,
    NETWORK_LOST,
    SECURITY_FAILURE,
    AUDIO_DEVICE_ERROR,
    UNSUPPORTED_LANGUAGE,
    UNKNOWN;

    val userFriendlyMessage: String
        get() = when (this) {
            USER_HANGUP -> "Call ended"
            USER_REJECTED -> "Call declined"
            REMOTE_HANGUP -> "Remote peer ended the call"
            REMOTE_REJECTED -> "Remote peer declined the call"
            BUSY -> "Remote peer is busy"
            TIMEOUT -> "Call timed out"
            NETWORK_LOST -> "Mesh network disconnected"
            SECURITY_FAILURE -> "Call encryption verification failed"
            AUDIO_DEVICE_ERROR -> "Audio hardware error"
            UNSUPPORTED_LANGUAGE -> "Selected language not supported on peer"
            UNKNOWN -> "Call ended unexpectedly"
        }
}
