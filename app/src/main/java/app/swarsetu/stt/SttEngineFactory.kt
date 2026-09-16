package app.swarsetu.stt

import android.content.Context
import app.swarsetu.stt.sravaani.SravaaniEngine

/**
 * Factory for creating [SttEngine] instances. Abstracts the concrete engine implementation so the
 * application layer (Koin DI) can swap engines without changing consumers.
 *
 * **Production engine: [SravaaniEngine]** (SraVaani 0.5-live, SIH 26173's mandated model) —
 * selected when ONNX Runtime is on the classpath (it always is; the probe guards exotic
 * build variants that strip it). SraVaani is a single multilingual model serving ALL 10
 * required languages; there is no per-language model selection and no aliasing.
 *
 * Vosk is retained ONLY behind the explicit DEBUG `VOICE_CAPTURE_DIAGNOSTIC_MODE=VOSK` switch
 * (audio-capture forensics), pending removal in the language-handling milestone. It is never
 * a production fallback: if ORT is unavailable the factory degrades to [DefaultSttEngine]
 * (empty results, no crash) rather than silently substituting a different STT model.
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
    fun create(): SttEngine {
        if (app.swarsetu.BuildConfig.DEBUG) {
            when (SttPipeline.VOICE_CAPTURE_DIAGNOSTIC_MODE) {
                SttPipeline.DiagnosticMode.VOSK -> {
                    return VoskEngine(context, modelManager)
                }

                SttPipeline.DiagnosticMode.AUDIO_ONLY -> {
                    return DefaultSttEngine(context, modelManager)
                }

                else -> {} // Proceed to normal PRODUCTION fallback
            }
        }

        return try {
            // Probe for ONNX Runtime — SraVaani 0.5's execution runtime (see the version
            // catalog pin; must match the numeric-equivalence gate version).
            Class.forName("ai.onnxruntime.OrtEnvironment")
            SravaaniEngine(context, modelManager)
        } catch (_: ClassNotFoundException) {
            android.util.Log.w(TAG, "ONNX Runtime unavailable — DefaultSttEngine (empty results)")
            DefaultSttEngine(context, modelManager)
        }
    }

    private companion object {
        const val TAG = "SttEngineFactory"
    }
}
