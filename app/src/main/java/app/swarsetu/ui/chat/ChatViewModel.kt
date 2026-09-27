package app.swarsetu.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.swarsetu.R
import app.swarsetu.TextLimits
import app.swarsetu.data.AttachmentStore
import app.swarsetu.data.BlobRepository
import app.swarsetu.data.GallerySaver
import app.swarsetu.data.GroupRepository
import app.swarsetu.data.MessageReceiptRepository
import app.swarsetu.data.MessageRepository
import app.swarsetu.data.PeerRepository
import app.swarsetu.data.ReactionRepository
import app.swarsetu.data.VoiceAudio
import app.swarsetu.data.group.GroupEntity
import app.swarsetu.data.group.GroupMembersStore
import app.swarsetu.data.group.toGroupInfo
import app.swarsetu.data.message.Conversations
import app.swarsetu.data.message.DeliveryPlane
import app.swarsetu.data.message.MentionStore
import app.swarsetu.data.message.MessageEntity
import app.swarsetu.data.message.groupTitle
import app.swarsetu.data.message.receivedPlane
import app.swarsetu.data.message.replyRef
import app.swarsetu.data.reaction.ReactionEntity
import app.swarsetu.data.settings.SettingsStore
import app.swarsetu.identity.Identity
import app.swarsetu.identity.displayNameFor
import app.swarsetu.mesh.MeshController
import app.swarsetu.mesh.TransportHealth
import app.swarsetu.mesh.crypto.AttachmentCrypto
import app.swarsetu.mesh.crypto.b64d
import app.swarsetu.mesh.protocol.GroupInfo
import app.swarsetu.mesh.protocol.Mention
import app.swarsetu.mesh.protocol.ReplyRef
import app.swarsetu.moderation.ImageScreeningService
import app.swarsetu.notifications.Notifier
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttPipeline
import app.swarsetu.tts.TtsLanguage
import app.swarsetu.tts.TtsManager
import app.swarsetu.tts.TtsPriority
import app.swarsetu.tts.TtsRequest
import app.swarsetu.ui.voice.VoicePlayer
import app.swarsetu.ui.voice.VoiceRecorder
import app.swarsetu.voice.toTtsLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatRow(
    val id: String,
    val body: String,
    val mine: Boolean,
    val senderName: String,
    val senderNodeId: String,
    // A non-[MessageEntity.KIND_NORMAL] row is a status notice (e.g. [MessageEntity.KIND_MEMBER_LEFT]),
    // rendered as a centered line using [senderName] instead of a chat bubble.
    val kind: Int = MessageEntity.KIND_NORMAL,
    val avatarHash: String?,
    val sentAt: Long,
    val received: Boolean,
    // The plane the receipt that flipped [received] arrived on; [DeliveryPlane.Internet] paints a globe
    // beside the tick. Only meaningful on our own delivered messages — see [MessageEntity].
    val deliveredVia: DeliveryPlane = DeliveryPlane.Unknown,
    // How many of the group's other members have acked this message, out of how many there are. Both 0
    // outside a group send of ours — and [deliveredCount] is 0 for a message acked before this device
    // recorded ackers, which is what makes the tick fall back to a bare "Delivered" (see [deliveryLabel]).
    val deliveredCount: Int = 0,
    val recipientTotal: Int = 0,
    // True when the on-device text moderator flagged this message's body; the bubble collapses it
    // behind a tap-to-reveal instead of showing the text outright.
    val moderationFlagged: Boolean = false,
    val attachmentHash: String? = null,
    val attachmentMime: String? = null,
    // Base64 key for an end-to-end-encrypted attachment (null for plaintext/broadcast attachments);
    // passed to the image loader to decrypt the ciphertext blob before decoding.
    val attachmentKey: String? = null,
    // True once the attachment blob is present locally; false while it's still being pulled (the bubble
    // shows a loading placeholder). Only meaningful when [attachmentHash] is non-null.
    val attachmentReady: Boolean = false,
    // True when on-device screening flagged the attachment as explicit; the bubble blurs it behind a
    // tap-to-view. Only meaningful when [attachmentHash] is non-null.
    val attachmentFlagged: Boolean = false,

    // A voice note's playing time and waveform bars, both derived locally from the audio (never carried on
    // the wire — see [app.swarsetu.data.VoiceAudio]). Null until the blob has arrived and been
    // described, which is why the bubble can render a length-less placeholder in the meantime.
    //
    // The bars stay in their stored Base64 form here rather than a decoded FloatArray: this is a data class,
    // and an array field would give it reference-identity equality, so every re-emission of the message list
    // would recompose every voice bubble on screen. The bubble decodes once, under `remember`.
    val voiceDurationMs: Int? = null,
    val voicePeaks: String? = null,
    val mentions: List<Mention> = emptyList(),
    val reactions: List<ReactionSummary> = emptyList(),
    // The message this row quotes (Signal-style reply), or null when it isn't a reply. Denormalized so the
    // quote renders even if the quoted original isn't in this thread. See [MessageEntity.replyRef].
    val replyTo: ReplyRef? = null,
    val messageType: Int = app.swarsetu.data.message.MessageEntity.TYPE_NORMAL_TEXT,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val sourceText: String? = null,
    val translatedText: String? = null,
    val isAlert: Boolean = false,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val locationTimestamp: Long? = null,
)

