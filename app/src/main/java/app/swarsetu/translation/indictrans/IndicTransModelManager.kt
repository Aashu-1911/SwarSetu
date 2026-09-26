package app.swarsetu.translation.indictrans

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class IndicTransModelBundle(
    val bundleKey: String,
    val repoId: String,
    val displayName: String,
    val description: String,
    val approxSizeMb: Int,
    val files: List<ModelFileSpec>,
) {
    INDIC_INDIC(
        bundleKey = "indic-indic-320m",
        repoId = "hari31416/indictrans2-indic-indic-dist-320M-ONNX-int8",
        displayName = "Indic → Indic (320M INT8)",
        description = "Direct neural translation between 22 Indian languages (Hindi, Marathi, Tamil, Telugu, etc.)",
        approxSizeMb = 371,
        files = listOf(
            ModelFileSpec("encoder_model.onnx", 829_000L),
            ModelFileSpec("encoder_model.onnx.data", 120_000_000L),
            ModelFileSpec("decoder_model.onnx", 2_000_000L),
            ModelFileSpec("decoder_shared.onnx.data", 203_000_000L),
            ModelFileSpec("decoder_with_past_model.onnx", 1_850_000L),
            ModelFileSpec("dict.SRC.json", 3_390_000L),
            ModelFileSpec("dict.TGT.json", 3_390_000L),
            ModelFileSpec("generation_config.json", 1_000L),
            ModelFileSpec("tokenizer_meta.json", 1_000L),
        ),
    ),
    EN_INDIC(
        bundleKey = "en-indic-200m",
        repoId = "hari31416/indictrans2-en-indic-dist-200M-ONNX-int8",
        displayName = "English → Indic (200M INT8)",
        description = "Neural translation from English into 22 Indian languages",
        approxSizeMb = 303,
        files = listOf(
            ModelFileSpec("encoder_model.onnx", 829_000L),
            ModelFileSpec("encoder_model.onnx.data", 73_800_000L),
            ModelFileSpec("decoder_model.onnx", 2_000_000L),
            ModelFileSpec("decoder_shared.onnx.data", 203_000_000L),
            ModelFileSpec("decoder_with_past_model.onnx", 1_850_000L),
            ModelFileSpec("dict.SRC.json", 650_000L),
            ModelFileSpec("dict.TGT.json", 3_390_000L),
            ModelFileSpec("generation_config.json", 1_000L),
            ModelFileSpec("tokenizer_meta.json", 1_000L),
        ),
    ),
    INDIC_EN(
        bundleKey = "indic-en-200m",
        repoId = "hari31416/indictrans2-indic-en-dist-200M-ONNX-int8",
        displayName = "Indic → English (200M INT8)",
        description = "Neural translation from 22 Indian languages into English",
        approxSizeMb = 258,
        files = listOf(
            ModelFileSpec("encoder_model.onnx", 829_000L),
            ModelFileSpec("encoder_model.onnx.data", 120_000_000L),
            ModelFileSpec("decoder_model.onnx", 1_580_000L),
            ModelFileSpec("decoder_shared.onnx.data", 110_000_000L),
            ModelFileSpec("decoder_with_past_model.onnx", 1_400_000L),
            ModelFileSpec("dict.SRC.json", 3_390_000L),
            ModelFileSpec("dict.TGT.json", 640_000L),
            ModelFileSpec("generation_config.json", 1_000L),
            ModelFileSpec("tokenizer_meta.json", 1_000L),
        ),
    );

    val totalExpectedBytes: Long get() = files.sumOf { it.approxBytes }
}

data class ModelFileSpec(
    val filename: String,
    val approxBytes: Long,
)

/**
 * Manages storage, integrity verification, and in-app downloading of
 * IndicTrans2 ONNX models from Hugging Face into application storage.
 */
