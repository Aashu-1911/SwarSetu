package app.swarsetu.call

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Supported payload types inside an encrypted CallPacket.
 */
enum class CallPayloadType(val value: Byte) {
    VOICE_PCM(1),             // Raw speech PCM frames (16kHz 16-bit mono)
    TRANSLATED_SEGMENT(2),    // Finalized translated text + metadata
    AUDIO_STREAM_CHUNK(3),    // Synthesized/Relayed audio chunk
    HEARTBEAT(4),             // Keepalive and RTT measurement ping/pong
    CALL_CONTROL(5);          // In-call control (mute status, language change)

    companion object {
        fun fromByte(b: Byte): CallPayloadType =
            entries.firstOrNull { it.value == b } ?: VOICE_PCM
    }
}

/**
 * Represents an encrypted network packet exchanged between devices during a live call.
 */
data class CallPacket(
    val callId: String,
    val sequenceNumber: Long,
    val timestampMs: Long,
    val sourcePeerId: String,
    val payloadType: CallPayloadType,
    val iv: ByteArray, // 12 bytes
    val encryptedPayload: ByteArray,
) {
    /**
     * Associated Authenticated Data (AAD) bound to the AES-GCM cipher.
     */
    fun computeAad(): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 8 + 1 + callId.length + sourcePeerId.length)
        buffer.putLong(sequenceNumber)
        buffer.putLong(timestampMs)
        buffer.put(payloadType.value)
        buffer.put(callId.toByteArray(Charsets.UTF_8))
        buffer.put(sourcePeerId.toByteArray(Charsets.UTF_8))
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CallPacket
        return callId == other.callId &&
                sequenceNumber == other.sequenceNumber &&
                timestampMs == other.timestampMs &&
                sourcePeerId == other.sourcePeerId &&
                payloadType == other.payloadType &&
                iv.contentEquals(other.iv) &&
                encryptedPayload.contentEquals(other.encryptedPayload)
    }

    override fun hashCode(): Int {
        var result = callId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + sourcePeerId.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        return result
    }
}

/**
 * Binary wire codec for CallPacket framing:
 * [MAGIC: 4B "SWCL"][VERSION: 1B][CALL_ID_LEN: 1B][CALL_ID: NB][SEQ: 8B][TS: 8B][PEER_ID_LEN: 1B][PEER_ID: NB]
 * [TYPE: 1B][IV: 12B][PAYLOAD_LEN: 4B][ENCRYPTED_PAYLOAD: NB]
 */
object CallPacketCodec {
    const val MAGIC: Int = 0x5357434C // "SWCL"
    const val VERSION: Byte = 1

    fun encode(packet: CallPacket): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(MAGIC)
        dos.writeByte(VERSION.toInt())

        val callIdBytes = packet.callId.toByteArray(Charsets.UTF_8)
        dos.writeByte(callIdBytes.size)
        dos.write(callIdBytes)

        dos.writeLong(packet.sequenceNumber)
        dos.writeLong(packet.timestampMs)

        val peerIdBytes = packet.sourcePeerId.toByteArray(Charsets.UTF_8)
        dos.writeByte(peerIdBytes.size)
        dos.write(peerIdBytes)

        dos.writeByte(packet.payloadType.value.toInt())
        dos.write(packet.iv)

        dos.writeInt(packet.encryptedPayload.size)
        dos.write(packet.encryptedPayload)

        dos.flush()
        return baos.toByteArray()
    }

    fun decode(bytes: ByteArray): CallPacket? = runCatching {
        val bais = ByteArrayInputStream(bytes)
        val dis = DataInputStream(bais)

        val magic = dis.readInt()
        if (magic != MAGIC) return null

        val version = dis.readByte()
        if (version != VERSION) return null

        val callIdLen = dis.readByte().toInt() and 0xFF
        val callIdBytes = ByteArray(callIdLen)
        dis.readFully(callIdBytes)
        val callId = String(callIdBytes, Charsets.UTF_8)

        val seq = dis.readLong()
        val ts = dis.readLong()

        val peerIdLen = dis.readByte().toInt() and 0xFF
        val peerIdBytes = ByteArray(peerIdLen)
        dis.readFully(peerIdBytes)
        val peerId = String(peerIdBytes, Charsets.UTF_8)

        val typeByte = dis.readByte()
        val type = CallPayloadType.fromByte(typeByte)

        val iv = ByteArray(CallSecurity.IV_SIZE_BYTES)
        dis.readFully(iv)

        val payloadLen = dis.readInt()
        if (payloadLen < 0 || payloadLen > 5 * 1024 * 1024) return null // 5MB sanity cap
        val payload = ByteArray(payloadLen)
        dis.readFully(payload)

        CallPacket(
            callId = callId,
            sequenceNumber = seq,
            timestampMs = ts,
            sourcePeerId = peerId,
            payloadType = type,
            iv = iv,
            encryptedPayload = payload,
        )
    }.getOrNull()
}
