package app.swarsetu.call

import android.util.Log
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Live Neural Translation processor for active calls using offline IndicTrans2 ONNX INT8 models.
 */
class LiveTranslationProcessor(
    private val translatorEngine: TranslatorEngine,
) {
    companion object {
        private const val TAG = "LiveTransProcessor"
    }

    /**
     * Translates recognized transcript text to the remote peer's target language.
     */
    suspend fun translateSegment(
        callId: String,
        speakerNodeId: String,
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
    ): LiveTranslation? = withContext(Dispatchers.Default) {
        val srcLang = IndicLanguage.fromCode(sourceLangCode) ?: IndicLanguage.HINDI
        val tgtLang = IndicLanguage.fromCode(targetLangCode) ?: IndicLanguage.HINDI

        // Same language: pass through with 0ms latency
        if (srcLang == tgtLang) {
            return@withContext LiveTranslation(
                callId = callId,
                speakerNodeId = speakerNodeId,
                sourceText = text,
                translatedText = text,
                sourceLanguage = srcLang,
                targetLanguage = tgtLang,
                latencyMs = 0L,
            )
        }

        val startTime = System.currentTimeMillis()
        try {
            val result = translatorEngine.indicTransEngine.translate(
                text = text,
                source = srcLang,
                target = tgtLang,
            )
            val latencyMs = System.currentTimeMillis() - startTime
            val translated = result.translatedText.trim()

            Log.d(TAG, "Translation [${srcLang.code} -> ${tgtLang.code}]: \"$text\" -> \"$translated\" in ${latencyMs}ms")
            LiveTranslation(
                callId = callId,
                speakerNodeId = speakerNodeId,
                sourceText = text,
                translatedText = translated,
                sourceLanguage = srcLang,
                targetLanguage = tgtLang,
                latencyMs = latencyMs,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Translation error from ${srcLang.code} to ${tgtLang.code}: ${e.message}", e)
            null
        }
    }
}
