package app.swarsetu.call

import android.util.Log
import app.swarsetu.stt.SttConfig
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.sravaani.Sravaani10Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    }

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
     * Transcribes a finalized PCM speech segment from VAD.
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
            val result = sravaaniEngine.transcribe(pcmShorts, language)
            val latencyMs = System.currentTimeMillis() - startTime

            val recognizedText = result.text.trim()
            if (recognizedText.isNotBlank()) {
                Log.d(TAG, "STT recognized [${language.code}]: \"$recognizedText\" in ${latencyMs}ms (${durationMs}ms audio)")
                return@withContext LiveTranscript(
                    callId = callId,
                    speakerNodeId = speakerNodeId,
                    text = recognizedText,
                    language = language,
                    latencyMs = latencyMs,
                    speechDurationMs = durationMs,
                )
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error in SraVaani 1.0 speech decoding: ${e.message}", e)
            null
        }
    }
}
