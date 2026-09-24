package app.swarsetu.stt.sravaani

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.swarsetu.stt.SttConfig
import app.swarsetu.stt.SttEngineFactory
import app.swarsetu.stt.SttException
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttModelManager
import app.swarsetu.stt.SttResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class Sravaani10EngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newEngine(): Sravaani10Engine = Sravaani10Engine(context, SttModelManager(context))

    @Test
    fun `transcribe before initialize throws EngineNotReady`() = runTest {
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
    fun `live stream before initialize throws EngineNotReady`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            val stream = engine.startLiveStream(SttLanguage.HINDI)
            try {
                stream.pushPcm(ShortArray(1600))
                throw AssertionError("expected EngineNotReady")
            } catch (e: SttException.EngineNotReady) {
                assertTrue(e.message.orEmpty().contains("never initialized"))
            } finally {
                stream.release()
            }
        }
    }

    @Test
    fun `double release of Sravaani10Engine is safe and idempotent`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            engine.release()
            engine.release()
            assertFalse(engine.isReady)
        }
    }

    @Test
    fun `language switching updates currentLanguage across all 10 languages`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            val languages = listOf(
                SttLanguage.HINDI,
                SttLanguage.GUJARATI,
                SttLanguage.MARATHI,
                SttLanguage.KANNADA,
                SttLanguage.MALAYALAM,
                SttLanguage.TAMIL,
                SttLanguage.TELUGU,
                SttLanguage.ODIA,
                SttLanguage.BENGALI,
                SttLanguage.ENGLISH,
            )

            for (lang in languages) {
                engine.setLanguage(lang)
                assertEquals(lang, engine.currentLanguage)
            }
        }
    }

    @Test
    fun `SttEngineFactory instantiates SraVaani 1_0 production engine`() {
        val modelManager = SttModelManager(context)
        val factory = SttEngineFactory(context, modelManager)

        val engine = factory.create()
        assertNotNull(engine)
        assertEquals("1.0", SttEngineFactory.SRAAVAANI_VERSION)
    }

    @Test
    fun `empty PCM transcription returns empty result`() = runTest {
        withContext(Dispatchers.Default) {
            val engine = newEngine()
            // Even if not initialized, empty PCM returns immediately
            val result = engine.transcribe(ShortArray(0), SttLanguage.HINDI)
            assertEquals("", result.text)
            assertEquals(SttLanguage.HINDI, result.language)
        }
    }
}
