package app.swarsetu.stt

import kotlinx.coroutines.flow.Flow

/**
 * Core interface for an offline speech-to-text engine. Implementations run entirely on-device
 * against bundled models — the app has no network.
 *
 * **Contract:**
 * - All methods are safe to call from any coroutine; internal synchronization is the engine's job.
 * - [transcribe] and [transcribeStream] suspend until the result is available.
 * - [transcribeStream] emits [SttResult]s as audio is processed (partial then final); the last
 *   emission with [SttResultType.FINAL] is the committed transcription.
 * - [release] is idempotent; calling methods after [release] throws [SttException.EngineNotReady].
 * - Implementations must follow the project's graceful-degradation pattern: if the model cannot be
 *   loaded, [transcribe] returns [SttResult.empty] rather than throwing. Only truly unexpected
 *   failures throw [SttException].
 *
 * **Audio format:** all engines accept 16-bit signed PCM, mono, at [SttLanguage.SAMPLE_RATE] (16 kHz).
 * The caller is responsible for capturing audio in this format. See [SttConfig.sampleRate].
 *
 * **Thread safety:** implementations may be called from multiple coroutines concurrently. The engine
 * serializes inference internally (the [Mutex]-behind-[runCatching] pattern from
 * [app.swarsetu.moderation.MlTextModerator]).
 *
 * **Lifecycle:**
 * 1. Created by [SttEngineFactory] (or via Koin DI).
 * 2. [initialize] loads the model for the configured language.
 * 3. [transcribe] or [transcribeStream] processes audio.
 * 4. [release] frees model memory and native resources.
 *
 * This interface is intentionally Knit-agnostic: it knows nothing about the mesh, the wire format,
 * or the application layer. The application (ViewModel / MeshManager) calls this interface and
 * handles the result.
 */
interface SttEngine {
    /**
     * Transcribe a complete audio buffer. Returns the final transcription once the entire buffer
     * has been processed. For streaming (partial results as audio arrives), use [transcribeStream].
     *
     * @param pcm 16-bit signed PCM samples, mono, at the engine's expected sample rate.
     * @param language Language to transcribe. Must be a supported language; if different from the
     *   currently loaded model, the engine may need to switch models.
     * @return Final transcription result, or [SttResult.empty] on silence / no speech detected.
     * @throws SttException.EngineNotReady if the engine has not been initialized or was released.
     * @throws SttException.UnsupportedLanguage if the language has no model.
     * @throws SttException.AudioInputError if the PCM data is invalid.
     * @throws SttException.InferenceError if inference fails unexpectedly.
     */
    suspend fun transcribe(
        pcm: ShortArray,
        language: SttLanguage,
    ): SttResult

    /**
     * Streaming transcription: emits partial results as audio is processed, ending with a final result.
     *
     * The default implementation chunks [pcm] and calls [transcribe] on each chunk, emitting partial
     * results. Engine implementations with native streaming support (SraVaani's cache-aware chunks)
     * should override this for better latency and accuracy.
     *
     * @param pcm 16-bit signed PCM samples, mono.
     * @param language Language to transcribe.
     * @return A [Flow] of [SttResult]s. The last emission has type [SttResultType.FINAL].
     */
    fun transcribeStream(
        pcm: ShortArray,
        language: SttLanguage,
    ): Flow<SttResult>

    /**
     * Initialize the engine for the given configuration. Loads the model for [config.language].
     * Must be called before [transcribe] or [transcribeStream].
     *
     * If the model is already loaded for this language, this is a no-op.
     *
     * @throws SttException.ModelLoadError if the model cannot be loaded.
     * @throws SttException.UnsupportedLanguage if the language has no model.
     */
    suspend fun initialize(config: SttConfig)

    /**
     * Switch to a different language. If the engine supports the language with the currently loaded
     * model (multilingual), this is a config update. Otherwise it triggers a model swap.
     *
     * @throws SttException.UnsupportedLanguage if the language has no model.
     * @throws SttException.ModelLoadError if the new model cannot be loaded.
     */
    suspend fun setLanguage(language: SttLanguage)

    /**
     * Release all resources (model memory, native buffers). Idempotent. After release, the engine
     * can be re-initialized with [initialize].
     */
    suspend fun release()

    /**
     * The current configuration, or null if the engine has not been initialized.
     */
    val config: SttConfig?

    /**
     * Whether the engine is initialized and ready for transcription.
     */
    val isReady: Boolean

    /**
     * The language the engine is currently configured for, or null if not initialized.
     */
    val currentLanguage: SttLanguage?
}
