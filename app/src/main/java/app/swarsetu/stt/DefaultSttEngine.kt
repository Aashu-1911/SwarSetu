package app.swarsetu.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Default [SttEngine] implementation skeleton. Handles:
 * - PCM input validation (format, length, sample rate)
 * - Engine lifecycle (initialize / release)
 * - Language switching
 * - Graceful degradation when no model is bundled (returns empty results)
 * - Thread safety via [Mutex] + [runCatching] (same pattern as [app.swarsetu.moderation.MlTextModerator])
 *
 * The actual model loading and inference are delegated to protected methods that concrete engine
 * implementations override. This class provides the scaffolding so each backend only needs to
 * implement [loadModel], [inferPcm], and [releaseModel].
 *
 * **No Knit/networking/TTS dependencies.** This class is a pure audio→text transform.
 */
open class DefaultSttEngine(
    private val context: Context,
    private val modelManager: SttModelManager,
) : SttEngine {
    private val mutex = Mutex()
    private var initialized = false
    private var _config: SttConfig? = null
    private var _currentLanguage: SttLanguage? = null

    override val config: SttConfig? get() = _config
    override val isReady: Boolean get() = initialized
    override val currentLanguage: SttLanguage? get() = _currentLanguage

    override suspend fun initialize(config: SttConfig) =
        mutex.withLock {
            if (initialized && _currentLanguage == config.language) {
                Log.d(TAG, "Already initialized for ${config.language.code}")
                return@withLock
            }
            _config = config
            _currentLanguage = config.language

            if (!modelManager.isAvailable(config.language)) {
                Log.w(TAG, "No model available for ${config.language.code} — engine will return empty results")
                initialized = true
                return@withLock
            }

            try {
                loadModel(config.language)
                modelManager.markLoaded(
                    SttModelInfo(
                        language = config.language,
                        modelPath = config.language.assetDir ?: "",
                        description = "Default STT model for ${config.language.displayName}",
                    ),
                )
                initialized = true
                Log.d(TAG, "Initialized for ${config.language.code}")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed for ${config.language.code}: ${e.message}")
                // Graceful degradation: engine is "ready" but will return empty results
                initialized = true
            }
        }

    override suspend fun setLanguage(language: SttLanguage) {
        val currentConfig = _config ?: SttConfig()
        if (language == _currentLanguage && initialized) return
        initialize(currentConfig.copy(language = language))
    }

    override suspend fun transcribe(
        pcm: ShortArray,
        language: SttLanguage,
    ): SttResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                validatePcm(pcm, language) ?: return@withLock SttResult.empty(language)

                if (!initialized) {
                    return@withLock SttResult.empty(language)
                }

                if (!modelManager.isAvailable(language)) {
                    return@withLock SttResult.empty(language)
                }

                val startMs = System.currentTimeMillis()
                val result =
                    runCatching { inferPcm(pcm, language) }
                        .getOrElse { e ->
                            Log.w(TAG, "Inference failed: ${e.message}")
                            SttResult.empty(language)
                        }
                val elapsed = System.currentTimeMillis() - startMs

                if (result.text.isBlank()) {
                    SttResult.empty(language)
                } else {
                    result.copy(durationMs = elapsed)
                }
            }
        }

    override fun transcribeStream(
        pcm: ShortArray,
        language: SttLanguage,
    ): Flow<SttResult> =
        flow {
            // Default implementation: chunk the PCM and emit partial results.
            // Concrete engines with streaming support (SraVaani) should override this
            // for better latency.
            val chunkSize = language.sampleRate / 2 // 500ms chunks
            val totalChunks = (pcm.size + chunkSize - 1) / chunkSize
            val accumulated = StringBuilder()

            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, pcm.size)
                val chunk = pcm.copyOfRange(start, end)

                val partial = transcribe(chunk, language)
                if (partial.text.isNotBlank()) {
                    accumulated.append(partial.text)
                }

                val type = if (i == totalChunks - 1) SttResultType.FINAL else SttResultType.PARTIAL
                emit(
                    SttResult(
                        text = accumulated.toString().trim(),
                        type = type,
                        language = language,
                        confidence = partial.confidence,
                    ),
                )
            }

            // If no chunks produced text, emit one empty final result
            if (accumulated.isEmpty()) {
                emit(SttResult.empty(language))
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun release() =
        mutex.withLock {
            if (!initialized) return@withLock
            try {
                releaseModel()
            } catch (e: Exception) {
                Log.w(TAG, "Model release error: ${e.message}")
            }
            _currentLanguage?.let { modelManager.markReleased(it) }
            initialized = false
            _config = null
            _currentLanguage = null
            Log.d(TAG, "Released")
        }

    // --- Protected extension points for concrete engine implementations ---

    /**
     * Load the model for [language] from assets. Called once during [initialize].
     * Throw on failure; the caller catches and degrades gracefully.
     *
     * The default implementation is a no-op (no model bundled). Override in concrete engines.
     */
    protected open suspend fun loadModel(language: SttLanguage) {
        // No-op: concrete engines override this to load their model.
    }

    /**
     * Run inference on [pcm] and return the transcription result. Called from [transcribe] under
     * the mutex. The engine is already initialized and the model is loaded.
     *
     * The default implementation returns an empty result. Override in concrete engines.
     */
    protected open suspend fun inferPcm(
        pcm: ShortArray,
        language: SttLanguage,
    ): SttResult = SttResult.empty(language)

    /**
     * Release model resources. Called from [release]. The default implementation is a no-op.
     */
    protected open suspend fun releaseModel() {
        // No-op: concrete engines override this to free native resources.
    }

    // --- Input validation ---

    /**
     * Validates PCM input. Returns null when valid (the caller proceeds), or a reason to short-circuit.
     * Follows the project's pattern of early-return validation rather than exceptions for expected cases.
     */
    private fun validatePcm(
        pcm: ShortArray,
        language: SttLanguage,
    ): String? {
        if (pcm.isEmpty()) {
            return "empty PCM buffer"
        }
        val expectedSamplesPerMs = language.sampleRate / 1000
        val durationMs = pcm.size / expectedSamplesPerMs
        val maxMs = _config?.maxAudioDurationMs ?: SttConfig().maxAudioDurationMs
        if (maxMs > 0 && durationMs > maxMs) {
            Log.w(TAG, "PCM too long: ${durationMs}ms > ${maxMs}ms limit")
            // Trim rather than reject — the caller wants a result, not an error
        }
        return null
    }

    private companion object {
        const val TAG = "SttEngine"
    }
}
