import sys

file_path = "app/src/main/java/app/swarsetu/ui/chat/ChatScreen.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# I will replace exactly what is needed.

# 1. Imports
content = content.replace("import app.swarsetu.data.relay.AttachmentRelay\n", "")
content = content.replace("import app.swarsetu.data.relay.RelayReach\n", "")

# 2. ViewModel state
content = content.replace("    val stagedAttachmentRelay by viewModel.stagedAttachmentRelay.collectAsStateWithLifecycle()\n", "")

# 3. ChatScreenContent call
content = content.replace("        stagedAttachmentRelay = stagedAttachmentRelay,\n", "")

# 4. ChatScreenContent params
content = content.replace("    // Reach of the staged photo, so the composer can caption it before the send rather than after.\n    stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent,\n", "")

# 5. relayMarkerExplained
content = content.replace("    var relayMarkerExplained by remember { mutableStateOf<AttachmentRelay?>(null) }\n", "")

# 6. ConnectionStatusRow
content = content.replace("ConnectionStatusRow(state.neighborCount, state.transportHealth, state.relayPlane)", "ConnectionStatusRow(state.neighborCount, state.transportHealth)")

# 7. RelayNotice & dialog show
content = content.replace("                RelayNotice(reach = state.relayReach, onClick = { showRelayInfo = true })\n", "")
content = content.replace("    var showRelayInfo by remember { mutableStateOf(false) }\n", "")

# We need to remove the showRelayInfo dialog. It looks like:
show_relay_dialog = """    if (showRelayInfo) {
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

"""
content = content.replace(show_relay_dialog, "")

relay_marker_dialog = """    relayMarkerExplained?.let { cause ->
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
    }
"""
content = content.replace(relay_marker_dialog, "")

relay_notice = """/**
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
"""
content = content.replace(relay_notice, "")

content = content.replace("    onExplainRelay: (AttachmentRelay) -> Unit = {},\n", "")
content = content.replace("                                    onExplainRelay = { relayMarkerExplained = it },\n", "")

nearby_only_logic = """                        // Reach, not delivery — kept on its own line above the tick so the two facts stay
                        // visually separate. This says the photo will not take the Internet shortcut; the
                        // tick below still reports whether the message itself got there.
                        if (row.attachmentRelay == AttachmentRelay.TooLarge ||
                            row.attachmentRelay == AttachmentRelay.Unsupported
                        ) {
                            NearbyOnlyMarker(onClick = { onExplainRelay(row.attachmentRelay) })
                        }"""
content = content.replace(nearby_only_logic, "")

nearby_only_marker = """/**
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
"""
content = content.replace(nearby_only_marker, "")

content = content.replace("    stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent,\n", "")
content = content.replace("                        relay = stagedAttachmentRelay,\n", "")
content = content.replace("    relay: AttachmentRelay,\n", "")

staged_caption = """    val caption =
        when (relay) {
            AttachmentRelay.TooLarge -> R.string.chat_relay_staged_too_large
            AttachmentRelay.Unsupported -> R.string.chat_relay_staged_unsupported
            AttachmentRelay.Silent, AttachmentRelay.Relayable -> null
        }"""
content = content.replace(staged_caption, "    val caption: Int? = null")

staged_relay_msg = """@Composable
private fun stagedRelayMessage(relay: AttachmentRelay): String? =
    when (relay) {
        AttachmentRelay.TooLarge -> stringResource(R.string.chat_relay_staged_too_large)
        AttachmentRelay.Unsupported -> stringResource(R.string.chat_relay_staged_unsupported)
        AttachmentRelay.Silent, AttachmentRelay.Relayable -> null
    }
"""
content = content.replace(staged_relay_msg, "")

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Done with python script")
