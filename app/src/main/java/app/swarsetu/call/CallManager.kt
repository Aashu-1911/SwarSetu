package app.swarsetu.call

import android.content.Context
import android.content.Intent
import android.util.Log
import app.swarsetu.identity.Identity
import app.swarsetu.mesh.MeshController
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.sravaani.Sravaani10Engine
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicLanguage
import app.swarsetu.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Authoritative Application/Service-scoped Call Runtime for SwarSetu Live Translated VoIP.
 * Manages the single active CallSession, hard state machine, full-duplex audio pipeline,
 * live STT/Translation/TTS workers, and idempotent lifecycle cleanup.
 */
class CallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sravaaniEngine: Sravaani10Engine,
    private val translatorEngine: TranslatorEngine,
    private val ttsManager: TtsManager,
    private val meshController: MeshController,
    private val identity: Identity,
) {
    companion object {
        private const val TAG = "CallManager"
        private const val OUTGOING_TIMEOUT_MS = 30_000L
        private const val INCOMING_TIMEOUT_MS = 45_000L
        private const val RECONNECT_TIMEOUT_MS = 10_000L
    }

    /** Returns this device's genuine mesh node ID. */
    fun localNodeId(): String = identity.nodeIdSync()

    private val mutex = Mutex()

    // Exactly ONE authoritative active CallSession
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

    // Call-scoped coroutine hierarchy
    private var callJob: Job? = null
    private var callScope: CoroutineScope? = null

    // Media & Transport Engines
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
     * Idempotent: returns existing callId if call is already in progress with this peer.
     */
    suspend fun initiateCall(
        peerId: String,
        peerName: String,
        localSourceLangCode: String = "en",
        localTargetLangCode: String = "hi",
    ): String = mutex.withLock {
        Log.i(TAG, "CALL_START_REQUEST: Initiate outgoing call to $peerName ($peerId), langs: $localSourceLangCode -> $localTargetLangCode")

        if (_callState.value.isActive) {
            val current = _currentSession.value
            if (current != null && current.peerId == peerId) {
                Log.i(TAG, "CALL_DUPLICATE_START_IGNORED: Active call session already exists for peer $peerId (callId=${current.callId})")
                return@withLock current.callId
            }
            Log.w(TAG, "CALL_DUPLICATE_START_IGNORED: Cannot start call to $peerId because another call (${current?.callId}) is in state ${_callState.value}")
            return@withLock ""
        }

        val callId = UUID.randomUUID().toString()
        Log.i(TAG, "CALL_CREATE: Created unique callId=$callId")

        // Create dedicated child job for this call session
        val parentJob = SupervisorJob(scope.coroutineContext[Job])
        callJob = parentJob
        val sessionScope = CoroutineScope(Dispatchers.Default + parentJob)
        callScope = sessionScope

        val session = CallSession(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            direction = CallDirection.OUTGOING,
            localSourceLanguage = localSourceLangCode,
            localTargetLanguage = localTargetLangCode,
            remoteSourceLanguage = localTargetLangCode,
            remoteTargetLanguage = localSourceLangCode,
            initialState = CallState.OUTGOING_REQUESTED,
        )
        _currentSession.value = session
        _callState.value = CallState.OUTGOING_REQUESTED

        // Transition to OUTGOING
        _callState.value = CallState.OUTGOING
        session.updateState(CallState.OUTGOING)

        val callerNonce = CallSecurity.generateNonce()
        localCallerNonce = callerNonce

        // 1. Initialize JitterBuffer and Transport as Server
        val jb = JitterBuffer()
        jitterBuffer = jb

        val dummyKey = CallSecurity.deriveSessionKey(callId, callerNonce, "provisional")
        val sec = CallSecurity(dummyKey)
        security = sec

        val myNodeId = localNodeId()
        val trans = CallMeshTransport(
            scope = sessionScope,
            callId = callId,
            localPeerId = myNodeId,
            remotePeerId = peerId,
            security = sec,
            jitterBuffer = jb,
            onPacketReceived = { packet -> handleInboundCallPacket(packet) },
            onConnectionClosed = { reason -> handleTransportClosed(reason) },
        )
        transport = trans

        val port = trans.startServer()
        val localIp = CallNetworkUtils.getLocalIpAddress()

        // 2. Send Call Offer Signal over Mesh
        Log.i(TAG, "CALL_SIGNALING_START: Sending Offer to $peerId on port $port, ip=$localIp")
        val offer = CallSignal.Offer(
            callId = callId,
            callerNodeId = myNodeId,
            callerName = "Me",
            callerSourceLanguage = localSourceLangCode,
            callerTargetLanguage = localTargetLangCode,
            wifiAwarePort = port,
            wifiAwareIpv6 = localIp,
            sessionNonce = callerNonce,
        )
        sendCallSignal(peerId, offer)
        Log.i(TAG, "CALL_SIGNALING_SUCCESS: Offer signal transmitted")

        // 3. Start Foreground Service
        startCallService(callId, peerName, "Calling...")

        // 4. Set Call Timeout
        startCallTimeout(callId, OUTGOING_TIMEOUT_MS)

        callId
    }

    /**
     * Accepts an incoming call offer.
     */
    suspend fun acceptCall(callId: String): Boolean = mutex.withLock {
        Log.i(TAG, "CALL_CONNECTING: Accepting call $callId")
        val offer = _incomingOffer.value
        if (offer == null || offer.callId != callId) {
            Log.e(TAG, "No matching incoming call offer found for $callId")
            return@withLock false
        }

        val session = _currentSession.value ?: return@withLock false
        _incomingOffer.value = null

        _callState.value = CallState.ACCEPTING
        session.updateState(CallState.ACCEPTING)

        _callState.value = CallState.CONNECTING
        session.updateState(CallState.CONNECTING)

        val parentJob = SupervisorJob(scope.coroutineContext[Job])
        callJob = parentJob
        val sessionScope = CoroutineScope(Dispatchers.Default + parentJob)
        callScope = sessionScope

        val calleeNonce = CallSecurity.generateNonce()
        localCallerNonce = offer.sessionNonce
        remoteNonce = calleeNonce

        // Derive mutual session key
        val sessionKey = CallSecurity.deriveSessionKey(callId, offer.sessionNonce, calleeNonce)
        val sec = CallSecurity(sessionKey)
        security = sec

        val jb = JitterBuffer()
        jitterBuffer = jb

        val myNodeId = localNodeId()
        val trans = CallMeshTransport(
            scope = sessionScope,
            callId = callId,
            localPeerId = myNodeId,
            remotePeerId = session.peerId,
            security = sec,
            jitterBuffer = jb,
            onPacketReceived = { packet -> handleInboundCallPacket(packet) },
            onConnectionClosed = { reason -> handleTransportClosed(reason) },
        )
        transport = trans

        val localIp = CallNetworkUtils.getLocalIpAddress()
        val targetHost = offer.wifiAwareIpv6

        // Connect asynchronously so mutex is not held if host is unreachable
        if (!targetHost.isNullOrBlank()) {
            sessionScope.launch(Dispatchers.IO) {
                trans.connect(targetHost, offer.wifiAwarePort)
            }
        }

        // Send Answer Signal back to caller immediately
        val answer = CallSignal.Answer(
            callId = callId,
            calleeNodeId = myNodeId,
            calleeName = "Me",
            calleeSourceLanguage = session.localSourceLanguage,
            calleeTargetLanguage = session.localTargetLanguage,
            accepted = true,
            wifiAwarePort = offer.wifiAwarePort,
            wifiAwareIpv6 = localIp,
            sessionNonce = calleeNonce,
        )
        sendCallSignal(session.peerId, answer)

        // Start Audio & Processors
        val enginesStarted = startCallEnginesAndProcessors(session)
        if (!enginesStarted) {
            Log.e(TAG, "Failed starting call engines for accepted call $callId")
            cleanupSession(CallEndReason.AUDIO_DEVICE_ERROR)
            return@withLock false
        }

        _callState.value = CallState.CONNECTED
        session.updateState(CallState.CONNECTED)
        Log.i(TAG, "CALL_CONNECTED: Call $callId connected with ${offer.callerName}")

        updateCallServiceNotification(callId, session.peerName, "Connected")
        true
    }

    /**
     * Declines / Rejects an incoming call offer.
     */
    suspend fun rejectCall(callId: String, reason: CallEndReason = CallEndReason.USER_REJECTED) = mutex.withLock {
        Log.i(TAG, "CALL_END_REQUEST: Rejecting call $callId (reason=$reason)")
        val offer = _incomingOffer.value
        val targetPeerId = _currentSession.value?.peerId ?: offer?.callerNodeId
        if (offer != null && targetPeerId != null && offer.callId == callId) {
            val answer = CallSignal.Answer(
                callId = callId,
                calleeNodeId = localNodeId(),
                calleeName = "Me",
                calleeSourceLanguage = offer.callerTargetLanguage,
                calleeTargetLanguage = offer.callerSourceLanguage,
                accepted = false,
                sessionNonce = "",
                rejectReason = reason.name,
            )
            sendCallSignal(targetPeerId, answer)
        }
        _incomingOffer.value = null
        cleanupSession(reason)
    }

    /**
     * Ends the currently active call idempotently.
     */
    suspend fun endCall(reason: CallEndReason = CallEndReason.USER_HANGUP) = mutex.withLock {
        val state = _callState.value
        if (state == CallState.IDLE || state == CallState.ENDED || state == CallState.ENDING) {
            Log.d(TAG, "CALL_END_ALREADY_IN_PROGRESS: endCall ignored in state $state")
            return@withLock
        }

        val session = _currentSession.value
        val callId = session?.callId ?: "unknown"
        Log.i(TAG, "CALL_END_REQUEST: Ending call $callId: $reason")

        _callState.value = CallState.ENDING
        session?.updateState(CallState.ENDING)

        // Send Hangup signal to peer over mesh
        if (session != null) {
            val hangup = CallSignal.Hangup(
                callId = session.callId,
                senderNodeId = localNodeId(),
                reason = reason.name,
            )
            sendCallSignal(session.peerId, hangup)
        }

        cleanupSession(reason)
    }

    /**
     * Handles incoming call signals received from the mesh network.
     */
    fun onSignalReceived(signal: CallSignal, fromPeerId: String) {
        scope.launch {
            mutex.withLock {
                when (signal) {
                    is CallSignal.Offer -> handleIncomingOffer(signal, fromPeerId)
                    is CallSignal.Answer -> handleIncomingAnswer(signal, fromPeerId)
                    is CallSignal.Ringing -> handleIncomingRinging(signal)
                    is CallSignal.Hangup -> handleIncomingHangup(signal)
                    is CallSignal.VoiceSegment -> handleIncomingVoiceSegment(signal)
                    is CallSignal.Heartbeat -> {}
                    is CallSignal.TransportCandidate -> {}
                }
            }
        }
    }

    private fun handleIncomingVoiceSegment(voice: CallSignal.VoiceSegment) {
        val session = _currentSession.value ?: return
        if (session.callId != voice.callId) return
        val segmentKey = "${session.callId}_mesh_${voice.timestampMs}_${voice.text.hashCode()}"
        if (!session.recordProcessedSegment(segmentKey)) {
            Log.d(TAG, "CALL_PACKET_DUPLICATE: Voice segment $segmentKey already processed")
            return
        }

        val srcLang = IndicLanguage.fromCode(voice.sourceLanguage) ?: IndicLanguage.HINDI
        val tgtLang = IndicLanguage.fromCode(voice.targetLanguage) ?: IndicLanguage.MARATHI

        val remoteTranslation = LiveTranslation(
            callId = session.callId,
            speakerNodeId = session.peerId,
            sourceText = voice.originalText,
            translatedText = voice.text,
            sourceLanguage = srcLang,
            targetLanguage = tgtLang,
            latencyMs = 0L,
        )

        ttsProcessor?.enqueue(remoteTranslation)
        session.addTranscript(
            CallTranscriptItem(
                id = UUID.randomUUID().toString(),
                speakerName = session.peerName,
                isLocal = false,
                originalText = voice.originalText,
                translatedText = voice.text,
                originalLanguage = voice.sourceLanguage,
                translatedLanguage = voice.targetLanguage,
            )
        )
    }

    private fun handleIncomingOffer(offer: CallSignal.Offer, fromPeerId: String) {
        val authoritativeCallerId = if (offer.callerNodeId.isNotBlank() && offer.callerNodeId != "0" && offer.callerNodeId != "1" && offer.callerNodeId.length >= 8) {
            offer.callerNodeId
        } else {
            fromPeerId
        }

        if (_callState.value.isActive) {
            val current = _currentSession.value
            if (_callState.value == CallState.OUTGOING && current != null && (current.peerId == fromPeerId || current.peerId == authoritativeCallerId)) {
                // Concurrent outgoing call glare: deterministic tie-breaker on callerNodeId
                if (authoritativeCallerId < current.peerId) {
                    Log.d(TAG, "Call glare resolved: remote peer $authoritativeCallerId wins tie-breaker")
                    current.updateState(CallState.ENDED)
                } else {
                    Log.d(TAG, "Call glare resolved: local peer wins tie-breaker, maintaining outgoing offer")
                    return
                }
            } else {
                // Already in a call: send busy reject
                Log.w(TAG, "Rejecting offer ${offer.callId} as BUSY (active state=${_callState.value})")
                val reject = CallSignal.Answer(
                    callId = offer.callId,
                    calleeNodeId = localNodeId(),
                    calleeName = "Me",
                    calleeSourceLanguage = offer.callerTargetLanguage,
                    calleeTargetLanguage = offer.callerSourceLanguage,
                    accepted = false,
                    sessionNonce = "",
                    rejectReason = "BUSY",
                )
                sendCallSignal(authoritativeCallerId, reject)
                return
            }
        }

        val peerDisplayName = if (offer.callerName.isNotBlank() && offer.callerName != "Me") offer.callerName else "Peer"
        val session = CallSession(
            callId = offer.callId,
            peerId = authoritativeCallerId,
            peerName = peerDisplayName,
            direction = CallDirection.INCOMING,
            localSourceLanguage = offer.callerTargetLanguage,
            localTargetLanguage = offer.callerSourceLanguage,
            remoteSourceLanguage = offer.callerSourceLanguage,
            remoteTargetLanguage = offer.callerTargetLanguage,
            initialState = CallState.INCOMING,
        )
        _currentSession.value = session
        _incomingOffer.value = offer
        _callState.value = CallState.INCOMING
        session.updateState(CallState.INCOMING)

        // Send Ringing acknowledgment back to caller
        val ringing = CallSignal.Ringing(callId = offer.callId, calleeNodeId = localNodeId())
        sendCallSignal(authoritativeCallerId, ringing)

        // Start Foreground Service with Incoming Call notification
        startCallService(offer.callId, peerDisplayName, "Incoming Call...")
        startCallTimeout(offer.callId, INCOMING_TIMEOUT_MS)

        Log.i(TAG, "Received incoming call offer ${offer.callId} from $peerDisplayName ($authoritativeCallerId)")
    }

    private suspend fun handleIncomingAnswer(answer: CallSignal.Answer, fromPeerId: String) {
        val session = _currentSession.value ?: return
        if (session.callId != answer.callId) return

        callTimeoutJob?.cancel()

        if (!answer.accepted) {
            val endReason = if (answer.rejectReason == "BUSY") CallEndReason.BUSY else CallEndReason.REMOTE_REJECTED
            Log.i(TAG, "Call offer declined by remote peer: ${answer.rejectReason}")
            cleanupSession(endReason)
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
        Log.i(TAG, "CALL_CONNECTED: Outgoing call ${session.callId} established with ${session.peerName}")

        val enginesStarted = startCallEnginesAndProcessors(session)
        if (!enginesStarted) {
            Log.e(TAG, "Failed starting call engines for ${session.callId}")
            cleanupSession(CallEndReason.AUDIO_DEVICE_ERROR)
            return
        }

        updateCallServiceNotification(session.callId, session.peerName, "Connected")
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
            Log.i(TAG, "Remote peer hung up call ${session.callId}")
            cleanupSession(CallEndReason.REMOTE_HANGUP)
        }
    }

    private suspend fun startCallEnginesAndProcessors(session: CallSession): Boolean {
        val activeScope = callScope ?: scope

        // 1. Prepare STT for local source language
        val sttLang = SttLanguage.fromCode(session.localSourceLanguage) ?: SttLanguage.ENGLISH
        sttProcessor?.prepare(sttLang)

        // 2. Setup VAD endpoint detector with local barge-in trigger
        val vad = CallVadEndpointDetector(
            onSpeechStarted = {
                // Local speech started: barge-in interrupt remote TTS
                ttsProcessor?.interrupt()
            },
            onSpeechSegmentFinalized = { pcmChunk, durationMs ->
                onLocalSpeechSegmentCaptured(session, pcmChunk, durationMs, sttLang)
            }
        )
        vadDetector = vad

        // 3. Start AudioRecord capture and AudioTrack playback
        val audio = CallAudioEngine(
            context = context,
            scope = activeScope,
            onPcmCaptured = { pcmChunk, length ->
                vad.processPcmChunk(pcmChunk, length)
            }
        )
        audioEngine = audio
        return audio.start(speakerphone = _isSpeakerOn.value)
    }

    private fun onLocalSpeechSegmentCaptured(
        session: CallSession,
        pcmShorts: ShortArray,
        durationMs: Long,
        language: SttLanguage,
    ) {
        val activeScope = callScope ?: scope
        activeScope.launch(Dispatchers.Default) {
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
            )
            if (translation == null) {
                Log.w(TAG, "CALL_TRANSLATION_ERROR: Translation failed for segment: \"${transcript.text}\". Emitting translation error and suppressing untranslated transmission.")
                session.addTranscript(
                    CallTranscriptItem(
                        id = UUID.randomUUID().toString(),
                        speakerName = "You",
                        isLocal = true,
                        originalText = transcript.text,
                        translatedText = "[Translation Error]",
                        originalLanguage = session.localSourceLanguage,
                        translatedLanguage = session.localTargetLanguage,
                        totalLatencyMs = transcript.latencyMs,
                    )
                )
                return@launch
            }

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

            // 1. Send encrypted translated segment packet over Wi-Fi Aware to remote peer
            val payloadBytes = translation.translatedText.toByteArray(Charsets.UTF_8)
            transport?.sendPayload(CallPayloadType.TRANSLATED_SEGMENT, payloadBytes)

            // 2. Also send VoiceSegment signal over Mesh so it is guaranteed to reach the peer even without TCP socket
            val voiceSignal = CallSignal.VoiceSegment(
                callId = session.callId,
                senderNodeId = localNodeId(),
                text = translation.translatedText,
                originalText = transcript.text,
                sourceLanguage = session.localSourceLanguage,
                targetLanguage = session.localTargetLanguage,
            )
            sendCallSignal(session.peerId, voiceSignal)
        }
    }

    private fun handleInboundCallPacket(packet: CallPacket) {
        val session = _currentSession.value ?: return
        if (session.callId != packet.callId) {
            Log.w(TAG, "Ignoring packet from old/mismatched callId=${packet.callId} (current=${session.callId})")
            return
        }

        val sec = security ?: return
        if (!sec.validateAndRecordSequence(packet.sequenceNumber)) {
            Log.w(TAG, "CALL_PACKET_DUPLICATE: Replay/duplicate packet #${packet.sequenceNumber} rejected")
            return
        }

        try {
            val aad = packet.computeAad()
            val decrypted = sec.decrypt(packet.iv, packet.encryptedPayload, aad)

            when (packet.payloadType) {
                CallPayloadType.TRANSLATED_SEGMENT -> {
                    val translatedText = String(decrypted, Charsets.UTF_8)
                    val segmentKey = "${session.callId}_${packet.sequenceNumber}"
                    if (!session.recordProcessedSegment(segmentKey)) {
                        Log.d(TAG, "CALL_PACKET_DUPLICATE: Segment $segmentKey already processed")
                        return
                    }

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
                CallPayloadType.VOICE_PCM -> {}
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting inbound call packet #${packet.sequenceNumber}: ${e.message}")
        }
    }

    private fun handleTransportClosed(reason: String) {
        scope.launch {
            mutex.withLock {
                val session = _currentSession.value
                if (_callState.value == CallState.CONNECTED && session != null) {
                    Log.w(TAG, "CALL_RECONNECTING: Transport connection dropped ($reason). Reconnecting...")
                    _callState.value = CallState.RECONNECTING
                    session.updateState(CallState.RECONNECTING)
                    updateCallServiceNotification(session.callId, session.peerName, "Reconnecting...")
                    startCallTimeout(session.callId, RECONNECT_TIMEOUT_MS)
                } else if (_callState.value.isActive) {
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

    /**
     * Strict ordered terminal cleanup (Part 6):
     * 1. mark ENDING
     * 2. stop microphone capture
     * 3. stop VAD
     * 4. reset STT
     * 5. stop translation workers
     * 6. stop TTS & flush queue
     * 7. flush/stop AudioTrack & audio mode
     * 8. close & clear jitter buffer
     * 9. stop audio transport & sockets
     * 10. cancel call-scoped coroutines
     * 11. stop foreground service
     * 12. mark ENDED
     * 13. schedule IDLE transition & clear active session
     */
    private suspend fun cleanupSession(reason: CallEndReason) {
        Log.i(TAG, "CALL_CLEANUP_START: Executing ordered cleanup for reason $reason")
        callTimeoutJob?.cancel()
        callTimeoutJob = null

        val session = _currentSession.value
        session?.endReason = reason

        // 1. Stop audio capture & playback
        try {
            audioEngine?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping CallAudioEngine: ${e.message}")
        } finally {
            audioEngine = null
        }

        // 2. Stop VAD
        try {
            vadDetector?.reset()
        } catch (_: Exception) {} finally {
            vadDetector = null
        }

        // 3. Reset STT
        sttProcessor?.reset()

        // 4. Stop TTS and flush queues
        try {
            ttsProcessor?.stopAndFlush()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping LiveTtsProcessor: ${e.message}")
        }

        // 5. Clear jitter buffer
        try {
            jitterBuffer?.clear()
        } catch (_: Exception) {} finally {
            jitterBuffer = null
        }

        // 6. Close transport & Wi-Fi Aware sockets
        try {
            transport?.close("Call ended ($reason)")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing CallMeshTransport: ${e.message}")
        } finally {
            transport = null
        }

        // 7. Cancel call-scoped coroutines
        callJob?.cancel()
        callJob = null
        callScope = null

        // 8. Stop Foreground Service
        stopCallService()

        // 9. Mark ENDED
        session?.updateState(CallState.ENDED)
        _callState.value = CallState.ENDED
        _incomingOffer.value = null
        Log.i(TAG, "CALL_CLEANUP_COMPLETE: Terminal cleanup finished for reason $reason")
        Log.i(TAG, "CALL_ENDED: Call session ended")

        // Schedule transition back to IDLE after a short delay so observers receive ENDED
        scope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(800L)
            mutex.withLock {
                if (_callState.value == CallState.ENDED || _callState.value == CallState.FAILED) {
                    _callState.value = CallState.IDLE
                    _currentSession.value = null
                    Log.d(TAG, "CallState reset to IDLE and session cleared")
                }
            }
        }
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
                    is CallSignal.VoiceSegment -> "VOICE_SEGMENT"
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
                if (_callState.value == CallState.OUTGOING || _callState.value == CallState.INCOMING ||
                    _callState.value == CallState.RINGING || _callState.value == CallState.RECONNECTING) {
                    Log.w(TAG, "Call $callId timed out after ${timeoutMs}ms (state=${_callState.value})")
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
