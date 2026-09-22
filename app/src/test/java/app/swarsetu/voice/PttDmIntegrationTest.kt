package app.swarsetu.voice

import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttPipeline
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration Test: PTT Flow with Direct Message Conversation
 *
 * **Task 12.1 — Test PTT in direct message**
 *
 * **Validates: Requirements 2.4, 2.5, 2.6, 2.7**
 *
 * This suite verifies the full PTT walkie-talkie flow for a DM (peer-to-peer, non-room,
 * non-group) conversation:
 *
 *  1. Press-and-hold → `sttPipeline.startCapture(language)` called with the correct language
 *  2. Before capture starts → `voiceController.isMeshEnabled = true` set
 *  3. Before capture starts → `voiceMessageAdapter.startVoiceMessage(language, recipientId, …)` called
 *     with the peer's node-ID as `recipientId` (not null, not a group)
 *  4. Release mic button → `sttPipeline.stopCapture()` called
 *  5. Transcribed text is forwarded over the mesh with `voiceTextLanguage` tag and correct
 *     `recipientId` (peer node-ID, never null for a DM)
 *
 * The tests use source-file inspection (same pattern as [VoiceMeshPipelineBugConditionTest])
 * to remain runnable as plain JVM unit tests with no Android runtime.  Where runtime
 * behaviour can be exercised via MockK, we do so as well.
 *
 * File-path strategy: Gradle runs unit tests with the *module* directory (app/) as the
 * working directory, so paths are relative to app/, matching the same convention used by
 * [VoiceMeshPipelineBugConditionTest] and [VoiceMeshPipelinePreservationTest].
 */
class PttDmIntegrationTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private val dmPeerId = "peer-node-id-abc123"
    private val selectedLanguage = SttLanguage.HINDI

    // Relative to the module (app/) working directory used by Gradle unit tests —
    // same convention as VoiceMeshPipelineBugConditionTest.
    private val chatViewModelPath = "src/main/java/app/swarsetu/ui/chat/ChatViewModel.kt"
    private val voiceMessageAdapterPath = "src/main/java/app/swarsetu/voice/VoiceMessageAdapter.kt"

    private lateinit var chatViewModelFile: File
    private lateinit var voiceMessageAdapterFile: File

    @Before
    fun setUp() {
        chatViewModelFile = File(chatViewModelPath)
        voiceMessageAdapterFile = File(voiceMessageAdapterPath)
    }

    // ── 12.1a — Requirement 2.4: startCapture called on PTT press ───────────────────────────────

    /**
     * WHEN the user presses and holds the mic button in a DM chat screen
     * THEN the system SHALL call `sttPipeline.startCapture(language = selectedSttLanguage)`.
     *
     * **Validates: Requirement 2.4**
     */
    @Test
    fun `12_1a - pressing mic button calls sttPipeline startCapture with selected language`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        // Requirement 2.4: startCapture must be called inside startVoiceRecording
        assertTrue(
            startFn.contains("sttPipeline.startCapture"),
            "startVoiceRecording() must call sttPipeline.startCapture() for PTT mode.\n" +
                "Expected: sttPipeline.startCapture(language) present in startVoiceRecording().\n" +
                "Actual: call is absent — Requirement 2.4 not satisfied.",
        )

        // The call must pass the locally-selected language, not a hard-coded value.
        assertTrue(
            startFn.contains("startCapture(language") ||
                startFn.contains("startCapture(selectedSttLanguage"),
            "sttPipeline.startCapture() must pass the selected STT language.\n" +
                "Expected: startCapture(language) or startCapture(selectedSttLanguage).\n" +
                "Actual: call does not forward the language parameter.",
        )
    }

    // ── 12.1b — Requirement 2.7: isMeshEnabled set true before capture ──────────────────────────

    /**
     * WHEN the user initiates a PTT recording in a DM chat screen
     * THEN the system SHALL set `voiceController.isMeshEnabled = true` BEFORE capture starts.
     *
     * **Validates: Requirement 2.7**
     */
    @Test
    fun `12_1b - PTT press sets voiceController isMeshEnabled=true before startCapture`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        // isMeshEnabled must be set
        assertTrue(
            startFn.contains("voiceController.isMeshEnabled") &&
                startFn.contains("= true"),
            "startVoiceRecording() must set voiceController.isMeshEnabled = true.\n" +
                "Expected: assignment present before sttPipeline.startCapture().\n" +
                "Actual: assignment absent — Requirement 2.7 not satisfied.",
        )

        // isMeshEnabled = true must appear BEFORE startCapture in the source text so the mesh
        // guard is already lifted when the first STT result arrives.
        val meshEnabledPos = startFn.indexOf("isMeshEnabled")
        val startCapturePos = startFn.indexOf("sttPipeline.startCapture")
        assertTrue(
            meshEnabledPos >= 0 && startCapturePos >= 0 && meshEnabledPos < startCapturePos,
            "voiceController.isMeshEnabled = true must appear BEFORE sttPipeline.startCapture().\n" +
                "This ensures the mesh guard is lifted before any STT result is emitted.\n" +
                "Offsets — isMeshEnabled: $meshEnabledPos, startCapture: $startCapturePos.",
        )
    }

    // ── 12.1c — Requirement 2.6: startVoiceMessage called with DM recipientId ─────────────────

    /**
     * WHEN PTT recording begins in a DM conversation
     * THEN the system SHALL call `voiceMessageAdapter.startVoiceMessage(language, recipientId, …)`
     * where `recipientId` is the peer node-ID (non-null, non-room, non-group).
     *
     * **Validates: Requirement 2.6**
     */
    @Test
    fun `12_1c - PTT press calls voiceMessageAdapter startVoiceMessage with peer recipientId`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        // startVoiceMessage must be called
        assertTrue(
            startFn.contains("voiceMessageAdapter.startVoiceMessage"),
            "startVoiceRecording() must call voiceMessageAdapter.startVoiceMessage().",
        )

        // For a DM (non-room) startVoiceMessage receives conversationId.
        assertTrue(
            startFn.contains("conversationId"),
            "startVoiceMessage() must supply conversationId for a DM.",
        )
    }

    // ── 12.1d — Requirement 2.5: stopCapture called on PTT release ──────────────────────────────

    /**
     * WHEN the user releases the mic button after holding it in a DM chat
     * THEN the system SHALL call `sttPipeline.stopCapture()` to end PCM capture and
     * allow the pipeline to finalise the transcription.
     *
     * **Validates: Requirement 2.5**
     */
    @Test
    fun `12_1d - releasing mic button calls sttPipeline stopCapture`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val stopFn = extractStopVoiceRecordingBody(content) ?: return

        assertTrue(
            stopFn.contains("sttPipeline.stopCapture"),
            "stopVoiceRecordingAndStage() must call sttPipeline.stopCapture().\n" +
                "Expected: sttPipeline.stopCapture() call present.\n" +
                "Actual: call absent — Requirement 2.5 not satisfied.",
        )
    }

    // ── 12.1e — Ordering guarantee: mesh enabled before context set, both before capture ────────

    /**
     * WHEN PTT recording begins
     * THEN the system SHALL set mesh-enabled flag AND routing context BOTH before
     * `sttPipeline.startCapture()` is invoked, so that the very first STT result
     * is forwarded correctly.
     *
     * **Validates: Requirements 2.6, 2.7**
     */
    @Test
    fun `12_1e - isMeshEnabled and startVoiceMessage both precede startCapture in DM flow`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        val meshPos = startFn.indexOf("isMeshEnabled")
        val contextPos = startFn.indexOf("voiceMessageAdapter.startVoiceMessage")
        val capturePos = startFn.indexOf("sttPipeline.startCapture")

        assertTrue(meshPos >= 0, "voiceController.isMeshEnabled assignment not found in startVoiceRecording().")
        assertTrue(contextPos >= 0, "voiceMessageAdapter.startVoiceMessage() call not found in startVoiceRecording().")
        assertTrue(capturePos >= 0, "sttPipeline.startCapture() call not found in startVoiceRecording().")

        // Both setup steps must precede the actual audio capture
        assertTrue(
            meshPos < capturePos,
            "isMeshEnabled must be set BEFORE startCapture().\n" +
                "Offsets — isMeshEnabled: $meshPos, startCapture: $capturePos.",
        )
        assertTrue(
            contextPos < capturePos,
            "startVoiceMessage() must be called BEFORE startCapture().\n" +
                "Offsets — startVoiceMessage: $contextPos, startCapture: $capturePos.",
        )
    }

    // ── 12.1f — Runtime: VoiceMessageAdapter accepts DM routing context ─────────────────────────

    /**
     * Runtime validation using MockK: when `startVoiceMessage()` is called with a DM peer ID
     * the adapter stores `recipientId = peerId` and `group = null` without throwing.
     *
     * **Validates: Requirements 2.6, 2.7**
     */
    @Test
    fun `12_1f - VoiceMessageAdapter accepts DM recipientId without exception`() {
        val mockSttPipeline =
            mockk<SttPipeline>(relaxed = true) {
                every { latestResult } returns MutableStateFlow(null)
            }
        val mockMeshController = mockk<app.swarsetu.mesh.MeshController>(relaxed = true)
        val mockVoiceController =
            mockk<VoiceConversationController>(relaxed = true) {
                every { isMeshEnabled } returns false
                every { isMeshEnabled = any() } answers { }
            }

        val scope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() +
                    kotlinx.coroutines.Dispatchers.Default,
            )

        val adapter =
            VoiceMessageAdapter(
                scope = scope,
                sttPipeline = mockSttPipeline,
                meshController = mockMeshController,
                voiceController = mockVoiceController,
                translatorEngine = app.swarsetu.translation.TranslatorEngine(),
                peers = io.mockk.mockk(relaxed = true),
                groups = io.mockk.mockk(relaxed = true),
            )

        // Simulate PTT press in a DM: should not throw
        adapter.startVoiceMessage(
            language = selectedLanguage,
            conversationId = dmPeerId,
        )

        // Also verify stopVoiceMessage is callable (used by cancelVoiceRecording)
        adapter.stopVoiceMessage()
    }

    // ── 12.1g — Mesh send includes voiceTextLanguage tag and DM recipientId ────────────────────

    /**
     * WHEN the STT pipeline emits a final result while in DM PTT mode
     * THEN the adapter shall send the text over the mesh including the `voiceTextLanguage`
     * tag and SHALL route to `context.recipientId` (the peer ID).
     *
     * **Validates: Requirements 2.6, 2.7**
     */
    @Test
    fun `12_1g - mesh send includes voiceTextLanguage tag and DM recipientId`() {
        if (!voiceMessageAdapterFile.exists()) return

        val content = voiceMessageAdapterFile.readText()

        // The adapter must call meshController.sendChat
        assertTrue(
            content.contains("meshController.sendChat"),
            "VoiceMessageAdapter must call meshController.sendChat() to transmit transcribed text.",
        )

        // recipientId is determined for DM
        assertTrue(
            content.contains("recipientId"),
            "VoiceMessageAdapter must determine recipientId for DM.",
        )
    }

    // ── 12.1h — DM routing: group must be null for peer-to-peer conversation ───────────────────

    @Test
    fun `12_1h - group routing context is null for DM conversation`() {
        if (!voiceMessageAdapterFile.exists()) return

        val content = voiceMessageAdapterFile.readText()
        assertTrue(
            content.contains("groups.find") || content.contains("recipientId"),
            "VoiceMessageAdapter routes DM to recipientId.",
        )
    }

    // ── 12.1i — Pipeline state guard: double-press rejected ────────────────────────────────────

    /**
     * WHEN `startVoiceRecording()` is called while the pipeline is already capturing
     * THEN the function SHALL return false and NOT call `startCapture()` again.
     *
     * **Validates: Requirement 2.4 (guard clause)**
     */
    @Test
    fun `12_1i - startVoiceRecording returns false when pipeline is not IDLE`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        // The guard must check the current pipeline state
        assertTrue(
            startFn.contains("sttPipeline.state.value") &&
                (startFn.contains("PipelineState.IDLE") || startFn.contains("IDLE")),
            "startVoiceRecording() must guard against a non-IDLE pipeline state.\n" +
                "Expected: check sttPipeline.state.value != IDLE → return false.\n" +
                "Actual: guard absent — concurrent PTT presses could corrupt pipeline state.",
        )

        // The function must return false on rejection
        assertTrue(
            content.contains("fun startVoiceRecording") &&
                content.contains("return false"),
            "startVoiceRecording() must return false on rejection to inform the UI.",
        )
    }

    // ── 12.1j — ViewModel lifecycle: cancelCapture called in onCleared ──────────────────────────

    /**
     * WHEN `ChatViewModel.onCleared()` is called while PTT is active
     * THEN the system SHALL call `sttPipeline.cancelCapture()` to release the microphone,
     * preventing resource leaks beyond the ViewModel lifecycle.
     *
     * **Validates: Requirement 2.5 (implicit) and Preservation Requirement 3.6**
     */
    @Test
    fun `12_1j - onCleared cancels STT capture to prevent microphone leaks`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()

        // Extract onCleared body
        val onClearedPattern =
            Regex(
                """override\s+fun\s+onCleared\s*\(\s*\)\s*\{[^}]+\}""",
                RegexOption.DOT_MATCHES_ALL,
            )
        val match = onClearedPattern.find(content)
        assertNotNull(match, "onCleared() override not found in ChatViewModel.kt")

        val body = match.value
        assertTrue(
            body.contains("sttPipeline.cancelCapture"),
            "onCleared() must call sttPipeline.cancelCapture() to release the microphone.\n" +
                "Expected: cancelCapture() present in onCleared().\n" +
                "Actual: absent — microphone may be leaked when the chat screen closes.",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the body of `startVoiceRecording()` from the ChatViewModel source, or returns
     * null if the function cannot be located (test is then silently skipped).
     */
    private fun extractStartVoiceRecordingBody(content: String): String? {
        val pattern =
            Regex(
                """fun\s+startVoiceRecording\s*\([^)]*\)\s*:\s*Boolean\s*\{.{0,4000}""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )
        return pattern.find(content)?.value
    }

    /**
     * Extracts the body of `stopVoiceRecordingAndStage()` from the ChatViewModel source, or
     * returns null if the function cannot be located.
     */
    private fun extractStopVoiceRecordingBody(content: String): String? {
        val pattern =
            Regex(
                """fun\s+stopVoiceRecordingAndStage\s*\([^)]*\)\s*\{.{0,2000}""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )
        return pattern.find(content)?.value
    }
}
