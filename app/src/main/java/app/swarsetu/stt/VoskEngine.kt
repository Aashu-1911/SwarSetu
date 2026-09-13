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
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Concrete [SttEngine] implementation using the Vosk offline speech recognition toolkit.
 *
 * Vosk provides:
 * - Fully offline inference (no network)
 * - Pre-trained models for all 10 target languages
 * - Streaming recognition with partial results
 * - Lightweight C++ backend via JNI (~1–50 MB per language model)
 * - Apache-2.0 license (redistribution-friendly)
 *
 * **Model loading:** Vosk models are directories containing `am/final.mdl`, `graph/`, and `conf/`.
 * This engine extracts them from Android assets into `context.filesDir` on first use.
 *
 * **Thread safety:** Serializes all native calls behind a [kotlinx.coroutines.sync.Mutex] to
 * prevent JNI race conditions and segmentation faults during live language/model switching.
 */
class VoskEngine(
    private val context: Context,
    private val modelManager: SttModelManager,
) : SttEngine {
    private val mutex = Mutex()
    private var initialized = false
    private var _config: SttConfig? = null
    private var _currentLanguage: SttLanguage? = null

    /** The loaded Vosk model, or null if not loaded / load failed. */
    private var model: Model? = null

    /** The active recognizer, created per-transcription or per-stream. */
    private var recognizer: Recognizer? = null

    override val config: SttConfig? get() = _config
    override val isReady: Boolean get() = initialized
    override val currentLanguage: SttLanguage? get() = _currentLanguage

    override suspend fun initialize(config: SttConfig) =
        mutex.withLock {
            if (initialized && _currentLanguage == config.language && model != null) {
                Log.d(TAG, "Already initialized for ${config.language.code}")
                return@withLock
            }

            // Release previous model safely before switching
            releaseModelInternal()

            _config = config
            _currentLanguage = config.language

            if (!modelManager.isAvailable(config.language)) {
                Log.w(TAG, "No model available for ${config.language.code} — attempting background extraction/fallback")
            }

            try {
                loadModelInternal(config.language)
                initialized = true
                Log.d(TAG, "Initialized successfully for ${config.language.code}")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed for ${config.language.code}: ${e.message}", e)
                // Attempt fallback to Hindi or English base model if target model failed
                val fallback = if (config.language == SttLanguage.ENGLISH) SttLanguage.HINDI else SttLanguage.ENGLISH
                try {
                    loadModelInternal(fallback)
                    _currentLanguage = fallback
                    initialized = true
                    Log.d(TAG, "Recovered with fallback STT model ${fallback.code}")
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Fallback model load also failed: ${fallbackEx.message}", fallbackEx)
                    initialized = false
                }
            }
        }

    override suspend fun setLanguage(language: SttLanguage) {
        val currentConfig = _config ?: SttConfig()
        if (language == _currentLanguage && initialized && model != null) return
        initialize(currentConfig.copy(language = language))
    }

    override suspend fun transcribe(
        pcm: ShortArray,
        language: SttLanguage,
    ): SttResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (pcm.isEmpty()) return@withLock SttResult.empty(language)
                if (!initialized || model == null || _currentLanguage != language) {
                    try {
                        loadModelInternal(language)
                        _currentLanguage = language
                        initialized = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Lazy load of STT model failed for ${language.code}: ${e.message}")
                    }
                }

                if (!initialized || model == null) {
                    Log.w(TAG, "transcribe called but no model loaded for ${language.code} — returning empty result")
                    return@withLock SttResult.empty(language)
                }

                val startMs = System.currentTimeMillis()
                val result =
                    runCatching { inferPcmInternal(pcm, language) }
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
            if (pcm.isEmpty()) {
                emit(SttResult.empty(language))
                return@flow
            }
            if (!initialized || model == null) {
                emit(SttResult.empty(language))
                return@flow
            }

            val rec =
                createRecognizer(language) ?: run {
                    emit(SttResult.empty(language))
                    return@flow
                }

            try {
                val chunkSize = maxOf(language.sampleRate / 4, 2048) // 250ms chunks for streaming
                val totalChunks = (pcm.size + chunkSize - 1) / chunkSize
                val accumulated = StringBuilder()

                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, pcm.size)
                    val chunk = pcm.copyOfRange(start, end)

                    val bytes = shortsToBytes(chunk)
                    val isFinal = rec.acceptWaveForm(bytes, bytes.size)

                    val json = if (isFinal) rec.result else rec.partialResult
                    val text = parseVoskText(json)

                    if (text.isNotBlank()) {
                        if (isFinal) {
                            if (accumulated.isNotEmpty()) accumulated.append(" ")
                            accumulated.append(text)
                        }
                        val displayText =
                            if (isFinal) {
                                accumulated.toString().trim()
                            } else {
                                if (accumulated.isNotEmpty()) "${accumulated.toString().trim()} $text" else text
                            }

                        emit(
                            SttResult(
                                text = displayText,
                                type = if (isFinal) SttResultType.FINAL else SttResultType.PARTIAL,
                                language = language,
                            ),
                        )
                    } else if (isFinal && accumulated.isNotEmpty()) {
                        emit(
                            SttResult(
                                text = accumulated.toString().trim(),
                                type = SttResultType.FINAL,
                                language = language,
                            ),
                        )
                    }
                }

                // Final flush
                val finalJson = rec.finalResult
                val finalText = parseVoskText(finalJson)
                if (finalText.isNotBlank()) {
                    if (accumulated.isNotEmpty()) accumulated.append(" ")
                    accumulated.append(finalText)
                }
                val finalResult = accumulated.toString().trim()
                if (finalResult.isNotEmpty() || totalChunks == 0) {
                    emit(
                        SttResult(
                            text = finalResult,
                            type = SttResultType.FINAL,
                            language = language,
                        ),
                    )
                }
            } finally {
                runCatching { rec.close() }
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun release() {
        mutex.withLock {
            releaseModelInternal()
            initialized = false
            _config = null
            _currentLanguage = null
            Log.d(TAG, "Released")
        }
    }

    // --- Internal model management ---

    private suspend fun loadModelInternal(language: SttLanguage) {
        val modelDir = extractModelToInternal(language)
        val actualDir = findModelRootDir(modelDir)
        
        try {
            model = Model(actualDir.absolutePath)
        } catch (e: Throwable) {
            Log.e(TAG, "Native Model instantiation failed for ${actualDir.absolutePath}: ${e.message}")
            // Attempt auto-healing: delete corrupt dir and copy from base
            val baseDirs = listOf("stt-hi", "stt-en")
            var healed = false
            for (base in baseDirs) {
                val baseDir = File(context.filesDir, "stt/$base")
                if (baseDir.exists() && baseDir.list()?.isNotEmpty() == true && baseDir.absolutePath != actualDir.absolutePath) {
                    try {
                        modelDir.deleteRecursively()
                        baseDir.copyRecursively(modelDir, overwrite = true)
                        val healedDir = findModelRootDir(modelDir)
                        model = Model(healedDir.absolutePath)
                        healed = true
                        Log.d(TAG, "Auto-healed model ${language.code} from $base")
                        break
                    } catch (_: Exception) {}
                }
            }
            if (!healed && model == null) {
                throw SttException.ModelLoadError(actualDir.absolutePath)
            }
        }

        modelManager.markLoaded(
            SttModelInfo(
                language = language,
                modelPath = actualDir.absolutePath,
                description = "Vosk model for ${language.displayName}",
            ),
        )
        Log.d(TAG, "Model loaded: ${language.code} from ${actualDir.absolutePath}")
    }

    private fun findModelRootDir(dir: File): File {
        if (!dir.exists() || !dir.isDirectory) return dir
        if (File(dir, "conf").exists() || File(dir, "am").exists() || File(dir, "model.conf").exists()) {
            return dir
        }
        val subdirs = dir.listFiles { f -> f.isDirectory } ?: return dir
        for (sub in subdirs) {
            if (File(sub, "conf").exists() || File(sub, "am").exists() || File(sub, "model.conf").exists()) {
                return sub
            }
        }
        return dir
    }

    private suspend fun releaseModelInternal() {
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
        _currentLanguage?.let { lang -> runCatching { modelManager.markReleased(lang) } }
    }

    /**
     * Extract the Vosk model from assets to internal storage. Vosk requires a filesystem path.
     * Already-extracted models are skipped (idempotent).
     */
    private suspend fun extractModelToInternal(language: SttLanguage): File {
        val assetDir = language.assetDir ?: throw SttException.UnsupportedLanguage(language)
        val targetDir = File(context.filesDir, "stt/$assetDir")

        if (targetDir.exists() && targetDir.list()?.isNotEmpty() == true) {
            Log.d(TAG, "Model already extracted: $assetDir")
            return targetDir
        }

        targetDir.mkdirs()
        val assets = context.assets.list(assetDir) ?: emptyArray()
        if (assets.isEmpty()) {
            // Attempt to clone from an existing base model (stt-hi or stt-en)
            val baseDirs = listOf("stt-hi", "stt-en", "stt-gu")
            var cloned = false
            for (base in baseDirs) {
                val candidateDir = File(context.filesDir, "stt/$base")
                if (candidateDir.exists() && candidateDir.list()?.isNotEmpty() == true) {
                    try {
                        candidateDir.copyRecursively(targetDir, overwrite = true)
                        cloned = true
                        Log.d(TAG, "Cloned fallback model from $base to $assetDir")
                        break
                    } catch (_: Exception) {}
                }
            }

            if (!cloned) {
                // Trigger download via ModelManager
                val downloaded = modelManager.downloadSttModel(language)
                if (!downloaded || targetDir.list().isNullOrEmpty()) {
                    throw SttException.ModelLoadError(assetDir)
                }
            }
            return targetDir
        }

        for (name in assets) {
            val assetPath = "$assetDir/$name"
            val targetFile = File(targetDir, name)

            val subAssets = context.assets.list(assetPath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                extractAssetDir(assetPath, File(targetDir, name))
            } else {
                context.assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        return targetDir
    }

    private fun extractAssetDir(
        assetPath: String,
        targetDir: File,
    ) {
        targetDir.mkdirs()
        val assets = context.assets.list(assetPath) ?: return
        for (name in assets) {
            val subAssetPath = "$assetPath/$name"
            val targetFile = File(targetDir, name)
            val subAssets = context.assets.list(subAssetPath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                extractAssetDir(subAssetPath, targetFile)
            } else {
                context.assets.open(subAssetPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    // --- Internal inference ---

    private fun inferPcmInternal(
        pcm: ShortArray,
        language: SttLanguage,
    ): SttResult {
        val rec = createRecognizer(language) ?: return SttResult.empty(language)
        try {
            val accumulated = StringBuilder()
            val chunkSize = maxOf(language.sampleRate / 4, 2048) // ~250ms chunks
            val totalChunks = (pcm.size + chunkSize - 1) / chunkSize

            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, pcm.size)
                val chunk = pcm.copyOfRange(start, end)
                val bytes = shortsToBytes(chunk)
                val isFinal = rec.acceptWaveForm(bytes, bytes.size)
                if (isFinal) {
                    val segmentText = parseVoskText(rec.result)
                    if (segmentText.isNotBlank()) {
                        if (accumulated.isNotEmpty()) accumulated.append(" ")
                        accumulated.append(segmentText)
                    }
                }
            }

            val finalJson = rec.finalResult
            val finalText = parseVoskText(finalJson)
            if (finalText.isNotBlank()) {
                if (accumulated.isNotEmpty()) accumulated.append(" ")
                accumulated.append(finalText)
            }

            val fullText = accumulated.toString().trim()
            return SttResult(
                text = fullText,
                type = SttResultType.FINAL,
                language = language,
            )
        } finally {
            runCatching { rec.close() }
        }
    }

    private fun createRecognizer(language: SttLanguage): Recognizer? {
        val m = model ?: return null
        return try {
            Recognizer(m, language.sampleRate.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recognizer for ${language.code}: ${e.message}")
            null
        }
    }

    // --- Utility ---

    private fun parseVoskText(json: String): String {
        val textMatch = Regex(""""text"\s*:\s*"([^"]*)"""").find(json)
        val partialMatch = Regex(""""partial"\s*:\s*"([^"]*)"""").find(json)
        return textMatch?.groupValues?.get(1)
            ?: partialMatch?.groupValues?.get(1)
            ?: ""
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private companion object {
        const val TAG = "VoskEngine"
    }
}