/**
 * One emoji's tally on a message: the [emoji], how many people reacted with it ([count]), and whether
 * the local user is one of them ([mine], to highlight the chip). Distinct emoji become distinct chips;
 * the UI shows the count only when it exceeds 1.
 */
data class ReactionSummary(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/** A person who can be "@"-mentioned: someone we've received a message from, resolved to a name. */
data class MentionCandidate(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
)

/** A peer currently shown as "typing" in this thread, resolved to a display [name] + [avatarHash] for the
 *  animated indicator row. */
data class TypingPeer(
    val nodeId: String,
    val name: String,
    val avatarHash: String?,
)

data class ChatUiState(
    val rows: List<ChatRow> = emptyList(),
    val neighborCount: Int = 0,
    // Radio health, so the connection header can distinguish "nobody nearby" from radios off/seized.
    val transportHealth: TransportHealth = TransportHealth.Healthy,
    val myNodeId: String = "",
    val mentionCandidates: List<MentionCandidate> = emptyList(),
    // Conversation header: the room ([isRoom] true) or a 1:1 DM with [title]/[avatarHash] of the peer.
    val isRoom: Boolean = true,
    val title: String = "",
    val avatarHash: String? = null,
    // True when this DM's peer is blocked, so the header offers "Unblock" instead of "Block".
    val isBlocked: Boolean = false,
    // True when this DM's peer has been key-verified (safety number / QR), to show a verified badge.
    val verified: Boolean = false,
    // True when this thread is a group chat; [memberCount] sizes the header subtitle. The header then
    // offers "Rename group" / "Leave group" instead of Block/Unblock.
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
    // Peers currently typing in this thread, shown as an animated indicator above the input. Ephemeral
    // (TTL'd in the mesh layer) and best-effort; empty most of the time.
    val typingPeers: List<TypingPeer> = emptyList(),
    val myLocation: app.swarsetu.mesh.protocol.LocationPayload? = null,
)

class ChatViewModel(
    private val conversationId: String,
    private val messages: MessageRepository,
    private val groups: GroupRepository,
    private val peers: PeerRepository,
    private val reactions: ReactionRepository,
    private val receipts: MessageReceiptRepository,
    private val meshManager: MeshController,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val notifier: Notifier,
    private val attachments: AttachmentStore,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
    private val gallerySaver: GallerySaver,
    // App-scoped on purpose: any number of voice-note bubbles can be on screen and only one may sound, so
    // arbitration can't live in a per-screen ViewModel.
    private val voicePlayer: VoicePlayer,
    private val context: Context,
    private val ttsManager: TtsManager,
    private val voiceMessageAdapter: app.swarsetu.voice.VoiceMessageAdapter,
    private val voiceController: app.swarsetu.voice.VoiceConversationController,
    private val sttPipeline: SttPipeline,
    private val translatorEngine: app.swarsetu.translation.TranslatorEngine,
) : ViewModel() {
    private val locationProvider = app.swarsetu.location.OfflineLocationProvider(context)
    val myLocation = locationProvider.observeLocation().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val isRoom = conversationId == Conversations.NEARBY

    private val myNodeId = MutableStateFlow<String?>(null)

    private val chatForeground = MutableStateFlow(false)

    private val _pendingAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val pendingAttachment: StateFlow<AttachmentStore.Ingested?> = _pendingAttachment.asStateFlow()

    private val _confirmAttachment = MutableStateFlow<AttachmentStore.Ingested?>(null)
    val confirmAttachment: StateFlow<AttachmentStore.Ingested?> = _confirmAttachment.asStateFlow()

    data class VoiceRecording(
        val elapsedMs: Long,
        val amplitude: Float,
        val locked: Boolean,
    )

    private val _voiceRecording = MutableStateFlow<VoiceRecording?>(null)
    val voiceRecording: StateFlow<VoiceRecording?> = _voiceRecording.asStateFlow()

    val voicePlayback: StateFlow<VoicePlayer.Playback?> = voicePlayer.nowPlaying

    val sttPartialText: StateFlow<String> = sttPipeline.partialText
    val sttLatestResult: StateFlow<app.swarsetu.stt.SttResult?> = sttPipeline.latestResult

    val selectedSttLanguage: StateFlow<SttLanguage> =
        settings.sttLanguageCode
            .map { code -> SttLanguage.fromCode(code) ?: SttLanguage.ENGLISH }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SttLanguage.ENGLISH)

    // Built lazily so a chat that never records never opens a recorder, and torn down in onCleared: the
    // microphone is exclusive, and leaking it would block every other app until this process died.
    private val recorder by lazy { VoiceRecorder(context, viewModelScope) }

    // Ticks the recording UI. Cancelled by every path that ends a recording.
    private var recordingTicker: Job? = null

    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    private val _closeChat = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeChat: SharedFlow<Unit> = _closeChat.asSharedFlow()

    private val _clearInput = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearInput: SharedFlow<Unit> = _clearInput.asSharedFlow()

    private val _sttTranscript = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sttTranscript: SharedFlow<String> = _sttTranscript.asSharedFlow()

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
        viewModelScope.launch {
            combine(chatForeground, messages.observeMessages(conversationId)) { foreground, msgs ->
                if (foreground) msgs.maxOfOrNull { it.sentAt } else null
            }.distinctUntilChanged().collect { watermark ->
                if (watermark != null) settings.setLastReadAt(conversationId, watermark)
            }
        }
        viewModelScope.launch {
            sttPipeline.latestResult.collect { result ->
                if (result != null && result.isUsable && result.text.isNotBlank()) {
                    android.util.Log.d("ChatViewModel", "STT transcript produced: \"${result.text}\"")
                    _sttTranscript.tryEmit(result.text)
                }
            }
        }
    }

    private data class MessagesBundle(
        val messages: List<MessageEntity>,
        val reactions: List<ReactionEntity>,
        val blocked: Set<String>,
        val blobSizes: Map<String, Int>,
        val flaggedHashes: Set<String>,
        val hideSensitiveContent: Boolean,
        val group: GroupEntity?,
        val deliveredCounts: Map<String, Int>,
    )

    private val blobState =
        combine(
            blobs.observeSizes(),
            imageScreening.observeFlaggedHashes(),
            settings.contentFilteringEnabled,
        ) { sizes, flagged, hideSensitive ->
            Triple(sizes, flagged.toSet(), hideSensitive)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val groupDelivery: Flow<Pair<GroupEntity?, Map<String, Int>>> =
        groups
            .observeGroup(conversationId)
            .distinctUntilChanged()
            .flatMapLatest { group ->
                val roster = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
                if (roster.isEmpty()) {
                    flowOf(group to emptyMap())
                } else {
                    receipts.observeDeliveredCounts(conversationId, roster).map { group to it }
                }
            }

    private val messagesWithReactions =
        combine(
            messages.observeMessages(conversationId),
            reactions.observeReactions(),
            settings.blockedNodeIds,
            blobState,
            groupDelivery,
        ) { msgs, reacts, blocked, (sizes, flagged, hideSensitive), (group, delivered) ->
            MessagesBundle(
                msgs.filter { it.senderId !in blocked },
                reacts,
                blocked,
                sizes,
                flagged,
                hideSensitive,
                group,
                delivered,
            )
        }

    private data class MeshStatus(
        val neighborCount: Int,
        val transportHealth: TransportHealth,
        val typing: Map<String, Set<String>>,
    )

    private val meshStatus =
        combine(
            meshManager.neighborCount,
            meshManager.transportHealth,
            meshManager.typing,
        ) { count, health, typing -> MeshStatus(count, health, typing) }

    private data class UserContext(
        val nodeId: String?,
        val displayName: String,
        val location: app.swarsetu.mesh.protocol.LocationPayload?
    )

    private val userContext = combine(myNodeId, settings.displayName, myLocation) { id, name, loc ->
        UserContext(id, name, loc)
    }

    val state: StateFlow<ChatUiState> =
        combine(
            messagesWithReactions,
            peers.observePeers(),
            meshStatus,
            userContext,
        ) { bundle, peerList, mesh, ctx ->
            val me = ctx.nodeId
            val myName = ctx.displayName
            val location = ctx.location
            val count = mesh.neighborCount
            val health = mesh.transportHealth
            val typingMap = mesh.typing
            val msgs = bundle.messages
            val reacts = bundle.reactions
            val blocked = bundle.blocked
            val blobSizes = bundle.blobSizes
            val flaggedHashes = bundle.flaggedHashes
            val hideSensitive = bundle.hideSensitiveContent
            val group = bundle.group
            val deliveredCounts = bundle.deliveredCounts
            val isGroup = group != null
            val members = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
            val peersByNode = peerList.associateBy { it.nodeId }
            val reactionsByMessage = reacts.groupBy { it.messageId }
            val rows =
                msgs.map { m ->
                    val mine = m.senderId == me
                    val name =
                        when {
                            mine -> myName.ifBlank { context.getString(R.string.chat_self_name) }
                            else -> displayNameFor(peersByNode[m.senderId]?.name, m.senderId)
                        }
                    val tallies =
                        reactionsByMessage[m.id]
                            .orEmpty()
                            .groupBy { it.emoji }
                            .mapNotNull { (emoji, group) ->
                                if (emoji == null) {
                                    null
                                } else {
                                    ReactionSummary(emoji, group.size, group.any { it.reactorNodeId == me })
                                }
                            }
                    val heldBytes = m.attachmentHash?.let { blobSizes[it] }
                    ChatRow(
                        id = m.id,
                        body = m.body,
                        mine = mine,
                        senderName = name,
                        senderNodeId = m.senderId,
                        kind = m.kind,
                        avatarHash = peersByNode[m.senderId]?.avatarHash,
                        sentAt = m.sentAt,
                        received = m.received,
                        deliveredVia = m.receivedPlane,
                        deliveredCount = if (mine && isGroup) deliveredCounts[m.id] ?: 0 else 0,
                        recipientTotal = if (mine && isGroup) members.count { it != me } else 0,
                        moderationFlagged = hideSensitive && m.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                        attachmentHash = m.attachmentHash,
                        attachmentMime = m.attachmentMime,
                        attachmentKey = m.attachmentKey,
                        voiceDurationMs = m.voiceDurationMs,
                        voicePeaks = m.voicePeaks,
                        attachmentReady = heldBytes != null,
                        attachmentFlagged = hideSensitive && m.attachmentHash != null && m.attachmentHash in flaggedHashes,

                        mentions = MentionStore.decode(m.mentions),
                        reactions = tallies,
                        replyTo = m.replyRef(),
                        messageType = m.messageType,
                        sourceLanguage = m.sourceLanguage,
                        targetLanguage = m.targetLanguage,
                        sourceText = m.sourceText,
                        translatedText = m.translatedText,
                        isAlert = m.isAlert,
                        locationLatitude = m.locationLatitude,
                        locationLongitude = m.locationLongitude,
                        locationAccuracyMeters = m.locationAccuracyMeters,
                        locationTimestamp = m.locationTimestamp,
                    )
                }
            val candidates =
                (msgs.map { it.senderId } + members)
                    .asSequence()
                    .filter { it != me }
                    .distinct()
                    .map { id ->
                        MentionCandidate(
                            nodeId = id,
                            displayName = displayNameFor(peersByNode[id]?.name, id),
                            avatarHash = peersByNode[id]?.avatarHash,
                        )
                    }.sortedBy { it.displayName.lowercase() }
                    .toList()
            val typingPeers =
                typingMap[conversationId]
                    .orEmpty()
                    .asSequence()
                    .filter { it != me && it !in blocked }
                    .map { id -> TypingPeer(id, displayNameFor(peersByNode[id]?.name, id), peersByNode[id]?.avatarHash) }
                    .sortedBy { it.name.lowercase() }
                    .toList()
            ChatUiState(
                rows = rows,
                neighborCount = count,
                transportHealth = health,
                myNodeId = me.orEmpty(),
                mentionCandidates = candidates,
                isRoom = isRoom,
                title =
                    when {
                        group != null -> {
                            groupTitle(
                                storedName = group.name,
                                memberIds = members,
                                selfId = me,
                                fallback = context.getString(R.string.group_unnamed),
                            ) { id -> displayNameFor(peersByNode[id]?.name, id) }
                        }

                        isRoom -> {
                            context.getString(R.string.nearby_title)
                        }

                        else -> {
                            displayNameFor(peersByNode[conversationId]?.name, conversationId)
                        }
                    },
                avatarHash =
                    when {
                        isRoom -> null
                        else -> group?.photoHash ?: peersByNode[conversationId]?.avatarHash
                    },
                isBlocked = !isRoom && !isGroup && conversationId in blocked,
                verified = !isRoom && !isGroup && peersByNode[conversationId]?.verified == true,
                isGroup = isGroup,
                memberCount = members.size,
                typingPeers = typingPeers,
                myLocation = location,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isRoom = isRoom))

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    fun send(
        text: String,
        mentions: List<Mention> = emptyList(),
        replyTo: ReplyRef? = null,
    ) {
        val trimmed = text.trim().take(TextLimits.MESSAGE)
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch {
            try {
                val outgoingReply = normalizeSelfAuthor(replyTo)
                val group = if (isRoom) null else groups.find(conversationId)

                val detectedScript = translatorEngine.detectLanguageFromScript(trimmed)
                val myLang = (detectedScript ?: selectedSttLanguage.value.code).lowercase()
                var sourceLang: String? = myLang
                var targetLang: String? = null
                var translatedText: String? = null

                if (!isRoom && group == null) {
                    val peer = peers.find(conversationId)
                    val peerLang = peer?.preferredLanguage?.lowercase()
                    if (peerLang != null) {
                        val normMy = translatorEngine.normalizeToLanguageTag(myLang) ?: myLang
                        val normPeer = translatorEngine.normalizeToLanguageTag(peerLang) ?: peerLang
                        if (normMy != normPeer && trimmed.isNotBlank()) {
                            targetLang = normPeer
                            sourceLang = normMy
                            val protectedNouns = listOfNotNull(peer.name, settings.displayName.first())
                            val res = translatorEngine.translate(trimmed, normMy, normPeer, protectedNouns)
                            if (res.isNotBlank() && res != trimmed) {
                                translatedText = res
                            }
                        }
                    }
                }

                val sent =
                    if (group != null) {
                        meshManager.sendChat(
                            text = trimmed,
                            attachment = attachment,
                            mentions = mentions,
                            recipientId = null,
                            group = group.toGroupInfo(),
                            replyTo = outgoingReply,
                            messageType = MessageEntity.TYPE_NORMAL_TEXT,
                            sourceLanguage = sourceLang,
                            targetLanguage = targetLang,
                            sourceText = trimmed,
                            translatedText = translatedText,
                        )
                    } else {
                        val recipientId = if (isRoom) null else conversationId
                        meshManager.sendChat(
                            text = trimmed,
                            attachment = attachment,
                            mentions = mentions,
                            recipientId = recipientId,
                            replyTo = outgoingReply,
                            messageType = MessageEntity.TYPE_NORMAL_TEXT,
                            sourceLanguage = sourceLang,
                            targetLanguage = targetLang,
                            sourceText = trimmed,
                            translatedText = translatedText,
                        )
                    }
                if (sent) {
                    _pendingAttachment.value = null
                    _clearInput.tryEmit(Unit)
                } else {
                    _isSending.value = false
                    _events.tryEmit(R.string.moderation_text_blocked)
                }
            } catch (e: Exception) {
                _isSending.value = false
            }
        }
    }

    fun replayTts(
        text: String,
        languageCode: String?,
    ) {
        if (text.isBlank()) return
        val ttsLang = TtsLanguage.fromLanguageCode(languageCode, text) ?: TtsLanguage.HINDI
        viewModelScope.launch {
            ttsManager.speak(
                TtsRequest(
                    requestId =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    text = text,
                    language = ttsLang,
                    priority = TtsPriority.ALERT,
                ),
            )
        }
    }

    private suspend fun normalizeSelfAuthor(replyTo: ReplyRef?): ReplyRef? {
        val me = identity.nodeId()
        return replyTo
            ?.takeIf { it.authorId == me }
            ?.copy(author = displayNameFor(settings.displayName.first(), me))
            ?: replyTo
    }

    fun getCooldownRemainingSeconds(): Int {
        val elapsed = System.currentTimeMillis() - globalLastAlertSentTimeMs
        val remaining = 30 - (elapsed / 1000).toInt()
        return if (remaining > 0) remaining else 0
    }

    fun sendAlert(type: app.swarsetu.sos.SosType = app.swarsetu.sos.SosType.GENERAL) {
        if (_isSending.value || getCooldownRemainingSeconds() > 0) return
        _isSending.value = true
        globalLastAlertSentTimeMs = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val loc = locationProvider.getFreshOrCachedLocation(5000L)
                val senderLang = selectedSttLanguage.value
                val senderLocalizedText = app.swarsetu.ui.i18n.AppStrings.get(type.messageKey, senderLang)
                val wireEnglishText = app.swarsetu.ui.i18n.AppStrings.get(type.messageKey, app.swarsetu.stt.SttLanguage.ENGLISH)
                val wireBody = app.swarsetu.sos.SosType.formatWireText(type, wireEnglishText)

                val sent =
                    meshManager.sendChat(
                        text = wireBody,
                        recipientId = null,
                        isAlert = true,
                        messageType = app.swarsetu.data.message.MessageEntity.TYPE_TRANSLATED_VOICE,
                        sourceLanguage = senderLang.toTtsLanguage()?.name,
                        sourceText = senderLocalizedText,
                        translatedText = senderLocalizedText,
                        location = loc,
                    )
                if (!sent) {
                    android.util.Log.w("ChatViewModel", "ALERT_BROADCAST_FAILED")
                } else {
                    android.util.Log.d("ChatViewModel", "ALERT_BROADCAST_SENT (type=${type.code})")
                }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun onInputCleared() {
        _isSending.value = false
    }

    fun react(
        messageId: String,
        emoji: String,
    ) {
        viewModelScope.launch {
            val group = if (isRoom) null else groups.find(conversationId)?.toGroupInfo()
            val recipientId = if (isRoom || group != null) null else conversationId
            meshManager.sendReaction(messageId, emoji, recipientId, group)
        }
    }

    fun deleteMessage(messageId: String) {
        val hash =
            state.value.rows
                .firstOrNull { it.id == messageId }
                ?.attachmentHash
        viewModelScope.launch {
            messages.delete(messageId)
            reactions.deleteForMessage(messageId)
            receipts.deleteForMessage(messageId)
            blobs.deleteIfUnreferenced(hash)
            _events.tryEmit(R.string.chat_message_deleted)
        }
    }

    fun block(nodeId: String) {
        viewModelScope.launch {
            settings.block(nodeId, peers.find(nodeId)?.deviceTag)
            _events.tryEmit(R.string.chat_user_blocked)
            if (!isRoom) _closeChat.tryEmit(Unit)
        }
    }

    fun unblock(nodeId: String) {
        viewModelScope.launch {
            settings.unblock(nodeId, peers.find(nodeId)?.deviceTag)
            _events.tryEmit(R.string.chat_user_unblocked)
        }
    }

    fun attach(uri: Uri) {
        viewModelScope.launch { stage(attachments.ingest(uri), notifyFailure = false) }
    }

    fun attachCaptured(jpeg: ByteArray) {
        viewModelScope.launch { stage(attachments.ingest(jpeg, "image/jpeg"), notifyFailure = true) }
    }

    private suspend fun stage(
        result: AttachmentStore.IngestResult,
        notifyFailure: Boolean,
    ) {
        when (result) {
            is AttachmentStore.IngestResult.Success -> {
                when {
                    !result.flagged -> {
                        _pendingAttachment.value = result.ingested
                    }

                    isRoom -> {
                        blobs.deleteIfUnreferenced(result.ingested.hash)
                        _events.tryEmit(R.string.moderation_image_blocked)
                    }

                    else -> {
                        _confirmAttachment.value = result.ingested
                    }
                }
            }

            AttachmentStore.IngestResult.Failed -> {
                if (notifyFailure) _events.tryEmit(R.string.chat_image_capture_failed)
            }
        }
    }

    fun startVoiceRecording(locked: Boolean = false): Boolean {
        android.util.Log.d("SW_VOICE", "VOICE_BUTTON_PRESSED")
        android.util.Log.d("SW_VOICE", "VOICE_START_REQUESTED")
        if (_voiceRecording.value != null || sttPipeline.state.value != app.swarsetu.stt.SttPipeline.PipelineState.IDLE) {
            android.util.Log.w("SW_VOICE", "VOICE_START_REJECTED: Pipeline not IDLE")
            return false
        }
        val language = selectedSttLanguage.value

        if (sttPipeline.canCapture) {
            android.util.Log.d("SW_VOICE", "MIC_PERMISSION_GRANTED")
            android.util.Log.d("SW_VOICE", "VOICE_START_ACCEPTED (STT lang=${language.code})")
            sttPipeline.startCapture(language, silenceTimeoutMs = 0L)
        } else {
            android.util.Log.w("SW_VOICE", "MIC_PERMISSION_DENIED")
            _events.tryEmit(R.string.chat_voice_record_failed)
            return false
        }

        _voiceRecording.value = VoiceRecording(elapsedMs = 0L, amplitude = 0f, locked = locked)
        recordingTicker?.cancel()
        recordingTicker =
            viewModelScope.launch {
                val startTime = System.currentTimeMillis()
                while (true) {
                    delay(VOICE_TICK_MS)
                    val elapsed = System.currentTimeMillis() - startTime
                    val amp = sttPipeline.amplitude.value
                    if (elapsed >= 600_000L) {
                        stopVoiceRecordingAndStage()
                        return@launch
                    }
                    _voiceRecording.value =
                        _voiceRecording.value?.copy(elapsedMs = elapsed, amplitude = amp)
                }
            }
        return true
    }

    fun lockVoiceRecording() {
        _voiceRecording.value = _voiceRecording.value?.copy(locked = true)
    }

    fun stopVoiceRecordingAndStage() {
        android.util.Log.d("SW_VOICE", "VOICE_SESSION_STOPPED (Stop requested)")
        if (_voiceRecording.value == null) return
        recordingTicker?.cancel()
        recordingTicker = null

        sttPipeline.stopCapture()

        _voiceRecording.value = null
    }

    fun cancelVoiceRecording() {
        android.util.Log.d("SW_VOICE", "VOICE_SESSION_STOPPED (Cancelled)")
        if (_voiceRecording.value == null) return
        recordingTicker?.cancel()
        recordingTicker = null
        sttPipeline.cancelCapture()
        voicePlayer.stop()

        _voiceRecording.value = null
    }

    fun playVoice(
        hash: String,
        key: String?,
    ) = voicePlayer.play(hash, key)

    /** Scrubs the loaded voice note to [positionMs]; ignored unless [hash] is the note that is loaded. */
    fun seekVoice(
        hash: String,
        positionMs: Int,
    ) = voicePlayer.seek(hash, positionMs)

    /** The user confirmed the explicit-image warning: stage the (already-ingested) image for sending. */
    fun confirmFlaggedAttachment() {
        _pendingAttachment.value = _confirmAttachment.value ?: return
        _confirmAttachment.value = null
    }

    /** The user declined the explicit-image warning: drop it and GC the ingested-but-unsent blob. */
    fun dismissFlaggedAttachment() {
        val pending = _confirmAttachment.value ?: return
        _confirmAttachment.value = null
        viewModelScope.launch { blobs.deleteIfUnreferenced(pending.hash) }
    }

    /**
     * Discards the staged attachment; its blob (ingested on pick or on finishing a recording) is GC'd unless
     * a sent message references it. A staged voice note's description rides on the attachment itself, so it
     * goes with it — nothing separate to clear.
     */
    fun clearAttachment() {
        val pending = _pendingAttachment.value ?: return
        _pendingAttachment.value = null
        viewModelScope.launch { blobs.deleteIfUnreferenced(pending.hash) }
    }

    /**
     * Exports the attachment blob [hash] to the public `Pictures/Knit` folder and toasts the result.
     *
     * [key] and [mime] come from the message row the user tapped, which is exactly what
     * [app.swarsetu.ui.image.BlobFetcher] takes to render that bubble — so what gets saved is what is
     * on screen, by construction. Both matter:
     *
     * - A DM/group attachment's stored blob is `iv || ciphertext` ([AttachmentCrypto]), content-addressed
     *   by the *ciphertext* hash, so it has to be opened before it leaves the app. Without [key] this
     *   wrote 300 KB of ciphertext into the gallery under an image mime and reported success.
     * - `blobs.mime` describes those stored (ciphertext) bytes and is only ever as good as whatever named
     *   the blob when it landed — since ADR 035 a fetcher default on the spool path rather than the frame.
     *   The row's mime is the plaintext's own type; the blob row is just the fallback.
     */
    fun saveAttachment(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        viewModelScope.launch {
            val raw = blobs.bytes(hash)
            val bytes = if (key != null && raw != null) AttachmentCrypto.open(raw, b64d(key)) else raw
            val type = mime ?: blobs.mimeFor(hash)
            val ok = bytes != null && type != null && gallerySaver.saveToPictures(bytes, hash, type)
            _events.tryEmit(if (ok) R.string.chat_image_saved else R.string.chat_image_save_failed)
        }
    }

    /** A message's text was copied to the clipboard; surface the confirmation toast. */
    fun onMessageCopied() {
        _events.tryEmit(R.string.chat_message_copied)
    }

    /** Chat is on screen: suppress this conversation's notifications and clear any active one (the user is reading). */
    fun onChatForeground() {
        chatForeground.value = true
        notifier.setVisibleConversation(conversationId)
    }

    /** Chat left the screen: resume notifying for this conversation's incoming messages. */
    fun onChatBackground() {
        chatForeground.value = false
        notifier.setVisibleConversation(null)
    }

    // Wall clock of the last typing cue we sent, so we throttle to at most one per TYPING_SEND_INTERVAL_MS
    // while the user edits (see onUserTyping). Main-thread-confined (the screen's snapshotFlow collector).
    private var lastTypingSentAt = 0L

    /**
     * The user changed the (non-empty) draft: emit a best-effort "now typing" cue, throttled to at most one per
     * [TYPING_SEND_INTERVAL_MS] and only while the chat is foregrounded. Fires immediately on the first keystroke
     * after an idle gap (the throttle window has elapsed), so the indicator appears promptly on the other side.
     * Cheap and fire-and-forget — the screen may call this on every keystroke.
     */
    fun onUserTyping() {
        val now = System.currentTimeMillis()
        if (!chatForeground.value || now - lastTypingSentAt < TYPING_SEND_INTERVAL_MS) return
        lastTypingSentAt = now
        viewModelScope.launch { meshManager.sendTyping(conversationId) }
    }

    /**
     * Releases the microphone and silences playback when the chat goes away. The recorder holds an exclusive
     * system resource that no other app can take back, so an abandoned recording must not outlive the screen
     * that started it; playback stops because a voice note continuing to sound from a thread the user has
     * navigated away from reads as a bug, not a feature.
     */
    override fun onCleared() {
        recordingTicker?.cancel()
        sttPipeline.cancelCapture()
        voicePlayer.stop()
        voiceMessageAdapter.stopVoiceMessage()
        voiceController.isMeshEnabled = false
        super.onCleared()
    }

    companion object {
        @Volatile
        private var globalLastAlertSentTimeMs: Long = 0L

        /** Send a typing cue at most this often while actively editing (< the receiver's ~12 s hold, so a peer
         *  who keeps typing re-cues before their indicator would expire). */
        const val TYPING_SEND_INTERVAL_MS = 8_000L

        /** Recording UI refresh — fast enough for a level meter to look live, slow enough to stay cheap. */
        const val VOICE_TICK_MS = 60L

        /**
         * Shortest voice note worth staging. Below this it is a fumbled press rather than speech, and
         * discarding it silently beats leaving an unsendable blip in the composer.
         */
        const val MIN_VOICE_MS = 700
    }
}
