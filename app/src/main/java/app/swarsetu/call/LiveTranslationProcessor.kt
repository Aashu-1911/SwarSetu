package app.swarsetu.call

import android.util.Log
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
     * Translates recognized transcript text to the remote peer's target language with fallback.
     */
    suspend fun translateSegment(
        callId: String,
        speakerNodeId: String,
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
    ): LiveTranslation? = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext null

        val srcLang = IndicLanguage.fromCode(sourceLangCode) ?: IndicLanguage.HINDI
        val tgtLang = IndicLanguage.fromCode(targetLangCode) ?: IndicLanguage.HINDI

        // Same language: pass through with 0ms latency
        if (srcLang == tgtLang) {
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
            var translated = ""
            var success = false

            // 1. Attempt IndicTrans2 neural translation with timeout
            try {
                val translationResult = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    translatorEngine.translate(
                        text = trimmed,
                        sourceLang = srcLang.code,
                        targetLang = tgtLang.code,
                    )
                }
                if (translationResult != null && translationResult.isNotBlank()) {
                    translated = translationResult.trim()
                    success = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "IndicTrans2 translation failed for \"$trimmed\": ${e.message}")
            }

            // 2. Direct model fallback if high-level translate threw
            if (!success) {
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
                    Log.w(TAG, "Direct IndicTrans2 fallback also failed: ${e.message}")
                }
            }

            // 3. Ultimate resilient fallback: deliver verbatim original text so call never drops
            if (!success || translated.isBlank()) {
                Log.w(TAG, "Falling back to verbatim original text for \"$trimmed\"")
                translated = trimmed
            }

            val latencyMs = System.currentTimeMillis() - startTime
            Log.d(TAG, "Translation [${srcLang.code} -> ${tgtLang.code}]: \"$trimmed\" -> \"$translated\" in ${latencyMs}ms")

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
