package app.swarsetu.stt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.swarsetu.stt.sravaani.SravaaniPackaging
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SttModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = SttModelManager(context)

    @Test
    fun `model directory points to version 1_0 path`() {
        val dir10 = manager.modelDir
        assertTrue(dir10.path.contains("1.0"))
    }

    @Test
    fun `computeSha256 produces exact hex string`() {
        val testFile = tmp.newFile("test.bin")
        testFile.writeText("hello world")

        val hash = manager.computeSha256(testFile)
        // sha256 of "hello world" is b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun `verifyModelFiles reports result for version 1_0`() {
        val result = manager.verifyModelFiles("1.0")
        assertNotNull(result)
    }

    @Test
    fun `stageLocalArtifacts validates SHA256 and rejects corrupt payloads`() {
        val scratchDir = tmp.newFolder("fake_scratch")
        val corruptEncoder = File(scratchDir, "encoder.onnx")
        corruptEncoder.writeBytes(ByteArray(100))

        val staged = manager.stageLocalArtifacts(scratchDir, "1.0")
        assertFalse("Staging corrupt artifacts must fail", staged)
    }

    @Test
    fun `download state starts Idle`() = runTest {
        val state = manager.downloadState.value
        assertEquals(SttModelManager.ModelDownloadState.Idle, state)
    }

    @Test
    fun `markLoaded and markReleased track active model state`() = runTest {
        assertEquals(0, manager.loadedCount())

        val info = SttModelInfo(
            language = SttLanguage.HINDI,
            modelPath = manager.modelDir.absolutePath,
            available = true,
            description = "Test Model",
        )

        manager.markLoaded(info)
        assertEquals(1, manager.loadedCount())
        assertEquals(info, manager.loadedInfo(SttLanguage.HINDI))

        manager.markReleased(SttLanguage.HINDI)
        assertEquals(0, manager.loadedCount())
    }
}
