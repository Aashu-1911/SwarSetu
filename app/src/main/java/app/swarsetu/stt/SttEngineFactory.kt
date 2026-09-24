package app.swarsetu.stt

import android.content.Context
import app.swarsetu.stt.sravaani.Sravaani10Engine

/**
 * Factory for creating [SttEngine] instances.
 *
 * Production engine: SraVaani 1.0 (FP16 FastConformer-TDT) -> [Sravaani10Engine].
 */
class SttEngineFactory(
    private val context: Context,
    private val modelManager: SttModelManager,
    private val executionProvider: String = "CPU",
) {
    /**
     * Create a new [SttEngine] instance. Call [SttEngine.initialize] before use.
     */
    fun create(): SttEngine {
        // 1. Check ONNX Runtime availability and native library linkage.
        val ortAvailable = try {
            Class.forName("ai.onnxruntime.OrtEnvironment")
            ai.onnxruntime.OrtEnvironment.getEnvironment()
            true
        } catch (_: Throwable) {
            false
        }

        if (!ortAvailable) {
            android.util.Log.e(TAG, "ONNX Runtime unavailable — cannot load SraVaani engine")
            return DefaultSttEngine(context, modelManager)
        }

        // 2. Check SraVaani 1.0 model availability
        if (modelManager.isSraVaaniAvailable()) {
            android.util.Log.i(TAG, "Instantiating SraVaani 1.0 TDT STT engine ($executionProvider)")
            return Sravaani10Engine(context, modelManager, executionProvider)
        }

        android.util.Log.w(
            TAG,
            "SraVaani 1.0 ONNX models missing from ${modelManager.modelDir.path} — fallback to DefaultSttEngine until downloaded",
        )
        return DefaultSttEngine(context, modelManager)
    }

    companion object {
        const val TAG = "SttEngineFactory"
        const val SRAAVAANI_VERSION: String = "1.0"
    }
}

