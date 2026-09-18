import sys

def replace_exact(filename, pairs):
    with open(filename, "r", encoding="utf-8") as f:
        content = f.read()
    
    for old, new in pairs:
        if old not in content:
            print(f"WARNING: Could not find snippet in {filename}:\n{old[:100]}")
        content = content.replace(old, new)
        
    with open(filename, "w", encoding="utf-8") as f:
        f.write(content)

cs_pairs = [
    ("""    if (showRelayInfo) {
        val isRoom = state.relayReach == RelayReach.Room
        AlertDialog(
            onDismissRequest = { showRelayInfo = false },
            icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            title = {
                Text(stringResource(if (isRoom) R.string.chat_relay_room_title else R.string.chat_relay_pending_title))
            },
            text = {
                Text(stringResource(if (isRoom) R.string.chat_relay_room_body else R.string.chat_relay_pending_body))
            },
            confirmButton = {
                TextButton(onClick = { showRelayInfo = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

""", ""),
    ("""    relayMarkerExplained?.let { cause ->
        // The peer's name makes the fallback concrete ("arrives when you and Ana are in range") rather
        // than abstract; in the room and in groups the title is already the collective noun.
        val other = state.title
        AlertDialog(
            onDismissRequest = { relayMarkerExplained = null },
            icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            title = {
                Text(
                    stringResource(
                        if (cause == AttachmentRelay.Unsupported) {
                            R.string.chat_relay_unsupported_title
                        } else {
                            R.string.chat_relay_too_large_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (cause == AttachmentRelay.Unsupported) {
                            R.string.chat_relay_unsupported_body
                        } else {
                            R.string.chat_relay_too_large_body
                        },
                        other,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { relayMarkerExplained = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }""", ""),
    ("""/**
 * The standing statement about a thread's Internet reach, pinned under the header.
 *
 * Renders nothing for [RelayReach.Silent] and [RelayReach.Covered] — coverage is the happy path, and a
 * relay outage is transient and heals itself, so neither earns a permanent line of chrome. Tinted
 * `surfaceVariant` rather than `errorContainer` on purpose: nothing here has failed. The message is
 * delivered by radio exactly as it always was; this only says the Internet shortcut is not available.
 */
@Composable
private fun RelayNotice(
    reach: RelayReach,
    onClick: () -> Unit,
) {
    val label =
        when (reach) {
            RelayReach.Room -> R.string.chat_relay_room
            RelayReach.Pending -> R.string.chat_relay_pending
            RelayReach.Silent, RelayReach.Covered -> return
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("chat_relay_notice"),
    ) {
        Row(
            modifier =
                Modifier
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = 4.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        }
    }
}
""", ""),
    ("""                        // Reach, not delivery — kept on its own line above the tick so the two facts stay
                        // visually separate. This says the photo will not take the Internet shortcut; the
                        // tick below still reports whether the message itself got there.
                        if (row.attachmentRelay == AttachmentRelay.TooLarge ||
                            row.attachmentRelay == AttachmentRelay.Unsupported
                        ) {
                            NearbyOnlyMarker(onClick = { onExplainRelay(row.attachmentRelay) })
                        }
""", ""),
    ("""/**
 * The in-bubble "nearby only" marker: this attachment will not cross the Internet plane.
 *
 * Deliberately unalarming — `onSurfaceVariant`, not `error`. Nothing has gone wrong: the message is
 * being delivered by radio exactly as it would be with relays switched off. Only *permanent* causes
 * reach this marker (too large for every relay, or relays that carry no photos at all), so it never
 * appears and then quietly disappears once a retry succeeds; see `attachmentReach`.
 */
@Composable
private fun ColumnScope.NearbyOnlyMarker(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 2.dp, vertical = 1.dp)
                .testTag("chat_relay_marker"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = stringResource(R.string.chat_relay_nearby_only_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.chat_relay_nearby_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
""", ""),
    ("""    val caption =
        when (relay) {
            AttachmentRelay.TooLarge -> R.string.chat_relay_staged_too_large
            AttachmentRelay.Unsupported -> R.string.chat_relay_staged_unsupported
            AttachmentRelay.Silent, AttachmentRelay.Relayable -> null
        }""", "    val caption: Int? = null"),
    ("""@Composable
private fun stagedRelayMessage(relay: AttachmentRelay): String? =
    when (relay) {
        AttachmentRelay.TooLarge -> stringResource(R.string.chat_relay_staged_too_large)
        AttachmentRelay.Unsupported -> stringResource(R.string.chat_relay_staged_unsupported)
        AttachmentRelay.Silent, AttachmentRelay.Relayable -> null
    }
""", "")
]
replace_exact("app/src/main/java/app/swarsetu/ui/chat/ChatScreen.kt", cs_pairs)

