package app.swarsetu.stt.sravaani

import app.swarsetu.stt.SttException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * P7 packaging integrity: SHA-256 pins for the exported ONNX graphs, verified (a) at build
 * time by the `checkSravaaniModel` Gradle task and (b) at extraction time by
 * [SravaaniEngine] — a corrupted asset must fail LOUDLY (ModelLoadError → DefaultSttEngine
 * fallback), never load garbage weights.
 *
 * Hashes are the provenance chain: official HF fp16 artifact (rev `29a15303`, P1 manifest) →
 * `scripts/sravaani/export_onnx.py` (P3, equivalence-gated) → fp32 ONNX encoder →
 * `scripts/sravaani/optimize_int8.py` (P9; `int8_meta.json` links the fp32 pin) → these exact
 * bytes. The PRODUCTION encoder asset is the P9 weight-only INT8 graph: real-speech WER parity
 * measured on FLEURS dev (10 langs × 10 utt, 2026-09-17): aggregate WER 0.2401 vs fp32 0.2396
 * (+0.05 pp, 6/10 languages bit-identical), 2.778× smaller (638,695,714 B vs 1,774,013,452 B).
 * The fp32 graph stays in `scratch/sravaani/onnx/` as the reference/baseline artifact.
 */
object SravaaniPackaging {
    /** Expected SHA-256 of `assets/sravaani/sravaani_encoder.onnx` (P9 int8 production graph). */
    const val ENCODER_SHA256 = "e074e56a91e71c534b7be72bb75c023c823e0475c9a165cf3551dc461eb5c515"

    /** Expected byte count of the encoder graph (int8_meta.json). */
    const val ENCODER_BYTES = 638_695_714L

    /** Expected SHA-256 of `assets/sravaani/sravaani_ctc.onnx` (P3 export manifest). */
    const val CTC_SHA256 = "6001671902c431b533452c78cedc3bafdccbeb8ae4f6ed1c23c0612466e1bb31"

    /** Expected byte count of the CTC graph (export manifest). */
    const val CTC_BYTES = 20_504_707L

    private fun expectedSha256(name: String): String? =
        when (name) {
            "sravaani_encoder.onnx" -> ENCODER_SHA256
            "sravaani_ctc.onnx" -> CTC_SHA256
            else -> null
        }

    private fun expectedBytes(name: String): Long? =
        when (name) {
            "sravaani_encoder.onnx" -> ENCODER_BYTES
            "sravaani_ctc.onnx" -> CTC_BYTES
            else -> null
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
     * Verify an extracted graph file: size then SHA-256. Returns silently on success;
     * throws [SttException.ModelLoadError] on any mismatch.
     */
    fun verifyExtracted(dir: File, name: String) {
        val file = File(dir, name)
        if (!file.isFile) {
            throw SttException.ModelLoadError("extracted graph missing: ${file.path}")
        }
        expectedBytes(name)?.let { expected ->
            if (file.length() != expected) {
                throw SttException.ModelLoadError(
                    "$name size ${file.length()} != expected $expected (corrupt extraction)",
                )
            }
        }
        val actual = file.inputStream().use { sha256(it) }
        val expectedHash = expectedSha256(name) ?: return // unpinned file: size check only
        if (!actual.equals(expectedHash, ignoreCase = true)) {
            throw SttException.ModelLoadError(
                "$name sha256 $actual != expected $expectedHash (corrupt or substituted graph)",
            )
        }
    }
}
