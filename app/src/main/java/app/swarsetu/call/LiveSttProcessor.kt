package app.swarsetu.call

import android.util.Log
import app.swarsetu.stt.SttConfig
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.sravaani.Sravaani10Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result produced by LiveSttProcessor for a finalized speech segment.
 */
data class LiveTranscript(
    val callId: String,
    val speakerNodeId: String,
    val text: String,
    val language: SttLanguage,
    val latencyMs: Long,
    val speechDurationMs: Long,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Live Speech-to-Text processor for active phone calls using production SraVaani 1.0 FP16 ONNX.
 * Maintains persistent sessions and serializes inference so concurrent segments decode in order.
 */
class LiveSttProcessor(
    private val sravaaniEngine: Sravaani10Engine,
) {
    companion object {
        private const val TAG = "LiveSttProcessor"
        private const val INFERENCE_TIMEOUT_MS = 2500L
    }

    private val inferenceMutex = Mutex()
    private var lastRecognizedText: String = ""
    private var lastRecognizedTimestampMs: Long = 0L

    /**
     * Initializes and warms up the SraVaani 1.0 ASR model for the call's language outside the hot path.
     */
    suspend fun prepare(language: SttLanguage): Boolean = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            try {
                if (!sravaaniEngine.isReady || sravaaniEngine.currentLanguage != language) {
                    sravaaniEngine.initialize(SttConfig(language = language))
                }
                sravaaniEngine.warmUp()
                Log.i(TAG, "LiveSttProcessor prepared and warmed up for ${language.code} (${language.englishName})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "CALL_STT_ERROR: Failed preparing SraVaani 1.0 engine: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Transcribes a finalized PCM speech segment from VAD with timeout, noise filtering, and structured logging.
     */
    suspend fun processSpeechSegment(
        callId: String,
        speakerNodeId: String,
        pcmShorts: ShortArray,
        durationMs: Long,
        language: SttLanguage,
    ): LiveTranscript? = withContext(Dispatchers.Default) {
        if (pcmShorts.isEmpty()) return@withContext null

        inferenceMutex.withLock {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "CALL_STT_SEGMENT_START: Processing segment of ${pcmShorts.size} samples ($durationMs ms) for ${language.code}")
            try {
                val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    sravaaniEngine.transcribe(pcmShorts, language)
                }

                if (result == null) {
                    Log.w(TAG, "CALL_STT_ERROR: STT decode timed out after ${INFERENCE_TIMEOUT_MS}ms for segment ($durationMs ms audio)")
                    return@withLock null
                }

                val latencyMs = System.currentTimeMillis() - startTime
                val rawText = result.text.trim()

                // Filter noise, blank artifacts, or non-printable tokens
                val cleanedText = sanitizeTranscript(rawText)
                if (cleanedText.isBlank()) {
                    Log.d(TAG, "CALL_STT_SEGMENT_FINAL: Empty/blank transcript filtered out")
                    return@withLock null
                }

                // Suppress identical duplicate transcript within 800ms window
                val now = System.currentTimeMillis()
                if (cleanedText == lastRecognizedText && (now - lastRecognizedTimestampMs) < 800L) {
                    Log.d(TAG, "CALL_STT_SEGMENT_FINAL: Deduplicated rapid identical STT transcript: \"$cleanedText\"")
                    return@withLock null
                }
                lastRecognizedText = cleanedText
                lastRecognizedTimestampMs = now

                Log.i(TAG, "CALL_STT_SEGMENT_FINAL: \"$cleanedText\" (language=${language.code})")
                Log.i(TAG, "CALL_STT_LATENCY: ${latencyMs}ms for ${durationMs}ms audio")

                LiveTranscript(
                    callId = callId,
                    speakerNodeId = speakerNodeId,
                    text = cleanedText,
                    language = language,
                    latencyMs = latencyMs,
                    speechDurationMs = durationMs,
                )
            } catch (e: Exception) {
                Log.e(TAG, "CALL_STT_ERROR: Error in SraVaani 1.0 speech decoding: ${e.message}", e)
                null
            }
        }
    }

    private fun sanitizeTranscript(raw: String): String {
        return raw
            .replace("<blank>", "")
            .replace("<unk>", "")
            .replace("<pad>", "")
            .replace(Regex("^[\\p{Punct}\\s]+$"), "")
            .trim()
    }

    fun reset() {
        lastRecognizedText = ""
        lastRecognizedTimestampMs = 0L
    }
}
