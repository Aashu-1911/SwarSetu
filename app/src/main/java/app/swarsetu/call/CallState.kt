package app.swarsetu.call

/**
 * Lifecycle states of an offline Live Translated Call.
 */
enum class CallState {
    IDLE,
    OUTGOING,
    INCOMING,
    CONNECTING,
    RINGING,
    CONNECTED,
    ENDING,
    ENDED,
    FAILED;

    val isActive: Boolean
        get() = this == OUTGOING || this == INCOMING || this == CONNECTING ||
                this == RINGING || this == CONNECTED || this == ENDING

    val isEstablished: Boolean
        get() = this == CONNECTED
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
