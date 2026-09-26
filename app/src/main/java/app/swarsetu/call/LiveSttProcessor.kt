package app.swarsetu.call

import android.util.Log
import app.swarsetu.stt.SttConfig
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.sravaani.Sravaani10Engine
import kotlinx.coroutines.Dispatchers
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
 */
class LiveSttProcessor(
    private val sravaaniEngine: Sravaani10Engine,
) {
    companion object {
        private const val TAG = "LiveSttProcessor"
        private const val INFERENCE_TIMEOUT_MS = 2500L
    }

    private var lastRecognizedText: String = ""
    private var lastRecognizedTimestampMs: Long = 0L

    /**
     * Initializes the SraVaani 1.0 ASR model for the call's language.
     */
    suspend fun prepare(language: SttLanguage): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sravaaniEngine.isReady || sravaaniEngine.currentLanguage != language) {
                sravaaniEngine.initialize(SttConfig(language = language))
            }
            sravaaniEngine.warmUp()
            Log.d(TAG, "LiveSttProcessor prepared for ${language.code} (${language.englishName})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed preparing SraVaani 1.0 engine: ${e.message}", e)
            false
        }
    }

    /**
     * Transcribes a finalized PCM speech segment from VAD with timeout and noise filtering.
     */
    suspend fun processSpeechSegment(
        callId: String,
        speakerNodeId: String,
        pcmShorts: ShortArray,
        durationMs: Long,
        language: SttLanguage,
    ): LiveTranscript? = withContext(Dispatchers.Default) {
        if (pcmShorts.isEmpty()) return@withContext null

        val startTime = System.currentTimeMillis()
        try {
            val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                sravaaniEngine.transcribe(pcmShorts, language)
            }

            if (result == null) {
                Log.w(TAG, "STT decode timed out after ${INFERENCE_TIMEOUT_MS}ms for segment ($durationMs ms audio)")
                return@withContext null
            }

            val latencyMs = System.currentTimeMillis() - startTime
            val rawText = result.text.trim()

            // Filter noise, blank artifacts, or non-printable tokens
            val cleanedText = sanitizeTranscript(rawText)
            if (cleanedText.isBlank()) {
                return@withContext null
            }

            // Suppress identical duplicate transcript within 800ms window
            val now = System.currentTimeMillis()
            if (cleanedText == lastRecognizedText && (now - lastRecognizedTimestampMs) < 800L) {
                Log.d(TAG, "Deduplicated rapid identical STT transcript: \"$cleanedText\"")
                return@withContext null
            }
            lastRecognizedText = cleanedText
            lastRecognizedTimestampMs = now

            Log.d(TAG, "STT recognized [${language.code}]: \"$cleanedText\" in ${latencyMs}ms (${durationMs}ms audio)")
            LiveTranscript(
                callId = callId,
                speakerNodeId = speakerNodeId,
                text = cleanedText,
                language = language,
                latencyMs = latencyMs,
                speechDurationMs = durationMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in SraVaani 1.0 speech decoding: ${e.message}", e)
            null
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
}
