package app.swarsetu.call

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides authenticated E2E encryption and replay protection for Live Translated Call packets.
 * Uses AES-256-GCM with distinct derived keys for caller-to-callee and callee-to-caller directions.
 */
class CallSecurity(
    private val sessionKey: ByteArray,
) {
    companion object {
        const val KEY_SIZE_BYTES = 32
        const val IV_SIZE_BYTES = 12
        const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Derives a deterministic 256-bit symmetric session key from the combined nonces and callId.
         */
        fun deriveSessionKey(
            callId: String,
            callerNonce: String,
            calleeNonce: String,
            sharedSecret: ByteArray? = null,
        ): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(callId.toByteArray(Charsets.UTF_8))
            md.update(callerNonce.toByteArray(Charsets.UTF_8))
            md.update(calleeNonce.toByteArray(Charsets.UTF_8))
            if (sharedSecret != null && sharedSecret.isNotEmpty()) {
                md.update(sharedSecret)
            }
            return md.digest()
        }

        fun generateNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val secureRandom = SecureRandom()
    private val secretKeySpec = SecretKeySpec(sessionKey, "AES")

    // Anti-replay sliding window (tracks highest sequence number and bitmask of seen packets)
    private var highestSeq: Long = -1L
    private var replayWindow: Long = 0L // 64-packet bitmask
    private val lock = Any()

    /**
     * Encrypts [plaintext] payload with AES-256-GCM.
     * Generates a unique 12-byte IV and binds [aad] (associated authenticated data).
     * Returns Pair(iv, ciphertextWithTag).
     */
    fun encrypt(plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(iv, ciphertext)
    }

    /**
     * Decrypts [ciphertextWithTag] and verifies authenticity using [iv] and [aad].
     * Throws exception if authentication fails or payload is tampered with.
     */
    fun decrypt(iv: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(TAG_BITS, iv))
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Checks if sequence number [seq] is a valid, non-replayed packet.
     * Updates replay sliding window if valid.
     */
    fun validateAndRecordSequence(seq: Long): Boolean = synchronized(lock) {
        if (seq < 0) return false

        if (highestSeq == -1L) {
            highestSeq = seq
            replayWindow = 1L
            return true
        }

        val diff = seq - highestSeq
        if (diff > 0) {
            // New packet ahead of highest
            if (diff >= 64) {
                replayWindow = 1L
            } else {
                replayWindow = (replayWindow shl diff.toInt()) or 1L
            }
            highestSeq = seq
            return true
        } else {
            // Out-of-order or duplicate packet behind highestSeq
            val offset = (-diff).toInt()
            if (offset >= 64) {
                // Too old, outside replay window
                return false
            }
            val bit = 1L shl offset
            if ((replayWindow and bit) != 0L) {
                // Duplicate packet!
                return false
            }
            // Mark as seen in window
            replayWindow = replayWindow or bit
            return true
        }
    }
}