mm_pairs = [
    ("import app.swarsetu.mesh.spool.GroupRootPolicy\n", ""),
    ("import app.swarsetu.mesh.spool.GroupScopeRoots\n", ""),
    ("import app.swarsetu.mesh.spool.ScopeBlobs\n", ""),
    ("import app.swarsetu.mesh.spool.ScopeRegistry\n", ""),
    ("import app.swarsetu.mesh.spool.ScopeRoots\n", ""),
    ("import app.swarsetu.mesh.spool.ScopeSync\n", ""),
    ("import app.swarsetu.mesh.spool.SpoolDialer\n", ""),
    ("import app.swarsetu.mesh.spool.SpoolStatus\n", ""),
    ("    // The spool plane's shared group roots (docs/SPOOL_PROTOCOL.md §3.2). NOT gated on the Internet plane\n    // being wired: a device with it switched off still adopts and re-gossips roots, which is what carries\n    // one across a plane-off member sitting between two plane-on ones. Only minting checks the switch.\n    private val groupRoots: GroupRootStore,\n", ""),
    ("    // Opens WebSocket sessions to spools for [scopeSync]. Null (the default) means the app is built\n    // without the Internet plane at all — which is what every unit test wants, and what keeps the mesh\n    // seam free of any transitive knowledge of it.\n    private val spoolDialer: SpoolDialer? = null,\n", ""),
    ("""    /**
     * Re-publishes an attachment (e.g. one we authored, or an identity avatar) to spools (spec
     * §9.5's push-half deferral: an attachment we authored and whose recipient acked stays off the
     * Internet while that peer is still on the presence plane. Wired unconditionally (it is pure and
     * cheap) but only ever consulted by [scopeSync].
     */
    private val attachmentDefer =
        AttachmentDeferPolicy(
            reachable = { transport.reachable.value.mapTo(mutableSetOf()) { peer -> peer.nodeId } },
            ackedBySender = { aHash -> messages.attachmentAcked(aHash, identity.nodeId()) },
        )

""", ""),
    ("""    /**
     * Spool sync logic (spec §3.1), exchanging keys/blobs with relays in the background. Wired when
     * [spoolDialer] is non-null. Runs inside [sessionScope] so stopping the mesh tears it down; its
     * frames come from the same custody store the radios re-serve from.
     */
    private val scopeSync: ScopeSync? =
        spoolDialer?.let { dialer ->
            ScopeSync(
                registry =
                    ScopeRegistry(
                        selfId = { identity.nodeId() },
                        groups = ::groupScopeRoots,
                    ),
                spoolDialer = dialer,
                forwardSync = forwardSync,
                keyExchange = keyExchange,
                blobExchange = blobExchange,
                blobs = ::scopeBlobs,
                defer = attachmentDefer,
                clock = clock,
            )
        }

""", ""),
    ("        scopeSync?.start(session)\n", ""),
    ("        scopeSync?.stop()\n", ""),
    ("    override fun spoolStatus(): List<SpoolStatus> = scopeSync?.status().orEmpty()\n", ""),
    ("            groupRoots.sweep(clock()) // drop rotated-away group roots past their drain window\n", ""),
    ("            mintGroupRootsIfDue() // mint a group's spool root when it is our turn (spec §3.2)\n", ""),
    ("            groupRoots.sweep(clock())\n", ""),
    ("            mintGroupRootsIfDue()\n", ""),
    ("""    override fun mintGroupRoots() {
        sessionScope?.launch { mintGroupRootsIfDue() }
    }

""", ""),
    ("""    /**
     * The mint pass, on the 15-min heal heartbeat and on the [mintGroupRoots] nudge: for every group we
     * hold, become eligible (plane on, fully ratchet-capable — spec §3.3), stamp the grace clock once,
     * and mint when [GroupRootPolicy.mintDue] says it is our turn. One rule covers both mints: version 1
     * when we hold no root, `version + 1` when a processed departure left a re-mint owed.
     *
     * The eligibility stamp is written **before** the first decision on purpose — the grace is measured
     * from it, so a device that never stamps never waits and never mints.
     */
    private suspend fun mintGroupRootsIfDue() {
        if (!settings.spoolEnabled.first()) return
        val me = identity.nodeId()
        val now = clock()
        groups.active().forEach { group ->
            val members = GroupMembersStore.decode(group.members)
            if (me !in members) return@forEach
            groupRoots.markEligible(group.groupId, now)
            val state = groupRoots.find(group.groupId)
            val version =
                GroupRootPolicy.mintDue(
                    state = state,
                    selfId = me,
                    preferredMinter = GroupRootPolicy.preferredMinter(group.createdBy, members),
                    now = now,
                ) ?: return@forEach
            groupRoots.upsert(
                GroupRootPolicy.rotated(state, group.groupId, GroupRootPolicy.newRoot(), version, me, now),
            )
            metrics.onGroupRootMinted()
            gossipGroupRoot(group.groupId)
        }
    }

""", ""),
    ("""    /**
     * Evaluates a gossip [gk] root against our held one (docs/SPOOL_PROTOCOL.md §3.2 Group Root Gossip).
     *
     * The sender gates mirror the seed path exactly (group held, not left, sender in the effective
     * roster); [GroupRootPolicy.adoptable] adds the spec's two insider-DoS bounds — the minter must be a
     * founding-roster member, and the version must stay inside the ceiling and jump bound.
     */
    private suspend fun adoptGroupRoot(
        senderId: String,
        gk: GroupKeyPayload,
        now: Long,
    ): Boolean {
        val gr = gk.gr ?: return false
        val group = groups.find(gk.groupId) ?: return false
        if (group.left || senderId !in GroupMembersStore.decode(group.members)) return false
        val held = groupRoots.find(gk.groupId)
        if (!GroupRootPolicy.adoptable(gr, foundingRoster(group), held)) return false
        groupRoots.upsert(GroupRootPolicy.rotated(held, gk.groupId, gr.root, gr.version, gr.minter, now))
        metrics.onGroupRootAdopted()
        return true
    }

""", ""),
    ("""    /**
     * The post-commit half of every inbound `CTL_GROUP_KEY`, in both directions of the root exchange.
     *
     * **Adopted**: pass the newer root on — the gossip that makes a lineage collapse across the whole
     * roster rather than only the members its minter could reach — and wake the Internet plane so the
     * rotated scope subscribes now instead of at the next reconcile tick.
     *
     * **Rejected**: if our own root is strictly newer, seed it back immediately (in-band healing). Our
     * gossips floored senderId's last update to NOW, but that didn't stop THEM sending their stale root
     * to US at the exact same time (a gossip cross), nor did it stop a third peer arriving and gossiping
     * the stale root to us (which we reject but must now counter-seed).
     */
    private suspend fun onGroupKeyReceived(
        senderId: String,
        gk: GroupKeyPayload,
        now: Long,
    ) {
        val adopted = adoptGroupRoot(senderId, gk, now)
        metrics.onGroupKeyReceived()
        if (adopted) {
            scopeSync?.onCustodyChanged()
            gossipGroupRoot(gk.groupId, skip = senderId)
            return
        }
        val held = groupRoots.find(gk.groupId)?.takeIf { it.root != null } ?: return
        val theirs = gk.gr
        // They can't be holding anything NEWER — that would have been adopted above — so "not exactly
        // ours" means "behind ours".
        if (theirs != null && theirs.version == held.version && theirs.minter == held.minter) return
        if (groups.find(gk.groupId)?.left != false) return
        if (!seedSendFloorOpen(gk.groupId, senderId)) return
        val env =
            sealEnvelopeFor(
                msgId = null,
                me = identity.nodeId(),
                now = now,
                recipientId = senderId,
                group = gk.groupId,
                content = null,
            ) ?: return
        Log.d(TAG, "counter-seeding newer group root ${gk.groupId} to $senderId")
        originateSigned(env)
    }

""", ""),
    ("        scopeSync?.onCustodyChanged()\n", ""),
    ("""    /**
     * Bridge between the blob repository's bytes and the spool sync's scope demands. We implement
     * `save` directly; nothing outside this file constructs one.
     */
    internal fun scopeBlobs(): ScopeBlobs =
        object : ScopeBlobs {
            override suspend fun has(aHash: String): Boolean = blobs.exists(aHash)

            override suspend fun bytes(aHash: String): ByteArray? = blobs.bytes(aHash)

            // [mime] is only the fetcher's hint, and since ADR 035 a sealed frame no longer carries one — so
            // prefer what our own decrypted row says this hash is, and fall back to the hint (an old peer's
            // cleartext mime, or ScopeSync's image/jpeg default) only when no row names it.
            override suspend fun save(
                aHash: String,
                mime: String,
                bytes: ByteArray,
            ) {
                blobs.save(
                    aHash,
                    messages.attachmentHint(aHash) ?: mime,
                    bytes,
                    encrypted = false,
                )
            }
        }

""", ""),
    ("""    /**
     * Produces [GroupScopeRoots] for the Internet relay plane's subscriptions. Evaluates the active
     * roster once (a heavy group-members decode) and bridges both the live root the spool rules want
     * the frame-set rule vets senders against and the rotated-away lineage while it drains.
     */
    private suspend fun groupScopeRoots(): List<GroupScopeRoots> {
        val states = groupRoots.all().filter { it.root != null }.associateBy { it.groupId }
        if (states.isEmpty()) return emptyList()
        return groups.active().mapNotNull { group ->
            val state = states[group.groupId] ?: return@mapNotNull null
            GroupScopeRoots(
                groupId = group.groupId,
                roster = foundingRoster(group),
                root = checkNotNull(state.root),
                rootVersion = state.version,
                prevRoot = state.prevRoot,
                prevRootVersion = state.prevVersion,
                prevRootExpiresAt = state.prevExpiresAt,
            )
        }
    }

""", ""),
]
replace_exact("app/src/main/java/app/swarsetu/mesh/MeshManager.kt", mm_pairs)

print("Done")
