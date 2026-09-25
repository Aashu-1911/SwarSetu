package app.swarsetu.call

import android.content.Context
import android.content.Intent
import android.util.Log
import app.swarsetu.mesh.MeshController
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.sravaani.Sravaani10Engine
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicLanguage
import app.swarsetu.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * High-level orchestrator for SwarSetu Live Translated VoIP Calls.
 */
class CallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sravaaniEngine: Sravaani10Engine,
    private val translatorEngine: TranslatorEngine,
    private val ttsManager: TtsManager,
    private val meshController: MeshController,
) {
    companion object {
        private const val TAG = "CallManager"
    }

    private val mutex = Mutex()

    private val _currentSession = MutableStateFlow<CallSession?>(null)
    val currentSession: StateFlow<CallSession?> = _currentSession.asStateFlow()

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()
    val state: StateFlow<CallState> get() = callState

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _incomingOffer = MutableStateFlow<CallSignal.Offer?>(null)
    val incomingOffer: StateFlow<CallSignal.Offer?> = _incomingOffer.asStateFlow()

    private var localCallerNonce: String? = null
    private var remoteNonce: String? = null

    // Processors & Engines
    private var audioEngine: CallAudioEngine? = null
    private var vadDetector: CallVadEndpointDetector? = null
    private var sttProcessor: LiveSttProcessor? = null
    private var transProcessor: LiveTranslationProcessor? = null
    private var ttsProcessor: LiveTtsProcessor? = null
    private var transport: CallMeshTransport? = null
    private var jitterBuffer: JitterBuffer? = null
    private var security: CallSecurity? = null

    private var callTimeoutJob: Job? = null

    init {
        sttProcessor = LiveSttProcessor(sravaaniEngine)
        transProcessor = LiveTranslationProcessor(translatorEngine)
        ttsProcessor = LiveTtsProcessor(ttsManager, scope)
        meshController.setCallSignalHandler { signalBytes, fromPeerId ->
            val signal = CallSignalCodec.decode(signalBytes)
            if (signal != null) {
                onSignalReceived(signal, fromPeerId)
            }
        }
    }

    /**
     * Starts an outgoing Live Translated Call to [peerId].
     */
    suspend fun initiateCall(
        peerId: String,
        peerName: String,
        localSourceLangCode: String = "hi",
        localTargetLangCode: String = "mr",
    ): String = mutex.withLock {
        if (_callState.value.isActive) {
            Log.w(TAG, "Cannot start call: another call is already active (${_callState.value})")
            return@withLock ""
        }

        val callId = UUID.randomUUID().toString()
        val myNodeId = meshController.neighborCount.value.toString() // or resolved node ID

        val session = CallSession(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            direction = CallDirection.OUTGOING,
            localSourceLanguage = localSourceLangCode,
            localTargetLanguage = localTargetLangCode,
            remoteSourceLanguage = localTargetLangCode,
            remoteTargetLanguage = localSourceLangCode,
        )
        _currentSession.value = session
        _callState.value = CallState.OUTGOING
        session.updateState(CallState.OUTGOING)

        localCallerNonce = CallSecurity.generateNonce()

        // 1. Initialize Transport as Server (awaiting connection from callee on accepted answer)
        val jb = JitterBuffer()
        jitterBuffer = jb

        // Derive provisional security or prepare session
        val dummyKey = CallSecurity.deriveSessionKey(callId, localCallerNonce!!, "provisional")
        security = CallSecurity(dummyKey)

        transport = CallMeshTransport(
            scope = scope,
            callId = callId,
            localPeerId = myNodeId,
            remotePeerId = peerId,
            security = security!!,
            jitterBuffer = jb,
            onPacketReceived = { packet -> handleInboundCallPacket(packet) },
            onConnectionClosed = { reason -> handleTransportClosed(reason) },
        )

        val port = transport?.startServer() ?: CallMeshTransport.DEFAULT_PORT

        // 2. Send Call Offer Signal over Mesh
        val offer = CallSignal.Offer(
            callId = callId,
            callerNodeId = myNodeId,
            callerName = "Me",
            callerSourceLanguage = localSourceLangCode,
            callerTargetLanguage = localTargetLangCode,
            wifiAwarePort = port,
            sessionNonce = localCallerNonce!!,
        )
        sendCallSignal(peerId, offer)

        // 3. Start Foreground Service
        startCallService(callId, peerName, "Calling...")

        // 4. Set call timeout (e.g. 30 seconds ringing timeout)
        startCallTimeout(callId, 30_000L)

        Log.d(TAG, "Initiated outgoing call $callId to $peerName ($peerId)")
        callId
    }

    /**
     * Accepts an incoming call offer.
     */
    suspend fun acceptCall(callId: String): Boolean = mutex.withLock {
        val offer = _incomingOffer.value
        if (offer == null || offer.callId != callId) {
            Log.e(TAG, "No matching incoming call offer found for $callId")
            return@withLock false
        }

        val session = _currentSession.value ?: return@withLock false
        _incomingOffer.value = null
        _callState.value = CallState.CONNECTING
        session.updateState(CallState.CONNECTING)

        val calleeNonce = CallSecurity.generateNonce()
        localCallerNonce = offer.sessionNonce
        remoteNonce = calleeNonce

        // Derive mutual session key
        val sessionKey = CallSecurity.deriveSessionKey(callId, offer.sessionNonce, calleeNonce)
        val sec = CallSecurity(sessionKey)
        security = sec

        val jb = JitterBuffer()
        jitterBuffer = jb

        val myNodeId = meshController.neighborCount.value.toString()
        val trans = CallMeshTransport(
            scope = scope,
            callId = callId,
            localPeerId = myNodeId,
            remotePeerId = offer.callerNodeId,
            security = sec,
            jitterBuffer = jb,
            onPacketReceived = { packet -> handleInboundCallPacket(packet) },
            onConnectionClosed = { reason -> handleTransportClosed(reason) },
        )
        transport = trans

        // Connect to caller's Wi-Fi Aware server
        val targetHost = offer.wifiAwareIpv6 ?: "127.0.0.1"
        val connected = trans.connect(targetHost, offer.wifiAwarePort)

        // Send Answer Signal back to caller
        val answer = CallSignal.Answer(
            callId = callId,
            calleeNodeId = myNodeId,
            calleeName = "Me",
            calleeSourceLanguage = session.localSourceLanguage,
            calleeTargetLanguage = session.localTargetLanguage,
            accepted = true,
            wifiAwarePort = offer.wifiAwarePort,
            sessionNonce = calleeNonce,
        )
        sendCallSignal(offer.callerNodeId, answer)

        // Start Audio & Processors
        startCallEnginesAndProcessors(session)
        _callState.value = CallState.CONNECTED
        session.updateState(CallState.CONNECTED)

        updateCallServiceNotification(callId, session.peerName, "Connected")
        Log.d(TAG, "Accepted call $callId from ${offer.callerName}")
        true
    }

    /**
     * Declines / Rejects an incoming call offer.
     */
    suspend fun rejectCall(callId: String, reason: CallEndReason = CallEndReason.USER_REJECTED) = mutex.withLock {
        val offer = _incomingOffer.value
        if (offer != null && offer.callId == callId) {
            val answer = CallSignal.Answer(
                callId = callId,
                calleeNodeId = offer.callerNodeId,
                calleeName = "Me",
                calleeSourceLanguage = offer.callerTargetLanguage,
                calleeTargetLanguage = offer.callerSourceLanguage,
                accepted = false,
                sessionNonce = "",
                rejectReason = reason.name,
            )
            sendCallSignal(offer.callerNodeId, answer)
        }
        _incomingOffer.value = null
        cleanupSession(reason)
    }

    /**
     * Ends the currently active call.
     */
    suspend fun endCall(reason: CallEndReason = CallEndReason.USER_HANGUP) = mutex.withLock {
        val session = _currentSession.value ?: return@withLock
        Log.d(TAG, "Ending call ${session.callId}: $reason")

        _callState.value = CallState.ENDING
        session.updateState(CallState.ENDING)

        // Send Hangup signal to peer
        val hangup = CallSignal.Hangup(
            callId = session.callId,
            senderNodeId = session.peerId,
            reason = reason.name,
        )
        sendCallSignal(session.peerId, hangup)

        cleanupSession(reason)
    }

    /**
     * Handles an incoming call signal from the mesh network.
     */
    fun onSignalReceived(signal: CallSignal, fromPeerId: String) {
        scope.launch {
            mutex.withLock {
                when (signal) {
                    is CallSignal.Offer -> handleIncomingOffer(signal, fromPeerId)
                    is CallSignal.Answer -> handleIncomingAnswer(signal, fromPeerId)
                    is CallSignal.Ringing -> handleIncomingRinging(signal)
                    is CallSignal.Hangup -> handleIncomingHangup(signal)
                    is CallSignal.Heartbeat -> {}
                    is CallSignal.TransportCandidate -> {}
                }
            }
        }
    }

    private fun handleIncomingOffer(offer: CallSignal.Offer, fromPeerId: String) {
        if (_callState.value.isActive) {
            // Already in a call: send busy reject
            val reject = CallSignal.Answer(
                callId = offer.callId,
                calleeNodeId = fromPeerId,
                calleeName = "Me",
                calleeSourceLanguage = offer.callerTargetLanguage,
                calleeTargetLanguage = offer.callerSourceLanguage,
                accepted = false,
                sessionNonce = "",
                rejectReason = "BUSY",
            )
            sendCallSignal(fromPeerId, reject)
            return
        }

        val session = CallSession(
            callId = offer.callId,
            peerId = offer.callerNodeId,
            peerName = offer.callerName,
            direction = CallDirection.INCOMING,
            localSourceLanguage = offer.callerTargetLanguage,
            localTargetLanguage = offer.callerSourceLanguage,
            remoteSourceLanguage = offer.callerSourceLanguage,
            remoteTargetLanguage = offer.callerTargetLanguage,
        )
        _currentSession.value = session
        _incomingOffer.value = offer
        _callState.value = CallState.INCOMING
        session.updateState(CallState.INCOMING)

        // Send Ringing acknowledgment back to caller
        val ringing = CallSignal.Ringing(callId = offer.callId, calleeNodeId = fromPeerId)
        sendCallSignal(fromPeerId, ringing)

        // Start Foreground Service with Incoming Call notification
        startCallService(offer.callId, offer.callerName, "Incoming Call...")
        startCallTimeout(offer.callId, 45_000L)

        Log.d(TAG, "Received incoming call offer ${offer.callId} from ${offer.callerName}")
    }

    private suspend fun handleIncomingAnswer(answer: CallSignal.Answer, fromPeerId: String) {
        val session = _currentSession.value ?: return
        if (session.callId != answer.callId) return

        callTimeoutJob?.cancel()

        if (!answer.accepted) {
            Log.d(TAG, "Call offer declined by remote peer: ${answer.rejectReason}")
            cleanupSession(CallEndReason.REMOTE_REJECTED)
            return
        }

        // Derive mutual encryption key
        val callerNonce = localCallerNonce ?: ""
        val sessionKey = CallSecurity.deriveSessionKey(session.callId, callerNonce, answer.sessionNonce)
        val sec = CallSecurity(sessionKey)
        security = sec

        // Connect/bind transport
        _callState.value = CallState.CONNECTED
        session.updateState(CallState.CONNECTED)

        startCallEnginesAndProcessors(session)
        updateCallServiceNotification(session.callId, session.peerName, "Connected")
        Log.d(TAG, "Call ${session.callId} connected successfully with ${session.peerName}")
    }

    private fun handleIncomingRinging(ringing: CallSignal.Ringing) {
        val session = _currentSession.value ?: return
        if (session.callId == ringing.callId && _callState.value == CallState.OUTGOING) {
            _callState.value = CallState.RINGING
            session.updateState(CallState.RINGING)
            updateCallServiceNotification(session.callId, session.peerName, "Ringing...")
        }
    }

    private suspend fun handleIncomingHangup(hangup: CallSignal.Hangup) {
        val session = _currentSession.value ?: return
        if (session.callId == hangup.callId) {
            Log.d(TAG, "Remote peer hung up call ${session.callId}")
            cleanupSession(CallEndReason.REMOTE_HANGUP)
        }
    }

    private suspend fun startCallEnginesAndProcessors(session: CallSession) {
        // 1. Prepare STT for local source language
        val sttLang = SttLanguage.fromCode(session.localSourceLanguage) ?: SttLanguage.HINDI
        sttProcessor?.prepare(sttLang)

        // 2. Setup VAD endpoint detector
        val vad = CallVadEndpointDetector(
            onSpeechSegmentFinalized = { pcmChunk, durationMs ->
                onLocalSpeechSegmentCaptured(session, pcmChunk, durationMs, sttLang)
            }
        )
        vadDetector = vad

        // 3. Start AudioRecord capture and AudioTrack playback
        val audio = CallAudioEngine(
            context = context,
            scope = scope,
            onPcmCaptured = { pcmChunk, length ->
                vad.processPcmChunk(pcmChunk, length)
            }
        )
        audioEngine = audio
        audio.start(speakerphone = true)
    }

    private fun onLocalSpeechSegmentCaptured(
        session: CallSession,
        pcmShorts: ShortArray,
        durationMs: Long,
        language: SttLanguage,
    ) {
        scope.launch(Dispatchers.Default) {
            val transcript = sttProcessor?.processSpeechSegment(
                callId = session.callId,
                speakerNodeId = "me",
                pcmShorts = pcmShorts,
                durationMs = durationMs,
                language = language,
            ) ?: return@launch

            val translation = transProcessor?.translateSegment(
                callId = session.callId,
                speakerNodeId = "me",
                text = transcript.text,
                sourceLangCode = session.localSourceLanguage,
                targetLangCode = session.localTargetLanguage,
            ) ?: return@launch

            // Update local metrics & transcript list
            session.updateMetrics(
                sttMs = transcript.latencyMs,
                transMs = translation.latencyMs,
            )
            session.addTranscript(
                CallTranscriptItem(
                    id = UUID.randomUUID().toString(),
                    speakerName = "You",
                    isLocal = true,
                    originalText = transcript.text,
                    translatedText = translation.translatedText,
                    originalLanguage = session.localSourceLanguage,
                    translatedLanguage = session.localTargetLanguage,
                    totalLatencyMs = transcript.latencyMs + translation.latencyMs,
                )
            )

            // Send encrypted translated segment packet over Wi-Fi Aware to remote peer
            val payloadBytes = translation.translatedText.toByteArray(Charsets.UTF_8)
            transport?.sendPayload(CallPayloadType.TRANSLATED_SEGMENT, payloadBytes)
        }
    }

    private fun handleInboundCallPacket(packet: CallPacket) {
        val session = _currentSession.value ?: return
        if (session.callId != packet.callId) return

        val sec = security ?: return
        if (!sec.validateAndRecordSequence(packet.sequenceNumber)) {
            // Replay or invalid packet
            return
        }

        try {
            val aad = packet.computeAad()
            val decrypted = sec.decrypt(packet.iv, packet.encryptedPayload, aad)

            when (packet.payloadType) {
                CallPayloadType.TRANSLATED_SEGMENT -> {
                    val translatedText = String(decrypted, Charsets.UTF_8)
                    val srcLang = IndicLanguage.fromCode(session.remoteSourceLanguage) ?: IndicLanguage.HINDI
                    val tgtLang = IndicLanguage.fromCode(session.localSourceLanguage) ?: IndicLanguage.MARATHI

                    val remoteTranslation = LiveTranslation(
                        callId = session.callId,
                        speakerNodeId = session.peerId,
                        sourceText = "",
                        translatedText = translatedText,
                        sourceLanguage = srcLang,
                        targetLanguage = tgtLang,
                        latencyMs = 0L,
                    )

                    // 1. Speak translated audio via Android TTS
                    ttsProcessor?.enqueue(remoteTranslation)

                    // 2. Add to transcript display
                    session.addTranscript(
                        CallTranscriptItem(
                            id = UUID.randomUUID().toString(),
                            speakerName = session.peerName,
                            isLocal = false,
                            originalText = "",
                            translatedText = translatedText,
                            originalLanguage = session.remoteSourceLanguage,
                            translatedLanguage = session.localSourceLanguage,
                        )
                    )
                }
                CallPayloadType.VOICE_PCM -> {
                    // Raw audio stream fallback
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting inbound call packet #${packet.sequenceNumber}: ${e.message}")
        }
    }

    private fun handleTransportClosed(reason: String) {
        scope.launch {
            mutex.withLock {
                if (_callState.value.isActive) {
                    Log.w(TAG, "Call transport closed unexpectedly: $reason")
                    cleanupSession(CallEndReason.NETWORK_LOST)
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        audioEngine?.setMuted(muted)
    }

    fun toggleMute() {
        val next = !_isMuted.value
        setMuted(next)
    }

    fun setSpeakerphone(on: Boolean) {
        _isSpeakerOn.value = on
        audioEngine?.setSpeakerphone(on)
    }

    fun toggleSpeaker() {
        val next = !_isSpeakerOn.value
        setSpeakerphone(next)
    }

    suspend fun acceptCall(): Boolean {
        val offer = _incomingOffer.value ?: return false
        return acceptCall(offer.callId)
    }

    suspend fun rejectCall(reason: CallEndReason = CallEndReason.USER_REJECTED) {
        val offer = _incomingOffer.value
        if (offer != null) {
            rejectCall(offer.callId, reason)
        } else {
            endCall(reason)
        }
    }

    private suspend fun cleanupSession(reason: CallEndReason) {
        callTimeoutJob?.cancel()
        callTimeoutJob = null

        val session = _currentSession.value
        session?.endReason = reason
        session?.updateState(CallState.ENDED)

        _callState.value = CallState.ENDED
        _incomingOffer.value = null

        audioEngine?.stop()
        audioEngine = null

        vadDetector?.reset()
        vadDetector = null

        ttsProcessor?.stopAndFlush()
        transport?.close("Call ended ($reason)")
        transport = null

        stopCallService()
        Log.d(TAG, "Call session cleaned up cleanly: $reason")
    }

    private fun sendCallSignal(targetPeerId: String, signal: CallSignal) {
        scope.launch(Dispatchers.IO) {
            try {
                val encodedCbor = CallSignalCodec.encode(signal)
                meshController.sendCallSignal(targetPeerId, encodedCbor)
                val signalType = when (signal) {
                    is CallSignal.Offer -> "OFFER"
                    is CallSignal.Answer -> "ANSWER"
                    is CallSignal.Ringing -> "RINGING"
                    is CallSignal.Hangup -> "HANGUP"
                    is CallSignal.Heartbeat -> "HEARTBEAT"
                    is CallSignal.TransportCandidate -> "CANDIDATE"
                }
                Log.d(TAG, "Sent CallSignal $signalType for call ${signal.callId} to $targetPeerId")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending CallSignal: ${e.message}", e)
            }
        }
    }

    private fun startCallTimeout(callId: String, timeoutMs: Long) {
        callTimeoutJob?.cancel()
        callTimeoutJob = scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(timeoutMs)
            mutex.withLock {
                if (_callState.value == CallState.OUTGOING || _callState.value == CallState.INCOMING || _callState.value == CallState.RINGING) {
                    Log.w(TAG, "Call $callId timed out after ${timeoutMs}ms")
                    cleanupSession(CallEndReason.TIMEOUT)
                }
            }
        }
    }

    private fun startCallService(callId: String, peerName: String, statusText: String) {
        val intent = Intent(context, LiveCallService::class.java).apply {
            action = LiveCallService.ACTION_START_CALL
            putExtra(LiveCallService.EXTRA_CALL_ID, callId)
            putExtra(LiveCallService.EXTRA_PEER_NAME, peerName)
            putExtra(LiveCallService.EXTRA_STATUS_TEXT, statusText)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed starting LiveCallService: ${e.message}")
        }
    }

    private fun updateCallServiceNotification(callId: String, peerName: String, statusText: String) {
        val intent = Intent(context, LiveCallService::class.java).apply {
            action = LiveCallService.ACTION_UPDATE_STATUS
            putExtra(LiveCallService.EXTRA_CALL_ID, callId)
            putExtra(LiveCallService.EXTRA_PEER_NAME, peerName)
            putExtra(LiveCallService.EXTRA_STATUS_TEXT, statusText)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun stopCallService() {
        val intent = Intent(context, LiveCallService::class.java).apply {
            action = LiveCallService.ACTION_STOP_CALL
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }
}
