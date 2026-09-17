package app.swarsetu.stt

import android.content.Context
import app.swarsetu.stt.sravaani.SravaaniEngine

/**
 * Factory for creating [SttEngine] instances. Abstracts the concrete engine implementation so the
 * application layer (Koin DI) can swap engines without changing consumers.
 *
 * **Production engine: [SravaaniEngine]** (SraVaani 0.5-live, SIH 26173's mandated model).
 * SraVaani is a single multilingual model serving ALL 10 required languages; there is no
 * per-language model selection and no aliasing. The Vosk debug-diagnostic path and the
 * Vosk-era engine were removed in P4 (forbidden substitute + aliased-language fallback).
 *
 * If ONNX Runtime is unavailable on an exotic build variant, the factory degrades to
 * [DefaultSttEngine] (empty results, no crash) rather than silently substituting a different
 * STT model — per the master spec's blocker policy, a missing runtime is a loud failure at
 * packaging time, not a model swap.
 *
 * Follows the project's DI convention: the factory is bound as a Koin `single` in
 * [app.swarsetu.di.SttModule], and engines are created via [create].
 */
class SttEngineFactory(
    private val context: Context,
    private val modelManager: SttModelManager,
) {
    /**
     * Create a new [SttEngine] instance. The engine is not initialized — call [SttEngine.initialize]
     * before use.
     *
     * Each call creates a fresh engine; the caller is responsible for calling [SttEngine.release]
     * when done. For a shared singleton engine, use Koin's `single { }` binding instead.
     */
    fun create(): SttEngine =
        try {
            // Probe for ONNX Runtime — SraVaani 0.5's execution runtime (see the version
            // catalog pin; must match the numeric-equivalence gate version).
            Class.forName("ai.onnxruntime.OrtEnvironment")
            SravaaniEngine(context, modelManager)
        } catch (_: ClassNotFoundException) {
            android.util.Log.w(TAG, "ONNX Runtime unavailable — DefaultSttEngine (empty results)")
            DefaultSttEngine(context, modelManager)
        }

    private companion object {
        const val TAG = "SttEngineFactory"
    }
}
