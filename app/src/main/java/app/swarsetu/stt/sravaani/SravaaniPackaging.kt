package app.swarsetu.stt.sravaani

import app.swarsetu.stt.SttException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SraVaani 1.0 packaging integrity: SHA-256 and size verification for ONNX model artifacts,
 * ensuring valid graphs are loaded without corruption.
 */
object SravaaniPackaging {
    // ---- SraVaani 1.0 (FP16 FastConformer-TDT) ----
    const val ENCODER_10_FP16_SHA256 = "0a90a983bbfa8b3468246b2217894e6d6f331de14e8f08cf38fa8ffcdbf897da"
    const val ENCODER_10_FP16_BYTES = 886_754_376L
    const val ENCODER_FP16_SHA256 = ENCODER_10_FP16_SHA256
    const val ENCODER_FP16_BYTES = ENCODER_10_FP16_BYTES
    const val ENCODER_SHA256 = ENCODER_10_FP16_SHA256
    const val ENCODER_BYTES = ENCODER_10_FP16_BYTES

    const val DECODER_JOINT_10_FP16_SHA256 = "9a514c1800f2d9fd18538db358d034aaa78be324e43ad43bc4b45e73e2b180d6"
    const val DECODER_JOINT_10_FP16_BYTES = 21_519_253L
    const val DECODER_JOINT_FP16_SHA256 = DECODER_JOINT_10_FP16_SHA256
    const val DECODER_JOINT_FP16_BYTES = DECODER_JOINT_10_FP16_BYTES
    const val DECODER_JOINT_SHA256 = DECODER_JOINT_10_FP16_SHA256
    const val DECODER_JOINT_BYTES = DECODER_JOINT_10_FP16_BYTES

    const val TABLES_10_SHA256 = "968e6125f2d90ba05dc7cdb8decdc6b62fce6108a2f7ed09cf6fbd5903a376e6"
    const val TABLES_10_BYTES = 133_220L
    const val TABLES_SHA256 = TABLES_10_SHA256
    const val TABLES_BYTES = TABLES_10_BYTES

    const val TOKENS_10_SHA256 = "50a9727d0dca2dbfbc420d4a6c7f3cba2d5cd177fc2263516741a842308128a2"
    const val TOKENS_10_BYTES = 50_006L
    const val TOKENS_SHA256 = TOKENS_10_SHA256
    const val TOKENS_BYTES = TOKENS_10_BYTES

    private val VALID_ENCODER_SIZES = setOf(ENCODER_10_FP16_BYTES)
    private val VALID_DECODER_SIZES = setOf(DECODER_JOINT_10_FP16_BYTES)

    private fun isValidSize(name: String, size: Long): Boolean =
        when (name) {
            "encoder.onnx" -> VALID_ENCODER_SIZES.contains(size)
            "decoder_joint.onnx" -> VALID_DECODER_SIZES.contains(size)
            "tables.bin" -> size in 130_000L..140_000L
            "tokens.txt" -> size in 45_000L..60_000L
            else -> true
        }

    /** Streaming SHA-256 of an [InputStream] (1 MB buffer — no full-file allocation). */
    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify an extracted or downloaded graph file: size then SHA-256 against pinned 1.0 artifacts.
     * Returns silently on success; throws [SttException.ModelLoadError] on any mismatch.
     */
    fun verifyExtracted(dir: File, name: String) {
        val file = File(dir, name)
        if (!file.isFile) {
            throw SttException.ModelLoadError("model file missing: ${file.path}")
        }
        if (!isValidSize(name, file.length())) {
            throw SttException.ModelLoadError(
                "$name size ${file.length()} is not an accepted valid graph size (corrupt extraction)",
            )
        }
        val actual = file.inputStream().use { sha256(it) }
        val acceptedHashes = when (name) {
            "encoder.onnx" -> setOf(ENCODER_10_FP16_SHA256)
            "decoder_joint.onnx" -> setOf(DECODER_JOINT_10_FP16_SHA256)
            "tables.bin" -> setOf(TABLES_10_SHA256)
            "tokens.txt" -> setOf(TOKENS_10_SHA256)
            else -> emptySet()
        }
        if (acceptedHashes.isNotEmpty() && !acceptedHashes.any { it.equals(actual, ignoreCase = true) }) {
            throw SttException.ModelLoadError(
                "$name sha256 $actual does not match accepted model hashes (corrupt or substituted graph)",
            )
        }
    }
}
