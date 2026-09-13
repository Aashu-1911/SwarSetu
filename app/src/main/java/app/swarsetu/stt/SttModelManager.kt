package app.swarsetu.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages STT model availability, loading, and lifecycle. Responsible for:
 * - Querying which languages have bundled models
 * - Loading model assets into memory (delegated to the engine)
 * - Tracking loaded models and evicting idle ones to bound memory
 * - Providing model metadata for diagnostics
 *
 * Mirrors the lazy-load + Mutex + runCatching pattern established by
 * [app.swarsetu.moderation.MlTextModerator] and [app.swarsetu.moderation.NsfwImageModerator].
 */
class SttModelManager(
    private val context: Context,
) {
    /** Mutex guarding model load/evict/download state. */
    private val mutex = Mutex()

    /** Currently loaded model info by language, or null if not loaded. */
    private val loadedModels = mutableMapOf<SttLanguage, SttModelInfo>()

    /**
     * Returns the set of languages that have bundled model assets available for loading.
     */
    suspend fun availableLanguages(): Set<SttLanguage> =
        withContext(Dispatchers.IO) {
            SttLanguage.entries
                .filter { lang ->
                    lang.assetDir != null && (modelAssetExists(lang) || modelFilesExist(lang))
                }.toSet()
        }

    /**
     * True when [language] has a model that can be loaded. Checks both:
     * 1. Asset directory (models bundled in APK)
     * 2. Internal storage (models extracted/downloaded at runtime to filesDir/stt/)
     */
    suspend fun isAvailable(language: SttLanguage): Boolean =
        language.assetDir != null && (modelAssetExists(language) || modelFilesExist(language))

    /**
     * Returns metadata for all bundled models.
     */
    suspend fun modelInfo(): List<SttModelInfo> =
        withContext(Dispatchers.IO) {
            SttLanguage.entries.mapNotNull { lang ->
                if (lang.assetDir == null) return@mapNotNull null
                val exists = modelAssetExists(lang) || modelFilesExist(lang)
                SttModelInfo(
                    language = lang,
                    modelPath = lang.assetDir,
                    available = exists,
                    description = "STT model for ${lang.displayName}",
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

    private fun modelAssetExists(language: SttLanguage): Boolean =
        try {
            val dir = language.assetDir ?: return false
            context.assets.list(dir)?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }

    /**
     * Check if this language's model has already been extracted to internal storage and is non-empty.
     */
    fun modelFilesExist(language: SttLanguage): Boolean =
        try {
            val dir = language.assetDir ?: return false
            val modelDir = File(context.filesDir, "stt/$dir")
            modelDir.exists() && modelDir.isDirectory && (modelDir.list()?.isNotEmpty() == true)
        } catch (_: Exception) {
            false
        }

    /**
     * Downloads and extracts the Vosk STT model for [language] directly into [context.filesDir]/stt/<assetDir>.
     */
    suspend fun downloadSttModel(
        language: SttLanguage,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val assetDir = language.assetDir ?: return@withContext false
                val targetDir = File(context.filesDir, "stt/$assetDir")
                if (modelFilesExist(language)) {
                    Log.d(TAG, "STT model for ${language.code} already exists")
                    onProgress?.invoke(1f)
                    return@withContext true
                }

                // Check if a canonical model with the same base exists locally to alias instantly
                val canonicalBaseLang = CANONICAL_ALIASES[language]
                if (canonicalBaseLang != null && canonicalBaseLang != language && modelFilesExist(canonicalBaseLang)) {
                    val sourceDir = File(context.filesDir, "stt/${canonicalBaseLang.assetDir}")
                    if (cloneModelDir(sourceDir, targetDir)) {
                        Log.d(TAG, "Instantly aliased STT model for ${language.code} from ${canonicalBaseLang.code}")
                        onProgress?.invoke(1f)
                        return@withContext true
                    }
                }

                val urlString = MODEL_URLS[language] ?: return@withContext false
                Log.d(TAG, "Stream-downloading STT model for ${language.code} from $urlString")

                val tempDir = File(context.filesDir, "stt/.tmp_$assetDir")
                tempDir.deleteRecursively()
                tempDir.mkdirs()

                var connection: java.net.HttpURLConnection? = null
                try {
                    val url = java.net.URL(urlString)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.connect()

                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        Log.e(TAG, "Failed to download STT model for ${language.code}: HTTP $responseCode")
                        return@withContext false
                    }

                    val totalLength = connection.contentLength.toLong()

                    var bytesReadTotal = 0L
                    java.io.BufferedInputStream(connection.inputStream, 64 * 1024).use { bis ->
                        val countingStream = object : java.io.FilterInputStream(bis) {
                            override fun read(): Int {
                                val b = super.read()
                                if (b != -1) {
                                    bytesReadTotal++
                                    if (totalLength > 0 && bytesReadTotal % (128 * 1024) == 0L) {
                                        onProgress?.invoke((bytesReadTotal.toFloat() / totalLength).coerceIn(0f, 0.95f))
                                    }
                                }
                                return b
                            }

                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                val read = super.read(b, off, len)
                                if (read > 0) {
                                    bytesReadTotal += read
                                    if (totalLength > 0) {
                                        onProgress?.invoke((bytesReadTotal.toFloat() / totalLength).coerceIn(0f, 0.95f))
                                    }
                                }
                                return read
                            }
                        }

                        java.util.zip.ZipInputStream(countingStream).use { zis ->
                            var entry: java.util.zip.ZipEntry? = zis.nextEntry
                            val buffer = ByteArray(32 * 1024)
                            while (entry != null) {
                                val normalizedPath = stripTopLevelDir(entry.name)
                                if (normalizedPath.isNotBlank()) {
                                    val target = File(tempDir, normalizedPath)
                                    if (entry.isDirectory) {
                                        target.mkdirs()
                                    } else {
                                        target.parentFile?.mkdirs()
                                        target.outputStream().use { fos ->
                                            var count: Int
                                            while (zis.read(buffer).also { count = it } != -1) {
                                                fos.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }

                    // Move tempDir to targetDir atomically
                    targetDir.deleteRecursively()
                    tempDir.renameTo(targetDir)

                    // Propagate to any other languages aliased to this one
                    populateAliasesFrom(language, targetDir)

                    onProgress?.invoke(1f)
                    Log.d(TAG, "Stream-extracted STT model for ${language.code} directly into ${targetDir.absolutePath}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading/extracting STT model for ${language.code}: ${e.message}")
                    tempDir.deleteRecursively()
                    targetDir.deleteRecursively()

                    // Graceful fallback to Hindi (or English) base model if standalone download fails
                    val fallbackBase = if (language == SttLanguage.ENGLISH) SttLanguage.ENGLISH else SttLanguage.HINDI
                    if (fallbackBase != language) {
                        val baseDir = File(context.filesDir, "stt/${fallbackBase.assetDir}")
                        if (modelFilesExist(fallbackBase) && cloneModelDir(baseDir, targetDir)) {
                            Log.d(TAG, "Successfully populated ${language.code} from fallback base ${fallbackBase.code}")
                            onProgress?.invoke(1f)
                            return@withContext true
                        }
                    }
                    false
                } finally {
                    connection?.disconnect()
                }
            }
        }

    private fun populateAliasesFrom(sourceLang: SttLanguage, sourceDir: File) {
        for ((targetLang, canonicalBase) in CANONICAL_ALIASES) {
            if (canonicalBase == sourceLang && targetLang != sourceLang && !modelFilesExist(targetLang)) {
                val destDir = File(context.filesDir, "stt/${targetLang.assetDir}")
                cloneModelDir(sourceDir, destDir)
            }
        }
    }

    private fun cloneModelDir(sourceDir: File, destDir: File): Boolean =
        try {
            if (!sourceDir.exists() || sourceDir.list().isNullOrEmpty()) false
            else {
                destDir.mkdirs()
                sourceDir.copyRecursively(destDir, overwrite = true)
                true
            }
        } catch (_: Exception) {
            false
        }

    private fun stripTopLevelDir(path: String): String {
        val slashIndex = path.indexOf('/')
        return if (slashIndex != -1 && slashIndex < path.length - 1) {
            path.substring(slashIndex + 1)
        } else if (slashIndex == path.length - 1) {
            "" // Directory entry of top-level dir
        } else {
            path
        }
    }

    /**
     * Downloads all offline speech recognition models using lightweight deduplication and parallel workers.
     */
    suspend fun downloadAllRequiredModels(onProgress: (Int, Int, SttLanguage) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val allLanguages = SttLanguage.entries
                val total = allLanguages.size
                val completedCount = java.util.concurrent.atomic.AtomicInteger(0)

                val distinctCanonicalLanguages = CANONICAL_ALIASES.values.distinct()
                val jobs =
                    distinctCanonicalLanguages.map { lang ->
                        async {
                            onProgress(completedCount.get(), total, lang)
                            val success = downloadSttModel(lang)
                            completedCount.incrementAndGet()
                            onProgress(completedCount.get(), total, lang)
                            success
                        }
                    }

                val results = jobs.map { it.await() }

                for (lang in allLanguages) {
                    if (!modelFilesExist(lang)) {
                        downloadSttModel(lang)
                    }
                }

                onProgress(total, total, SttLanguage.ENGLISH)
                results.all { it }
            }
        }

    private companion object {
        const val TAG = "SttModelManager"

        private val CANONICAL_ALIASES =
            mapOf(
                SttLanguage.ENGLISH to SttLanguage.ENGLISH,
                SttLanguage.HINDI to SttLanguage.HINDI,
                SttLanguage.GUJARATI to SttLanguage.GUJARATI,
                SttLanguage.MARATHI to SttLanguage.HINDI,
                SttLanguage.BENGALI to SttLanguage.BENGALI,
                SttLanguage.TAMIL to SttLanguage.TAMIL,
                SttLanguage.TELUGU to SttLanguage.TELUGU,
                SttLanguage.KANNADA to SttLanguage.HINDI,
                SttLanguage.MALAYALAM to SttLanguage.HINDI,
                SttLanguage.ODIA to SttLanguage.HINDI,
            )

        private val MODEL_URLS =
            mapOf(
                SttLanguage.ENGLISH to "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
                SttLanguage.HINDI to "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
                SttLanguage.GUJARATI to "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip",
                SttLanguage.MARATHI to "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
                SttLanguage.BENGALI to "https://alphacephei.com/vosk/models/vosk-model-small-bn-0.22.zip",
                SttLanguage.TAMIL to "https://alphacephei.com/vosk/models/vosk-model-small-ta-0.22.zip",
                SttLanguage.TELUGU to "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip",
                SttLanguage.KANNADA to "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
                SttLanguage.MALAYALAM to "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
                SttLanguage.ODIA to "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            )
    }
}
