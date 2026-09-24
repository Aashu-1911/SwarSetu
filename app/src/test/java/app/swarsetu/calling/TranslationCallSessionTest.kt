package app.swarsetu.calling

import app.swarsetu.mesh.MeshController
import app.swarsetu.stt.SttEngine
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttLiveStream
import app.swarsetu.stt.SttResult
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.voice.TtsManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationCallSessionTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `test call starts and initializes state`() = testScope.runTest {
        val meshController = mock(MeshController::class.java)
        val sttEngine = mock(SttEngine::class.java)
        val translatorEngine = mock(TranslatorEngine::class.java)
        val ttsManager = mock(TtsManager::class.java)

        `when`(meshController.callFrames).thenReturn(MutableSharedFlow())

        val session = TranslationCallSession(meshController, sttEngine, translatorEngine, ttsManager, this)
        
        session.startCall("peer123", "hi", "en")
        val state = session.state.value

        assertEquals("peer123", state.activePeerId)
        assertEquals("hi", state.sourceLanguage)
        assertEquals("en", state.targetLanguage)
        assertEquals(CallStatus.IDLE, state.status)
    }

    @Test
    fun `test listening updates status and starts stream`() = testScope.runTest {
        val meshController = mock(MeshController::class.java)
        val sttEngine = mock(SttEngine::class.java)
        val translatorEngine = mock(TranslatorEngine::class.java)
        val ttsManager = mock(TtsManager::class.java)
        val sttStream = mock(SttLiveStream::class.java)

        `when`(meshController.callFrames).thenReturn(MutableSharedFlow())
        `when`(sttEngine.isReady).thenReturn(true)
        `when`(sttEngine.startLiveStream(SttLanguage.fromCode("hi"))).thenReturn(sttStream)

        val session = TranslationCallSession(meshController, sttEngine, translatorEngine, ttsManager, this)
        session.startCall("peer123", "hi", "en")
        
        session.startListening()
        assertEquals(CallStatus.LISTENING, session.state.value.status)
        verify(sttEngine).startLiveStream(SttLanguage.fromCode("hi"))
    }

    @Test
    fun `test incoming frame updates state and translates when callerId matches`() = testScope.runTest {
        val meshController = mock(MeshController::class.java)
        val sttEngine = mock(SttEngine::class.java)
        val translatorEngine = mock(TranslatorEngine::class.java)
        val ttsManager = mock(TtsManager::class.java)

        val framesFlow = MutableSharedFlow<app.swarsetu.mesh.protocol.CallContent>()
        `when`(meshController.callFrames).thenReturn(framesFlow)
        `when`(translatorEngine.translate("hello", "en", "hi")).thenReturn("namaste")

        val session = TranslationCallSession(meshController, sttEngine, translatorEngine, ttsManager, this)
        session.startCall("caller123", "hi", "en") // we are talking to caller123

        // Send a frame from the correct caller
        framesFlow.emit(
            app.swarsetu.mesh.protocol.CallContent(
                sessionId = "me",
                callerId = "caller123",
                sequenceNumber = 0,
                sourceLanguage = "en",
                targetLanguage = "hi",
                sourceText = "hello",
                isFinal = true
            )
        )

        // Give coroutines time to process
        kotlinx.coroutines.test.runCurrent()

        val state = session.state.value
        assertEquals("namaste", state.translatedIncomingText)
        verify(ttsManager).speak("namaste", "hi")
    }
}
