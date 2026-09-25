package app.swarsetu.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LiveCallTestSuite {

    @Test
    fun testCallSignalingCodec_OfferAndAnswer() {
        val offer = CallSignal.Offer(
            callId = UUID.randomUUID().toString(),
            callerNodeId = "node_alice",
            callerName = "Alice",
            callerSourceLanguage = "mr",
            callerTargetLanguage = "hi",
            wifiAwarePort = 42424,
            sessionNonce = "alice_nonce_12345678",
        )

        val encodedOffer = CallSignalCodec.encode(offer)
        assertTrue(encodedOffer.isNotEmpty())

        val decodedOffer = CallSignalCodec.decode(encodedOffer)
        assertNotNull(decodedOffer)
        assertTrue(decodedOffer is CallSignal.Offer)
        val castOffer = decodedOffer as CallSignal.Offer
        assertEquals(offer.callId, castOffer.callId)
        assertEquals("node_alice", castOffer.callerNodeId)
        assertEquals("mr", castOffer.callerSourceLanguage)
        assertEquals("hi", castOffer.callerTargetLanguage)
        assertEquals(42424, castOffer.wifiAwarePort)
        assertEquals("alice_nonce_12345678", castOffer.sessionNonce)

        val answer = CallSignal.Answer(
            callId = offer.callId,
            calleeNodeId = "node_bob",
            calleeName = "Bob",
            calleeSourceLanguage = "hi",
            calleeTargetLanguage = "mr",
            accepted = true,
            wifiAwarePort = 42424,
            sessionNonce = "bob_nonce_87654321",
        )

        val encodedAnswer = CallSignalCodec.encode(answer)
        val decodedAnswer = CallSignalCodec.decode(encodedAnswer) as? CallSignal.Answer
        assertNotNull(decodedAnswer)
        assertEquals(offer.callId, decodedAnswer?.callId)
        assertTrue(decodedAnswer?.accepted == true)
        assertEquals("node_bob", decodedAnswer?.calleeNodeId)
    }

    @Test
    fun testCallSecurity_EncryptionDecryptionAndReplayProtection() {
        val callId = "test_call_1001"
        val nonceA = "alice_nonce_abc"
        val nonceB = "bob_nonce_xyz"

        val keyAlice = CallSecurity.deriveSessionKey(callId, nonceA, nonceB)
        val keyBob = CallSecurity.deriveSessionKey(callId, nonceA, nonceB)
        assertArrayEquals(keyAlice, keyBob)
        assertEquals(32, keyAlice.size) // 256-bit AES key

        val securityAlice = CallSecurity(keyAlice)
        val securityBob = CallSecurity(keyBob)

        val plaintext = "नमस्कार, आपण कसे आहात?".toByteArray(Charsets.UTF_8)
        val aad = "SWCL:seq=1:call=$callId".toByteArray(Charsets.UTF_8)

        val (iv, ciphertext) = securityAlice.encrypt(plaintext, aad)
        assertEquals(12, iv.size) // 96-bit GCM IV

        val decrypted = securityBob.decrypt(iv, ciphertext, aad)
        assertEquals("नमस्कार, आपण कसे आहात?", String(decrypted, Charsets.UTF_8))

        // Replay attack protection test
        assertTrue(securityBob.validateAndRecordSequence(1L))
        assertFalse(securityBob.validateAndRecordSequence(1L)) // Replay rejected
        assertTrue(securityBob.validateAndRecordSequence(2L))
        assertTrue(securityBob.validateAndRecordSequence(5L))
        assertTrue(securityBob.validateAndRecordSequence(3L)) // In-window out-of-order accepted
        assertFalse(securityBob.validateAndRecordSequence(3L)) // Duplicate rejected
    }

    @Test
    fun testCallPacketCodec_BinarySerialization() {
        val packet = CallPacket(
            callId = "call_abc_123",
            sequenceNumber = 42L,
            timestampMs = 1700000000000L,
            sourcePeerId = "peer_alice",
            payloadType = CallPayloadType.TRANSLATED_SEGMENT,
            iv = ByteArray(12) { it.toByte() },
            encryptedPayload = "encrypted_segment_bytes".toByteArray(Charsets.UTF_8),
        )

        val serialized = CallPacketCodec.encode(packet)
        assertTrue(serialized.isNotEmpty())

        val decoded = CallPacketCodec.decode(serialized)
        assertNotNull(decoded)
        assertEquals("call_abc_123", decoded?.callId)
        assertEquals(42L, decoded?.sequenceNumber)
        assertEquals(1700000000000L, decoded?.timestampMs)
        assertEquals("peer_alice", decoded?.sourcePeerId)
        assertEquals(CallPayloadType.TRANSLATED_SEGMENT, decoded?.payloadType)
        assertArrayEquals(packet.iv, decoded?.iv)
        assertArrayEquals(packet.encryptedPayload, decoded?.encryptedPayload)
    }

    @Test
    fun testJitterBuffer_SortingAndPlayout() {
        val jb = JitterBuffer(targetDelayMs = 40L, maxBufferCapacity = 20)

        val now = System.currentTimeMillis()
        val p1 = CallPacket("call1", 1L, now - 60, "alice", CallPayloadType.VOICE_PCM, ByteArray(12), ByteArray(320))
        val p2 = CallPacket("call1", 2L, now - 50, "alice", CallPayloadType.VOICE_PCM, ByteArray(12), ByteArray(320))
        val p3 = CallPacket("call1", 3L, now - 40, "alice", CallPayloadType.VOICE_PCM, ByteArray(12), ByteArray(320))

        // Push out of order: 2, 1, 3
        jb.push(p2, now)
        jb.push(p1, now)
        jb.push(p3, now)

        assertEquals(3, jb.currentQueueSize)

        // Poll ready packets should retrieve in sorted sequence: 1, 2, 3
        val out1 = jb.popReady(now + 100)
        val out2 = jb.popReady(now + 100)
        val out3 = jb.popReady(now + 100)

        assertEquals(1L, out1?.sequenceNumber)
        assertEquals(2L, out2?.sequenceNumber)
        assertEquals(3L, out3?.sequenceNumber)
    }

    @Test
    fun testCallVadEndpointDetector_SpeechAccumulationAndEndpoint() {
        var finalizedCount = 0
        var totalCapturedSamples = 0

        val vad = CallVadEndpointDetector(
            energyThresholdRms = 250f,
            silenceEndpointTimeoutMs = 200L,
            minSpeechDurationMs = 100L,
            onSpeechSegmentFinalized = { pcmShorts, durationMs ->
                finalizedCount++
                totalCapturedSamples += pcmShorts.size
            }
        )

        // 1. Send silence: RMS = 0
        val silenceChunk = ShortArray(320) { 0 }
        for (i in 0 until 10) {
            vad.processPcmChunk(silenceChunk, silenceChunk.size)
        }
        assertEquals(0, finalizedCount)

        // 2. Send loud speech: RMS > 1000
        val speechChunk = ShortArray(320) { if (it % 2 == 0) 4000 else -4000 }
        for (i in 0 until 15) { // 300 ms of speech
            vad.processPcmChunk(speechChunk, speechChunk.size)
        }
        assertTrue(vad.isSpeechActive)

        // 3. Send silence to trigger endpoint detection (200ms timeout)
        for (i in 0 until 12) { // 240 ms of silence
            vad.processPcmChunk(silenceChunk, silenceChunk.size)
        }

        assertFalse(vad.isSpeechActive)
        assertEquals(1, finalizedCount)
        assertTrue(totalCapturedSamples > 0)
    }
}
