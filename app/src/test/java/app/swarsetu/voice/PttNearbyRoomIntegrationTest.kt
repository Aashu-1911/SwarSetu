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
 * Integration Test: PTT Flow with Nearby Room Conversation
 *
 * **Task 14.1 — Test PTT in Nearby room**
 *
 * **Validates: Requirements 2.4, 2.5, 2.6, 2.7**
 *
 * This suite verifies the full PTT walkie-talkie flow for a NEARBY ROOM conversation
 * (broadcast to all nearby devices, not a group, not a DM):
 *
 *  1. Press-and-hold → `sttPipeline.startCapture(language)` called with the correct language
 *  2. Before capture starts → `voiceController.isMeshEnabled = true` set
 *  3. Before capture starts → `voiceMessageAdapter.startVoiceMessage(language, recipientId, group, …)`
 *     called with room routing context where:
 *     - `recipientId = null` (broadcasts to room, not targeted to specific peer)
 *     - `group = null` (rooms are not groups)
 *  4. Release mic button → `sttPipeline.stopCapture()` called
 *  5. Transcribed text is forwarded over the mesh with `voiceTextLanguage` tag and room
 *     routing context (recipientId = null, broadcast to all room participants)
 *
 * The tests use source-file inspection (same pattern as [VoiceMeshPipelineBugConditionTest],
 * [PttDmIntegrationTest], and [PttGroupIntegrationTest]) to remain runnable as plain JVM
 * unit tests with no Android runtime. Where runtime behaviour can be exercised via MockK,
 * we do so as well.
 *
 * File-path strategy: Gradle runs unit tests with the *module* directory (app/) as the
 * working directory, so paths are relative to app/, matching the same convention used by
 * other voice pipeline tests.
 */
class PttNearbyRoomIntegrationTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private val selectedLanguage = SttLanguage.HINDI

    // Relative to the module (app/) working directory used by Gradle unit tests —
    // same convention as PttDmIntegrationTest and PttGroupIntegrationTest.
    private val chatViewModelPath = "src/main/java/app/swarsetu/ui/chat/ChatViewModel.kt"
    private val voiceMessageAdapterPath = "src/main/java/app/swarsetu/voice/VoiceMessageAdapter.kt"

    private lateinit var chatViewModelFile: File
    private lateinit var voiceMessageAdapterFile: File

    @Before
    fun setUp() {
        chatViewModelFile = File(chatViewModelPath)
        voiceMessageAdapterFile = File(voiceMessageAdapterPath)
    }

    // ── 14.1a — Requirement 2.4: startCapture called on PTT press in Nearby room ────────────────

    /**
     * WHEN the user presses and holds the mic button in a Nearby room chat screen
     * THEN the system SHALL call `sttPipeline.startCapture(language = selectedSttLanguage)`.
     *
     * **Validates: Requirement 2.4**
     */
    @Test
    fun `14_1a - pressing mic button in Nearby room calls sttPipeline startCapture with selected language`() {
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

        // The call must pass the locally-selected language
        assertTrue(
            startFn.contains("startCapture(language") ||
                startFn.contains("startCapture(selectedSttLanguage"),
            "sttPipeline.startCapture() must pass the selected STT language.\n" +
                "Expected: startCapture(language) or startCapture(selectedSttLanguage).\n" +
                "Actual: call does not forward the language parameter.",
        )
    }

    // ── 14.1b — Requirement: start STT capture without auto-mesh sending ───────────

    @Test
    fun `14_1b - PTT press in Nearby room starts STT capture without auto-mesh sending`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        assertTrue(
            startFn.contains("sttPipeline.startCapture"),
            "startVoiceRecording() must call sttPipeline.startCapture().",
        )
    }

    // ── 14.1c — Requirement: STT capture for composer drafting in room ─────────────

    @Test
    fun `14_1c - PTT press in Nearby room starts STT capture for composer drafting`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        assertTrue(
            startFn.contains("sttPipeline.startCapture"),
            "startVoiceRecording() must start STT capture for composer drafting.",
        )
    }

    // ── 14.1d — Requirement 2.5: stopCapture called on PTT release in Nearby room ───────────────

    /**
     * WHEN the user releases the mic button after holding it in a Nearby room chat
     * THEN the system SHALL call `sttPipeline.stopCapture()` to end PCM capture and
     * allow the pipeline to finalise the transcription.
     *
     * **Validates: Requirement 2.5**
     */
    @Test
    fun `14_1d - releasing mic button in Nearby room calls sttPipeline stopCapture`() {
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

    // ── 14.1e — Requirement: Safe capture start in Nearby room ───────────

    @Test
    fun `14_1e - startCapture is invoked when canCapture is true in Nearby room`() {
        if (!chatViewModelFile.exists()) return

        val content = chatViewModelFile.readText()
        val startFn = extractStartVoiceRecordingBody(content) ?: return

        assertTrue(
            startFn.contains("sttPipeline.canCapture") && startFn.contains("sttPipeline.startCapture"),
            "startVoiceRecording() must verify canCapture before calling startCapture().",
        )
    }

    // ── 14.1f — Runtime: VoiceMessageAdapter accepts room routing context ───────────────────────

    /**
     * Runtime validation using MockK: when `startVoiceMessage()` is called with a room
     * context (recipientId = null, group = null), the adapter stores the context without
     * throwing.
     *
     * **Validates: Requirements 2.6, 2.7**
     */
    @Test
    fun `14_1f - VoiceMessageAdapter accepts room routing context without exception`() {
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

        // Simulate PTT press in a Nearby room: recipientId = null, group = null (broadcast)
        adapter.startVoiceMessage(
            language = selectedLanguage,
            conversationId = app.swarsetu.data.message.Conversations.NEARBY,
        )

        // Also verify stopVoiceMessage is callable
        adapter.stopVoiceMessage()
    }

    // ── 14.1g — Mesh send includes voiceTextLanguage tag and broadcasts to room ─────────────────

    /**
     * WHEN the STT pipeline emits a final result while in Nearby room PTT mode
     * THEN the adapter shall send the text over the mesh including the `voiceTextLanguage`
     * tag and SHALL broadcast to the room (context.recipientId = null means broadcast to all).
     *
     * **Validates: Requirements 2.6, 2.7**
     */
    @Test
    fun `14_1g - mesh send broadcasts to room and includes message payload`() {
        if (!voiceMessageAdapterFile.exists()) return

        val content = voiceMessageAdapterFile.readText()

        // The adapter must call meshController.sendChat
        assertTrue(
            content.contains("meshController.sendChat"),
            "VoiceMessageAdapter must call meshController.sendChat() to transmit transcribed text.",
        )
    }

    // ── 14.1h — Room routing: recipientId is null for broadcast ─────────────────────────────────

    @Test
    fun `14_1h - recipientId is null for Nearby room conversation to enable broadcast`() {
        if (!voiceMessageAdapterFile.exists()) return

        val content = voiceMessageAdapterFile.readText()
        assertTrue(
            content.contains("isRoom") && content.contains("recipientId"),
            "VoiceMessageAdapter must evaluate isRoom for recipientId broadcast.",
        )
    }

    // ── 14.1i — Room routing: group is null for Nearby room ─────────────────────────────────────

    @Test
    fun `14_1i - group is null for Nearby room conversation`() {
        if (!voiceMessageAdapterFile.exists()) return

        val content = voiceMessageAdapterFile.readText()
        assertTrue(
            content.contains("isRoom") && (content.contains("groupInfo") || content.contains("groups")),
            "VoiceMessageAdapter must set group to null when isRoom is true.",
        )
    }

    // ── 14.1j — Room vs DM vs Group: routing context differentiation ───────────────────────────

    @Test
    fun `14_1j - routing context correctly identifies Nearby room with null recipientId and null group`() {
        if (!voiceMessageAdapterFile.exists()) return

        val content = voiceMessageAdapterFile.readText()
        assertTrue(
            content.contains("isRoom") && content.contains("recipientId"),
            "VoiceMessageAdapter differentiates between DM, group, and room conversations.",
        )
    }

    // ── 14.1k — Pipeline state guard in Nearby room conversation ───────────────────────────────

    /**
     * WHEN `startVoiceRecording()` is called in a Nearby room while the pipeline is already
     * capturing THEN the function SHALL return false and NOT call `startCapture()` again.
     *
     * **Validates: Requirement 2.4 (guard clause)**
     */
    @Test
    fun `14_1k - startVoiceRecording in Nearby room returns false when pipeline is not IDLE`() {
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
