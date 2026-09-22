package app.swarsetu.stt

import android.content.Context
import app.swarsetu.stt.sravaani.SravaaniEngine
import java.io.File

/**
 * Factory for creating [SttEngine] instances. Abstracts the concrete engine implementation so the
 * application layer (Koin DI) can swap engines without changing consumers.
 *
 * **Production engine: [SravaaniEngine]** (SraVaani 0.5-live, SIH 26173's mandated model).
 * SraVaani is a single multilingual model serving ALL 10 required languages.
 */
class SttEngineFactory(
    private val context: Context,
    private val modelManager: SttModelManager,
) {
    /**
     * Create a new [SttEngine] instance. The engine is not initialized — call [SttEngine.initialize]
     * before use.
     */
    fun create(): SttEngine {
        // 1. Check ONNX Runtime availability.
        val ortAvailable = try {
            Class.forName("ai.onnxruntime.OrtEnvironment")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        if (!ortAvailable) {
            android.util.Log.e(TAG, "ONNX Runtime unavailable — cannot load SraVaani engine")
            return DefaultSttEngine(context, modelManager)
        }

        // 2. Check that the SraVaani ONNX graphs are present — either in APK assets (fresh
        //    install) or already extracted to app-private storage (post-first-use / sideloaded).
        val extractDir = File(File(context.filesDir, "stt/sravaani"), "sravaani_encoder.onnx")
        val encoderPresent = extractDir.isFile || try {
            context.assets.open("sravaani/sravaani_encoder.onnx").use { it.read() >= 0 }
        } catch (_: Exception) {
            false
        }

        if (!encoderPresent) {
            android.util.Log.w(
                TAG,
                "SraVaani ONNX graphs missing from both APK assets and app-private storage" +
                    " — fallback to DefaultSttEngine"
            )
            return DefaultSttEngine(context, modelManager)
        }

        // 3. All prerequisites met: use production SraVaani engine.
        return SravaaniEngine(context, modelManager)
    }

    private companion object {
        const val TAG = "SttEngineFactory"
    }
}

