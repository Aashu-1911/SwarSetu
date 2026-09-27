package app.swarsetu.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Item in the live call transcript display.
 */
data class CallTranscriptItem(
    val id: String,
    val speakerName: String,
    val isLocal: Boolean,
    val originalText: String,
    val translatedText: String,
    val originalLanguage: String,
    val translatedLanguage: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val totalLatencyMs: Long? = null,
)

/**
 * Real-time performance metrics for an active Live Translated Call.
 */
data class CallMetrics(
    val setupLatencyMs: Long = 0L,
    val sttLatencyMs: Long = 0L,
    val translationLatencyMs: Long = 0L,
    val ttsLatencyMs: Long = 0L,
    val networkRttMs: Long = 0L,
    val jitterMs: Double = 0.0,
    val packetLossPercent: Float = 0f,
    val totalSpeechLatencyMs: Long = 0L,
    val utterancesProcessed: Int = 0,
) {
    val totalTranslatedMs: Long get() = totalSpeechLatencyMs
    val lastSttLatencyMs: Long get() = sttLatencyMs
    val lastTranslationLatencyMs: Long get() = translationLatencyMs
}

/**
 * Authoritative Active Live Translated Call session state and metric trackers.
 */
class CallSession(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val direction: CallDirection,
    val localSourceLanguage: String, // Language I speak (e.g. "mr")
    val localTargetLanguage: String, // Language remote peer speaks (e.g. "hi")
    val remoteSourceLanguage: String,
    val remoteTargetLanguage: String,
    val startTimeMs: Long = System.currentTimeMillis(),
    initialState: CallState = if (direction == CallDirection.OUTGOING) CallState.OUTGOING_REQUESTED else CallState.INCOMING,
) {
    val sourceLanguage: String get() = localSourceLanguage
    val targetLanguage: String get() = localTargetLanguage

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(CallMetrics())
    val metrics: StateFlow<CallMetrics> = _metrics.asStateFlow()

    private val _transcripts = MutableStateFlow<List<CallTranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<CallTranscriptItem>> = _transcripts.asStateFlow()

    private val processedSegmentIds = ConcurrentHashMap.newKeySet<String>()

    var connectedTimeMs: Long? = null
    val connectedAt: Long get() = connectedTimeMs ?: startTimeMs
    var endTimeMs: Long? = null
    var endReason: CallEndReason? = null

    val durationSeconds: Long
        get() {
            val start = connectedTimeMs ?: return 0L
            val end = endTimeMs ?: System.currentTimeMillis()
            return maxOf(0L, (end - start) / 1000L)
        }

    fun updateState(newState: CallState): Boolean {
        val current = _state.value
        if (!CallState.validateAndLogTransition(current, newState, callId)) {
            return false
        }
        _state.value = newState
        if (newState == CallState.CONNECTED && connectedTimeMs == null) {
            connectedTimeMs = System.currentTimeMillis()
        } else if (newState == CallState.ENDED || newState == CallState.FAILED) {
            if (endTimeMs == null) {
                endTimeMs = System.currentTimeMillis()
            }
        }
        return true
    }

    fun recordProcessedSegment(segmentId: String): Boolean {
        return processedSegmentIds.add(segmentId)
    }

    fun isSegmentProcessed(segmentId: String): Boolean {
        return processedSegmentIds.contains(segmentId)
    }

    fun addTranscript(item: CallTranscriptItem) {
        _transcripts.update { current -> current + item }
    }

    fun updateMetrics(
        sttMs: Long? = null,
        transMs: Long? = null,
        ttsMs: Long? = null,
        rttMs: Long? = null,
        jitter: Double? = null,
        lossPct: Float? = null,
    ) {
        _metrics.update { cur ->
            val newStt = sttMs ?: cur.sttLatencyMs
            val newTrans = transMs ?: cur.translationLatencyMs
            val newTts = ttsMs ?: cur.ttsLatencyMs
            val newRtt = rttMs ?: cur.networkRttMs
            val newJitter = jitter ?: cur.jitterMs
            val newLoss = lossPct ?: cur.packetLossPercent

            val totalSpeech = newStt + newTrans + newTts + (newRtt / 2)
            cur.copy(
                sttLatencyMs = newStt,
                translationLatencyMs = newTrans,
                ttsLatencyMs = newTts,
                networkRttMs = newRtt,
                jitterMs = newJitter,
                packetLossPercent = newLoss,
                totalSpeechLatencyMs = totalSpeech,
                utterancesProcessed = if (sttMs != null) cur.utterancesProcessed + 1 else cur.utterancesProcessed,
            )
        }
    }
}
