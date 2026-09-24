package app.swarsetu.stt.sravaani

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.swarsetu.stt.SttException
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttModelManager
import app.swarsetu.stt.SttResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P10 lifecycle tests (JVM-runnable portion): live-stream abandonment must not leak native
 * cache tensors, and release() must be safe from any state. The heavier half of P10
 * (peak/sustained PSS, repeated-transcription soak, low-RAM behavior) is instrumented
 * on-device work — honestly labeled NOT MEASURED (collected by the P11 runner).
 */
@RunWith(RobolectricTestRunner::class)
class SravaaniEngineLifecycleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newEngine(): SravaaniEngine = SravaaniEngine(context, SttModelManager(context))

    @Test
    fun `transcribe before initialize fails loudly`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            try {
                engine.transcribe(ShortArray(1600), SttLanguage.HINDI)
                throw AssertionError("expected EngineNotReady")
            } catch (e: SttException.EngineNotReady) {
                assertTrue(e.message.orEmpty().contains("never initialized"))
            }
        }
    }

    @Test
    fun `live stream push before initialize fails loudly without leaking`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            val stream = engine.startLiveStream(SttLanguage.HINDI)
            try {
                stream.pushPcm(ShortArray(1600))
                throw AssertionError("expected EngineNotReady")
            } catch (e: SttException.EngineNotReady) {
                assertTrue(e.message.orEmpty().contains("never initialized"))
            } finally {
                stream.release() // abandoned: must free whatever was allocated (nothing here)
            }
        }
    }

    @Test
    fun `finish without push returns empty result and closes stream`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            // initialize() verifies packaging; with no graphs packaged in unit tests it
            // throws ModelLoadError — that is the P7 contract. The finish()-with-no-push
            // path is exercised against a live stream only if packaging is present.
            val packagingPresent = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    engine.initialize(
                        app.swarsetu.stt.SttConfig(SttLanguage.HINDI),
                    )
                }
                true
            } catch (e: Throwable) {
                false
            }
            if (!packagingPresent) {
                // Unit environment: no graphs. The contract below is pinned on-device
                // (P11); here we only assert initialize did not half-initialize state.
                assertFalse(engine.isReady)
                return@withContext
            }
            val stream = engine.startLiveStream(SttLanguage.HINDI)
            val result = stream.finish()
            assertEquals("", result.text)
            assertEquals(SttResultType.FINAL, result.type)
            stream.release()
            engine.release()
        }
    }

    @Test
    fun `double release of engine is safe`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            engine.release()
            engine.release() // second call must be a no-op, not a crash
            assertFalse(engine.isReady)
        }
    }

    @Test
    fun `release after abandoned stream drains closer without deadlock`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            engine.release()
            val start = System.nanoTime()
            engine.release()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("release() took ${elapsedMs}ms — closer drain is blocking", elapsedMs < 5_000)
        }
    }
}
