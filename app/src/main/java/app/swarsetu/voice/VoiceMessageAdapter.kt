package app.swarsetu.voice

import app.swarsetu.data.group.toGroupInfo
import app.swarsetu.mesh.MeshController
import app.swarsetu.mesh.crypto.MessageContent
import app.swarsetu.mesh.protocol.GroupInfo
import app.swarsetu.mesh.protocol.ReplyRef
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttPipeline
import app.swarsetu.stt.SttResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges the local STT pipeline to the Mesh network.
 *
 * When a voice message is initiated, this adapter configures the routing context.
 * As soon as the STT pipeline produces a final, usable result, the adapter composes and
 * originates a new chat message over the mesh.
 */
class VoiceMessageAdapter(
    scope: CoroutineScope,
    private val sttPipeline: SttPipeline,
    private val meshController: MeshController,
    private val voiceController: VoiceConversationController,
    private val translatorEngine: app.swarsetu.translation.TranslatorEngine,
    private val peers: app.swarsetu.data.PeerRepository,
    private val groups: app.swarsetu.data.GroupRepository,
) {
    private data class RoutingContext(
        val language: SttLanguage,
        val conversationId: String,
        val replyTo: ReplyRef?,
        val isAlert: Boolean,
    )

    private val currentContext = AtomicReference<RoutingContext?>(null)
    private var lastSentResult: SttResult? = null

    init {
        scope.launch {
            sttPipeline.latestResult.collect { result ->
                // Check if MESH mode is enabled at the controller level and context is set
                if (result != null && voiceController.isMeshEnabled) {
                    val context = currentContext.get()
                    if (context != null) {
                        handleSttResult(result)
                    } else {
                        android.util.Log.w("VoiceMessageAdapter", "STT result received but routing context not ready yet")
                    }
                }
            }
        }
    }

    /**
     * Starts listening for a voice message that will be sent over the mesh.
     * Context is set synchronously without blocking on database queries.
     */
    fun startVoiceMessage(
        language: SttLanguage,
        conversationId: String = app.swarsetu.data.message.Conversations.NEARBY,
        replyTo: ReplyRef? = null,
        isAlert: Boolean = false,
    ) {
        currentContext.set(RoutingContext(language, conversationId, replyTo, isAlert))
    }

    fun stopVoiceMessage() {
        currentContext.set(null)
    }

    private suspend fun handleSttResult(result: SttResult) {
        if (!result.isUsable) {
            android.util.Log.d("SW_MESSAGE", "STT result not usable (empty/blank)")
            return
        }
        if (result === lastSentResult) return
        lastSentResult = result

        val context = currentContext.get() ?: return
        android.util.Log.d("SW_MESSAGE", "MESSAGE_CREATE_START (conv=${context.conversationId}, srcLang=${context.language.code})")

        try {
            val t1 = System.currentTimeMillis()
            val t0 = t1 - result.durationMs
            voiceController.reportSttLatency(t0, t1)

            val conversationId = context.conversationId
            val isRoom = conversationId == app.swarsetu.data.message.Conversations.NEARBY
            val groupInfo: GroupInfo? = if (!isRoom) {
                runCatching { groups.find(conversationId)?.toGroupInfo() }.getOrNull()
            } else null

            val sourceLanguage = context.language.code.lowercase()
            var targetLanguage: String? = null
            var translatedText: String? = null

            // Determine recipientId vs group
            val recipientId = if (isRoom || groupInfo != null) null else conversationId

            if (recipientId != null) {
                try {
                    val peer = peers.find(recipientId)
                    val rawTarget = peer?.preferredLanguage?.lowercase() ?: sourceLanguage
                    targetLanguage = translatorEngine.normalizeToLanguageTag(rawTarget) ?: rawTarget
                    val normalizedSource = translatorEngine.normalizeToLanguageTag(sourceLanguage) ?: sourceLanguage
                    
                    if (targetLanguage != normalizedSource) {
                        android.util.Log.d("SW_TRANSLATION", "TRANSLATION_START ($normalizedSource -> $targetLanguage)")
                        val protectedNouns = listOfNotNull(peer?.name)
                        val res = translatorEngine.translate(result.text, normalizedSource, targetLanguage, protectedNouns)
                        if (res.isNotBlank() && res != result.text) {
                            translatedText = res
                            android.util.Log.d("SW_TRANSLATION", "TRANSLATION_SUCCESS ($normalizedSource -> $targetLanguage)")
                        } else {
                            android.util.Log.d("SW_TRANSLATION", "TRANSLATION_SUCCESS (Identical/untranslated)")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SW_TRANSLATION", "TRANSLATION_FAILED: ${e.message}", e)
                }
            }

            val t2 = System.currentTimeMillis()

            val outboundText = translatedText ?: result.text
            val content =
                MessageContent(
                    body = outboundText,
                    replyTo = context.replyTo,
                    messageType = app.swarsetu.data.message.MessageEntity.TYPE_TRANSLATED_VOICE,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    sourceText = result.text,
                    translatedText = translatedText,
                    isAlert = if (context.isAlert) true else null,
                )
            val payloadSizeBytes = content.encode().size

            val messageId = UUID.randomUUID().toString()
            val t3 = System.currentTimeMillis()

            voiceController.reportOutboundMessageMetrics(messageId, payloadSizeBytes, t2, t3)
            android.util.Log.d("SW_MESSAGE", "MESSAGE_CREATE_SUCCESS (id=$messageId, size=$payloadSizeBytes bytes)")

            meshController.sendChat(
                text = outboundText,
                recipientId = recipientId,
                group = groupInfo,
                replyTo = context.replyTo,
                messageType = app.swarsetu.data.message.MessageEntity.TYPE_TRANSLATED_VOICE,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                sourceText = result.text,
                translatedText = translatedText,
                isAlert = context.isAlert,
                messageId = messageId,
            )
        } catch (e: Exception) {
            android.util.Log.e("SW_MESSAGE", "MESSAGE_CREATE_FAILED: ${e.message}", e)
        } finally {
            // Clear context and disable mesh after transmission attempt
            currentContext.set(null)
            voiceController.isMeshEnabled = false
        }
    }
}
