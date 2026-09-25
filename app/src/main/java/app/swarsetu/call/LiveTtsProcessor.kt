package app.swarsetu.call

import android.util.Log
import app.swarsetu.tts.TtsLanguage
import app.swarsetu.tts.TtsManager
import app.swarsetu.tts.TtsPriority
import app.swarsetu.tts.TtsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles Text-to-Speech playback for incoming translated call segments.
 * Queues speech utterances in sequence, prevents overlapping TTS, and flushes on call end.
 */
class LiveTtsProcessor(
    private val ttsManager: TtsManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "LiveTtsProcessor"
    }

    private val speechQueue = Channel<LiveTranslation>(Channel.UNLIMITED)
    private var playbackJob: Job? = null

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
        val text = translation.translatedText
        val langCode = translation.targetLanguage.code
        val ttsLang = TtsLanguage.fromLanguageCode(langCode, text) ?: TtsLanguage.HINDI

        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "Speaking translated segment [${ttsLang.displayName}]: \"$text\"")
            val request = TtsRequest(
                requestId = UUID.randomUUID().toString(),
                text = text,
                language = ttsLang,
                priority = TtsPriority.NORMAL,
            )
            ttsManager.speak(request)
            val ttsLatency = System.currentTimeMillis() - startTime
            Log.d(TAG, "TTS synthesis completed in ${ttsLatency}ms for \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing TTS for translated text: ${e.message}", e)
        }
    }

    /**
     * Interrupts currently playing speech (barge-in).
     */
    fun interrupt() {
        ttsManager.stopAll()
        // Drain pending queue
        while (speechQueue.tryReceive().isSuccess) {
            // discarded
        }
        Log.d(TAG, "TTS playback interrupted and speech queue drained")
    }

    /**
     * Stops all speech and releases playback queue.
     */
    fun stopAndFlush() {
        interrupt()
    }
}
