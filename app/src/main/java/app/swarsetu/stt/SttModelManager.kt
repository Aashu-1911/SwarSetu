package app.swarsetu.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages STT model availability and lifecycle metadata.
 *
 * **SraVaani 0.5 architecture (P4):** there is ONE multilingual model for all 10 languages —
 * not one model per language. All of the Vosk-era machinery is gone by design:
 * - **No runtime downloads.** The problem statement requires a fresh install + no internet
 *   = working STT; model download at runtime violates that (and the master spec §11).
 * - **No canonical aliasing.** The old `CANONICAL_ALIASES` map returned the Hindi model's
 *   transcripts for Kannada/Malayalam/Odia/Marathi — explicitly forbidden (§7). Removed.
 *
 * Availability is a property of the single artifact set (`assets/sravaani/`), so
 * [isAvailable] is true for every declared language exactly when that artifact set is
 * packaged. Loaded-model bookkeeping remains per-[SttLanguage] only because the engine's
 * diagnostics API is language-keyed.
 */
class SttModelManager(
    private val context: Context,
) {
    /** Mutex guarding loaded-model bookkeeping. */
    private val mutex = Mutex()

    /** Currently loaded model info by language, or null if not loaded. */
    private val loadedModels = mutableMapOf<SttLanguage, SttModelInfo>()

    /**
     * The languages the STT subsystem serves: every declared language, all through the one
     * multilingual SraVaani model.
     */
    suspend fun availableLanguages(): Set<SttLanguage> =
        if (isSraVaaniPackaged()) SttLanguage.entries.toSet() else emptySet()

    /**
     * True when [language] can be transcribed: the single multilingual artifact set is
     * packaged (every declared language shares it — no per-language check, no aliasing).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun isAvailable(language: SttLanguage): Boolean = isSraVaaniPackaged()

    /**
     * Metadata for the multilingual model (one entry per declared language, same artifact).
     */
    suspend fun modelInfo(): List<SttModelInfo> =
        withContext(Dispatchers.IO) {
            val packaged = isSraVaaniPackaged()
            SttLanguage.entries.map { lang ->
                SttModelInfo(
                    language = lang,
                    modelPath = SRAVAANI_ASSET_DIR,
                    available = packaged,
                    description = "SraVaani 0.5-live multilingual (one model, 10 languages)",
                )
            }
        }

    /**
     * Returns the cached model info for a loaded language, or null if not loaded.
     */
    fun loadedInfo(language: SttLanguage): SttModelInfo? = loadedModels[language]

    /**
     * Record that a model was successfully loaded. Called by the engine after construction.
     */
    suspend fun markLoaded(info: SttModelInfo) =
        mutex.withLock {
            loadedModels[info.language] = info
            Log.d(TAG, "Model loaded: ${info.language.code} (${info.sizeMb} MB)")
        }

    /**
     * Record that a model was released. Called by the engine on [SttEngine.release].
     */
    suspend fun markReleased(language: SttLanguage) =
        mutex.withLock {
            val removed = loadedModels.remove(language)
            if (removed != null) {
                Log.d(TAG, "Model released: ${language.code}")
            }
        }

    /**
     * Release all loaded models. Called on application shutdown or when memory pressure demands it.
     */
    suspend fun releaseAll() =
        mutex.withLock {
            val count = loadedModels.size
            loadedModels.clear()
            Log.d(TAG, "All models released ($count)")
        }

    /**
     * Number of models currently loaded in memory.
     */
    fun loadedCount(): Int = loadedModels.size

    /**
     * The SraVaani artifact set is available if the graphs are packaged as assets
     * (fresh-install-offline contract) OR already extracted to app-private storage
     * (post-first-use state; extraction is handled by the engine).
     */
    private fun isSraVaaniPackaged(): Boolean =
        try {
            val graphs = context.assets.list(SRAVAANI_ASSET_DIR).orEmpty()
            val hasGraphAssets = graphs.contains(SravaaniGraphNames.ENCODER) &&
                graphs.contains(SravaaniGraphNames.CTC)
            hasGraphAssets || extractedGraphsExist()
        } catch (_: Exception) {
            extractedGraphsExist()
        }

    private fun extractedGraphsExist(): Boolean =
        try {
            File(File(context.filesDir, EXTRACT_DIR), SravaaniGraphNames.ENCODER).isFile &&
                File(File(context.filesDir, EXTRACT_DIR), SravaaniGraphNames.CTC).isFile
        } catch (_: Exception) {
            false
        }

    /** Canonical graph asset names — shared with [app.swarsetu.stt.sravaani.SravaaniEngine]. */
    object SravaaniGraphNames {
        const val ENCODER = "sravaani_encoder.onnx"
        const val CTC = "sravaani_ctc.onnx"
    }

    private companion object {
        const val TAG = "SttModelManager"
        const val SRAVAANI_ASSET_DIR = "sravaani"
        const val EXTRACT_DIR = "stt/sravaani"
    }
}