class IndicTransModelManager(
    private val context: Context? = null,
    baseDirectory: File? = null,
) {
    companion object {
        private const val TAG = "IndicTransModelManager"
        private const val BASE_HF_URL = "https://huggingface.co"
    }

    private val baseDir: File = baseDirectory
        ?: context?.let { File(it.filesDir, "models/indictrans2") }
        ?: File(System.getProperty("java.io.tmpdir"), "swarsetu_indictrans2_models").apply { mkdirs() }

    fun getModelDir(bundle: IndicTransModelBundle): File {
        return File(baseDir, bundle.bundleKey).apply { if (!exists()) mkdirs() }
    }

    /**
     * Checks if all required ONNX and tokenizer files are downloaded and non-empty.
     */
    fun isModelDownloaded(bundle: IndicTransModelBundle): Boolean {
        val dir = getModelDir(bundle)
        if (!dir.exists() || !dir.isDirectory) return false
        return bundle.files.all { spec ->
            val f = File(dir, spec.filename)
            f.exists() && f.isFile && f.length() > 0
        }
    }

    /**
     * Total size in bytes currently downloaded on disk for the given bundle.
     */
    fun getDownloadedBytes(bundle: IndicTransModelBundle): Long {
        val dir = getModelDir(bundle)
        if (!dir.exists()) return 0L
        return bundle.files.sumOf { spec ->
            val f = File(dir, spec.filename)
            if (f.exists()) f.length() else 0L
        }
    }

    /**
     * Deletes downloaded files for a model bundle to reclaim storage.
     */
    fun deleteModel(bundle: IndicTransModelBundle): Boolean {
        val dir = getModelDir(bundle)
        return try {
            if (dir.exists()) {
                dir.deleteRecursively()
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model ${bundle.bundleKey}", e)
            false
        }
    }

    /**
     * Downloads all files for a model bundle from Hugging Face with progress callbacks.
     */
    suspend fun downloadModel(
        bundle: IndicTransModelBundle,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int, currentFile: String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = getModelDir(bundle)
        val totalExpected = bundle.totalExpectedBytes
        var totalDownloaded = getDownloadedBytes(bundle)

        Log.i(TAG, "Starting download for ${bundle.displayName} into ${dir.absolutePath}")

        for (spec in bundle.files) {
            val targetFile = File(dir, spec.filename)
            if (targetFile.exists() && targetFile.length() > (spec.approxBytes * 0.8)) {
                Log.d(TAG, "File ${spec.filename} already exists (${targetFile.length()} bytes), skipping.")
                continue
            }

            val fileUrl = "$BASE_HF_URL/${bundle.repoId}/resolve/main/${spec.filename}"
            val success = downloadSingleFileWithRedirects(
                urlStr = fileUrl,
                destination = targetFile,
                onChunk = { bytesReadThisFile, fileSize ->
                    val overall = (totalDownloaded - (if (targetFile.exists()) targetFile.length() else 0L)) + bytesReadThisFile
                    val pct = if (totalExpected > 0) ((overall * 100) / totalExpected).toInt().coerceIn(0, 100) else 0
                    onProgress(overall, totalExpected, pct, spec.filename)
                },
            )

            if (!success) {
                Log.e(TAG, "Failed downloading file: ${spec.filename}")
                return@withContext false
            }

            totalDownloaded = getDownloadedBytes(bundle)
            val pct = if (totalExpected > 0) ((totalDownloaded * 100) / totalExpected).toInt().coerceIn(0, 100) else 0
            onProgress(totalDownloaded, totalExpected, pct, spec.filename)
        }

        val allReady = isModelDownloaded(bundle)
        Log.i(TAG, "Download finished for ${bundle.displayName}. Status: $allReady")
        return@withContext allReady
    }

    private fun downloadSingleFileWithRedirects(
        urlStr: String,
        destination: File,
        onChunk: (bytesRead: Long, fileSize: Long) -> Unit,
    ): Boolean {
        var currentUrl = urlStr
        var redirects = 0
        val maxRedirects = 5
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")

        try {
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "SwarSetu-IndicTrans2-Android")

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308
                ) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location == null) return false
                    currentUrl = if (location.startsWith("http")) location else URL(url, location).toString()
                    redirects++
                    continue
                }

                if (code !in 200..299) {
                    Log.e(TAG, "HTTP error $code for $currentUrl")
                    conn.disconnect()
                    return false
                }

                val contentLength = conn.contentLengthLong
                var bytesRead = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024) // 64 KB chunk
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            onChunk(bytesRead, contentLength)
                        }
                        output.flush()
                    }
                }
                conn.disconnect()

                if (tempFile.exists() && tempFile.length() > 0) {
                    if (destination.exists()) destination.delete()
                    val renamed = tempFile.renameTo(destination)
                    if (!renamed) {
                        tempFile.copyTo(destination, overwrite = true)
                        tempFile.delete()
                    }
                    return true
                } else {
                    tempFile.delete()
                    return false
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error downloading $urlStr", e)
            if (tempFile.exists()) tempFile.delete()
            return false
        }
        return false
    }
}
