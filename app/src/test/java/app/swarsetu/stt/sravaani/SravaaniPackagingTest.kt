package app.swarsetu.stt.sravaani

import app.swarsetu.stt.SttException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Packaging integrity: the extraction verifier must accept true artifact bytes and
 * fail LOUDLY (SttException.ModelLoadError) on truncation or corruption.
 */
class SravaaniPackagingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sha256 streaming matches known vector`() {
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
                SravaaniPackaging.verifyExtracted(dir, "decoder_joint.onnx")
            }
        assertTrue(e.message.orEmpty().contains("missing"))
    }

    @Test
    fun `verifyExtracted rejects a truncated graph on size before hashing`() {
        val dir = tmp.newFolder()
        File(dir, "decoder_joint.onnx").writeBytes(ByteArray(1000))
        val e =
            assertThrows(SttException.ModelLoadError::class.java) {
                SravaaniPackaging.verifyExtracted(dir, "decoder_joint.onnx")
            }
        assertTrue(e.message.orEmpty().contains("size"))
    }

    @Test
    fun `verifyExtracted rejects corrupt same-size graph on hash mismatch`() {
        val dir = tmp.newFolder()
        val corrupt = ByteArray(64)
        File(dir, "some.bin").writeBytes(corrupt)
        val wrongHash = SravaaniPackaging.sha256(ByteArrayInputStream(corrupt))
        assertTrue(
            "corrupt content must never equal the pinned encoder hash",
            !wrongHash.equals(SravaaniPackaging.ENCODER_SHA256, ignoreCase = true),
        )
        val dec = ByteArray(SravaaniPackaging.DECODER_JOINT_BYTES.toInt())
        File(dir, "decoder_joint.onnx").writeBytes(dec)
        val e =
            assertThrows(SttException.ModelLoadError::class.java) {
                SravaaniPackaging.verifyExtracted(dir, "decoder_joint.onnx")
            }
        assertTrue(e.message.orEmpty().contains("sha256"))
    }

    @Test
    fun `pins match the SraVaani 1_0 production manifest`() {
        assertEquals(886_754_376L, SravaaniPackaging.ENCODER_10_FP16_BYTES)
        assertEquals(21_519_253L, SravaaniPackaging.DECODER_JOINT_10_FP16_BYTES)
        assertEquals(133_220L, SravaaniPackaging.TABLES_10_BYTES)
        assertEquals(50_006L, SravaaniPackaging.TOKENS_10_BYTES)
        assertEquals("0a90a983bbfa8b3468246b2217894e6d6f331de14e8f08cf38fa8ffcdbf897da", SravaaniPackaging.ENCODER_10_FP16_SHA256)
        assertEquals("9a514c1800f2d9fd18538db358d034aaa78be324e43ad43bc4b45e73e2b180d6", SravaaniPackaging.DECODER_JOINT_10_FP16_SHA256)
        assertEquals("968e6125f2d90ba05dc7cdb8decdc6b62fce6108a2f7ed09cf6fbd5903a376e6", SravaaniPackaging.TABLES_10_SHA256)
        assertEquals("50a9727d0dca2dbfbc420d4a6c7f3cba2d5cd177fc2263516741a842308128a2", SravaaniPackaging.TOKENS_10_SHA256)
    }
}
