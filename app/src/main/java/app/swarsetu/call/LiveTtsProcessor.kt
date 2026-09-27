package app.swarsetu.call

import android.util.Log
import app.swarsetu.tts.TtsLanguage
import app.swarsetu.tts.TtsManager
import app.swarsetu.tts.TtsPriority
import app.swarsetu.tts.TtsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles Text-to-Speech playback for incoming translated call segments.
 * Queues speech utterances in sequence, prevents overlapping TTS, bounds latency, and flushes on call end.
 */
class LiveTtsProcessor(
    private val ttsManager: TtsManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "LiveTtsProcessor"
        private const val TTS_SYNTHESIS_TIMEOUT_MS = 4500L
        private const val MAX_QUEUE_DEPTH = 4
    }

    // Bounded channel to prevent queue accumulation and stale audio lag
    private val speechQueue = Channel<LiveTranslation>(
        capacity = MAX_QUEUE_DEPTH,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var playbackJob: Job? = null
    private val isSpeaking = AtomicBoolean(false)
    private val pendingQueueDepth = AtomicInteger(0)
    private val totalDroppedSegments = AtomicInteger(0)
    private val totalInterruptedSegments = AtomicInteger(0)

    init {
        startQueueConsumer()
    }

    private fun startQueueConsumer() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val translation = speechQueue.receive()
                pendingQueueDepth.decrementAndGet()
                speakTranslation(translation)
            }
        }
    }

    /**
     * Enqueues a translated segment for speech synthesis.
     */
    fun enqueue(translation: LiveTranslation) {
        if (translation.translatedText.isBlank()) return
        val depth = pendingQueueDepth.get()
        val segId = if (translation.segmentId.isNotBlank()) translation.segmentId else "seg_${System.currentTimeMillis()}"
        
        if (depth >= MAX_QUEUE_DEPTH) {
            val dropped = totalDroppedSegments.incrementAndGet()
            Log.w(TAG, "CALL_TTS_QUEUE: Queue overflow (depth=$depth >= $MAX_QUEUE_DEPTH). Dropped oldest segment. Total dropped=$dropped")
        }

        pendingQueueDepth.incrementAndGet()
        Log.i(TAG, "CALL_TTS_QUEUE: [seg=$segId, depth=${pendingQueueDepth.get()}] Enqueuing \"${translation.translatedText}\"")
        speechQueue.trySend(translation)
    }

    private suspend fun speakTranslation(translation: LiveTranslation) {
        val text = translation.translatedText.trim()
        if (text.isBlank()) return

        val langCode = translation.targetLanguage.code
        val ttsLang = TtsLanguage.fromLanguageCode(langCode, text) ?: TtsLanguage.HINDI
        val segId = if (translation.segmentId.isNotBlank()) translation.segmentId else "seg_${System.currentTimeMillis()}"

        val startTime = System.currentTimeMillis()
        try {
            isSpeaking.set(true)
            Log.i(TAG, "CALL_TTS_START: [seg=$segId, lang=${ttsLang.displayName}] \"$text\"")
            val request = TtsRequest(
                requestId = UUID.randomUUID().toString(),
                text = text,
                language = ttsLang,
                priority = TtsPriority.NORMAL,
            )

            withTimeoutOrNull(TTS_SYNTHESIS_TIMEOUT_MS) {
                ttsManager.speak(request)
            }

            val ttsLatency = System.currentTimeMillis() - startTime
            Log.i(TAG, "CALL_TTS_COMPLETE: [seg=$segId] Speech finished in ${ttsLatency}ms for \"$text\"")
            Log.i(TAG, "CALL_TTS_LATENCY: ${ttsLatency}ms")
        } catch (e: Exception) {
            Log.e(TAG, "CALL_TTS_ERROR: [seg=$segId] Error playing TTS for translated text: ${e.message}", e)
        } finally {
            isSpeaking.set(false)
        }
    }

    /**
     * Interrupts currently playing speech (barge-in).
     */
    fun interrupt() {
        try {
            ttsManager.stopAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS playback: ${e.message}")
        }
        var drainedCount = 0
        while (speechQueue.tryReceive().isSuccess) {
            drainedCount++
            pendingQueueDepth.decrementAndGet()
        }
        val interrupted = totalInterruptedSegments.addAndGet(if (isSpeaking.get()) drainedCount + 1 else drainedCount)
        isSpeaking.set(false)
        Log.i(TAG, "CALL_TTS_INTERRUPT: Barge-in interrupted speech (drained $drainedCount queued items, total interrupted=$interrupted)")
    }

    /**
     * Stops all speech and releases playback queue.
     */
    fun stopAndFlush() {
        interrupt()
    }
}
