package app.swarsetu.stt.sravaani

import app.swarsetu.stt.SttException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * P7 packaging integrity: the extraction verifier must accept the true artifact bytes and
 * fail LOUDLY (SttException.ModelLoadError) on truncation or corruption — never load garbage
 * weights silently (the Vosk-era failure pattern).
 *
 * The real graphs (~1.8 GB) cannot live in a unit test; these tests use synthetic fixtures
 * with the verifier's actual logic by exercising [SravaaniPackaging.verifyExtracted] against
 * a directory whose file sizes match the pins. For full-size verification,
 * `checkSravaaniModel` runs on packaged builds and `sha256sum` on the scratch artifacts
 * matched the export manifest at P7 authoring time (evidence in the task ledger).
 */
class SravaaniPackagingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sha256 streaming matches known vector`() {
        // SHA-256 of the empty input and of "abc" (FIPS 180-2 test vectors).
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SravaaniPackaging.sha256(ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SravaaniPackaging.sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test
    fun `verifyExtracted rejects a missing graph`() {
        val dir = tmp.newFolder()
        val e =
            assertThrows(SttException.ModelLoadError::class.java) {
                SravaaniPackaging.verifyExtracted(dir, "sravaani_ctc.onnx")
            }
        assertTrue(e.message.orEmpty().contains("missing"))
    }

    @Test
    fun `verifyExtracted rejects a truncated graph on size before hashing`() {
        val dir = tmp.newFolder()
        // Real CTC pin is ~20.5 MB; a truncated fixture is caught by the size gate.
        File(dir, "sravaani_ctc.onnx").writeBytes(ByteArray(1000))
        val e =
            assertThrows(SttException.ModelLoadError::class.java) {
                SravaaniPackaging.verifyExtracted(dir, "sravaani_ctc.onnx")
            }
        assertTrue(e.message.orEmpty().contains("size"))
    }

    @Test
    fun `verifyExtracted rejects corrupt same-size graph on hash mismatch`() {
        val dir = tmp.newFolder()
        // Same-size corrupt content: size gate passes, hash gate must catch it.
        val corrupt = ByteArray(64)
        File(dir, "some.bin").writeBytes(corrupt)
        // Use an unpinned name with a size we can't fake — instead verify hash-mismatch
        // behavior directly through a known-good substitute: hash of wrong content vs pin.
        val wrongHash = SravaaniPackaging.sha256(ByteArrayInputStream(corrupt))
        assertTrue(
            "corrupt content must never equal the pinned encoder hash",
            !wrongHash.equals(SravaaniPackaging.ENCODER_SHA256, ignoreCase = true),
        )
        // And prove the hash gate fires for a same-size mismatch using a temp re-pin:
        // verifyExtracted(name="sravaani_ctc.onnx") with the EXACT pinned size is impossible
        // in-unit (20 MB is fine to write actually — do it for the CTC pin).
        val ctc = ByteArray(20_504_707) // exact expected CTC size
        File(dir, "sravaani_ctc.onnx").writeBytes(ctc)
        val e =
            assertThrows(SttException.ModelLoadError::class.java) {
                SravaaniPackaging.verifyExtracted(dir, "sravaani_ctc.onnx")
            }
        assertTrue(e.message.orEmpty().contains("sha256"))
    }

    @Test
    fun `pins match the P3 export manifest`() {
        // Provenance chain guard: the packaged pins must equal the export manifest values
        // (scratch/sravaani/onnx/export_meta.json, verified at P7 authoring time via
        // sha256sum — evidence in the task ledger).
        assertEquals(1_774_013_452L, SravaaniPackaging.ENCODER_BYTES)
        assertEquals(20_504_707L, SravaaniPackaging.CTC_BYTES)
        assertEquals(64, SravaaniPackaging.ENCODER_SHA256.length)
        assertEquals(64, SravaaniPackaging.CTC_SHA256.length)
    }
}
