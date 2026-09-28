package app.swarsetu.call

import android.util.Log
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result produced by LiveTranslationProcessor.
 */
data class LiveTranslation(
    val callId: String,
    val speakerNodeId: String,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: IndicLanguage,
    val targetLanguage: IndicLanguage,
    val latencyMs: Long,
    val segmentId: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Live Neural Translation processor for active calls using offline IndicTrans2 ONNX INT8 models
 * with entity shielding, timeout guards, and resilient fallbacks.
 */
class LiveTranslationProcessor(
    private val translatorEngine: TranslatorEngine,
) {
    companion object {
        private const val TAG = "LiveTransProcessor"
        private const val TRANSLATION_TIMEOUT_MS = 2500L
    }

    private val translationMutex = Mutex()

    /**
     * Translates recognized transcript text to the remote peer's target language with fallback and structured logging.
     */
    suspend fun translateSegment(
        callId: String,
        speakerNodeId: String,
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        protectedNouns: List<String> = emptyList(),
    ): LiveTranslation? = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext null

        val srcLang = IndicLanguage.fromCode(sourceLangCode) ?: IndicLanguage.HINDI
        val tgtLang = IndicLanguage.fromCode(targetLangCode) ?: IndicLanguage.HINDI

        // Same language: pass through with 0ms latency
        if (srcLang == tgtLang) {
            Log.i(TAG, "CALL_TRANSLATION_FINAL: Same language (${srcLang.code}), passing verbatim: \"$trimmed\"")
            return@withContext LiveTranslation(
                callId = callId,
                speakerNodeId = speakerNodeId,
                sourceText = trimmed,
                translatedText = trimmed,
                sourceLanguage = srcLang,
                targetLanguage = tgtLang,
                latencyMs = 0L,
            )
        }

        translationMutex.withLock {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "CALL_TRANSLATION_START: [${srcLang.code} -> ${tgtLang.code}] \"$trimmed\"")
            var translated = ""
            var success = false

            // 1. Attempt IndicTrans2 neural translation with timeout
            try {
                val translationResult = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    translatorEngine.translate(
                        text = trimmed,
                        sourceLang = srcLang.code,
                        targetLang = tgtLang.code,
                        protectedNouns = protectedNouns,
                    )
                }
                if (translationResult != null && translationResult.isNotBlank()) {
                    translated = translationResult.trim()
                    success = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "CALL_TRANSLATION_ERROR: IndicTrans2 high-level translate error for \"$trimmed\": ${e.message}")
            }

            // 2. Bounded retry on direct IndicTrans2 engine if initial attempt failed
            if (!success) {
                var retryCount = 0
                val maxRetries = 1
                while (!success && retryCount <= maxRetries) {
                    retryCount++
                    try {
                        val directResult = withTimeoutOrNull(1500L) {
                            translatorEngine.indicTransEngine.translate(
                                text = trimmed,
                                source = srcLang,
                                target = tgtLang,
                            )
                        }
                        if (directResult != null && directResult.translatedText.isNotBlank()) {
                            translated = directResult.translatedText.trim()
                            success = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CALL_TRANSLATION_ERROR: Direct IndicTrans2 retry $retryCount failed: ${e.message}")
                    }
                }
            }

            // 3. Translation Failure Policy (Requirement 9):
            // Do NOT fall back to verbatim/untranslated text. Return null and log failure.
            if (!success || translated.isBlank()) {
                Log.e(TAG, "CALL_TRANSLATION_ERROR: Translation failed for \"$trimmed\" after retries. Suppressing untranslated audio transmission.")
                return@withLock null
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "CALL_TRANSLATION_FINAL: [${srcLang.code} -> ${tgtLang.code}] \"$trimmed\" -> \"$translated\"")
            Log.i(TAG, "CALL_TRANSLATION_LATENCY: ${latencyMs}ms")

            LiveTranslation(
                callId = callId,
                speakerNodeId = speakerNodeId,
                sourceText = trimmed,
                translatedText = translated,
                sourceLanguage = srcLang,
                targetLanguage = tgtLang,
                latencyMs = latencyMs,
            )
        }
    }
}
