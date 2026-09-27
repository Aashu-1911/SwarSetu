package app.swarsetu.call

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * Signaling messages exchanged between SwarSetu peers to establish, maintain,
 * and tear down offline VoIP calls over Wi-Fi Aware / Bluetooth mesh.
 */
@Serializable
sealed interface CallSignal {
    val callId: String
    val timestampMs: Long

    @Serializable
    data class Offer(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val callerNodeId: String,
        val callerName: String,
        val callerSourceLanguage: String, // e.g. "mr", "hi", "en"
        val callerTargetLanguage: String, // e.g. "hi", "mr", "en"
        val wifiAwarePort: Int = 42424,
        val wifiAwareIpv6: String? = null,
        val sessionNonce: String,
        val capabilities: Long = 1L,
    ) : CallSignal

    @Serializable
    data class Answer(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val calleeNodeId: String,
        val calleeName: String,
        val calleeSourceLanguage: String,
        val calleeTargetLanguage: String,
        val accepted: Boolean,
        val wifiAwarePort: Int = 42424,
        val wifiAwareIpv6: String? = null,
        val sessionNonce: String,
        val rejectReason: String? = null,
    ) : CallSignal

    @Serializable
    data class Ringing(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val calleeNodeId: String,
    ) : CallSignal

    @Serializable
    data class Hangup(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val senderNodeId: String,
        val reason: String = "USER_HANGUP",
    ) : CallSignal

    @Serializable
    data class Heartbeat(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val senderNodeId: String,
        val sequenceNumber: Long,
    ) : CallSignal

    @Serializable
    data class TransportCandidate(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val senderNodeId: String,
        val transportType: String, // "WIFI_AWARE", "WIFI_DIRECT", "BLE"
        val host: String,
        val port: Int,
    ) : CallSignal

    @Serializable
    data class VoiceSegment(
        override val callId: String,
        override val timestampMs: Long = System.currentTimeMillis(),
        val senderNodeId: String,
        val text: String,
        val originalText: String = "",
        val sourceLanguage: String = "",
        val targetLanguage: String = "",
        val sequenceNumber: Long = 0L,
    ) : CallSignal
}

object CallSignalCodec {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(signal: CallSignal): ByteArray = cbor.encodeToByteArray(signal)

    fun decode(bytes: ByteArray): CallSignal? = runCatching {
        cbor.decodeFromByteArray<CallSignal>(bytes)
    }.getOrNull()
}
