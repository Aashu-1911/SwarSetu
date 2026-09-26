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
        private const val MAX_QUEUE_DEPTH = 3
    }

    // Bounded channel to prevent queue accumulation and stale audio lag
    private val speechQueue = Channel<LiveTranslation>(
        capacity = MAX_QUEUE_DEPTH,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var playbackJob: Job? = null
    private val isSpeaking = AtomicBoolean(false)

    init {
        startQueueConsumer()
    }

    private fun startQueueConsumer() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val translation = speechQueue.receive()
                speakTranslation(translation)
            }
        }
    }

    /**
     * Enqueues a translated segment for speech synthesis.
     */
    fun enqueue(translation: LiveTranslation) {
        if (translation.translatedText.isBlank()) return
        speechQueue.trySend(translation)
    }

    private suspend fun speakTranslation(translation: LiveTranslation) {
        val text = translation.translatedText.trim()
        if (text.isBlank()) return

        val langCode = translation.targetLanguage.code
        val ttsLang = TtsLanguage.fromLanguageCode(langCode, text) ?: TtsLanguage.HINDI

        val startTime = System.currentTimeMillis()
        try {
            isSpeaking.set(true)
            Log.d(TAG, "Speaking translated segment [${ttsLang.displayName}]: \"$text\"")
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
            Log.d(TAG, "TTS synthesis completed in ${ttsLatency}ms for \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing TTS for translated text: ${e.message}", e)
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
        // Drain pending queue
        while (speechQueue.tryReceive().isSuccess) {
            // discarded
        }
        isSpeaking.set(false)
        Log.d(TAG, "TTS playback interrupted and speech queue drained")
    }

    /**
     * Stops all speech and releases playback queue.
     */
    fun stopAndFlush() {
        interrupt()
    }
}
