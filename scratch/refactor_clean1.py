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

cvm_pairs = [
    ("            meshManager.mintGroupRoots()\n", "")
]
replace_exact("app/src/main/java/app/swarsetu/ui/contacts/ContactsViewModel.kt", cvm_pairs)


# For ChatScreen.kt:
cs_pairs = [
    ("import app.swarsetu.data.relay.AttachmentRelay\n", ""),
    ("import app.swarsetu.data.relay.RelayReach\n", ""),
    ("    val stagedAttachmentRelay by viewModel.stagedAttachmentRelay.collectAsStateWithLifecycle()\n", ""),
    ("        stagedAttachmentRelay = stagedAttachmentRelay,\n", ""),
    ("    // Reach of the staged photo, so the composer can caption it before the send rather than after.\n    stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent,\n", ""),
    ("    var relayMarkerExplained by remember { mutableStateOf<AttachmentRelay?>(null) }\n", ""),
    ("ConnectionStatusRow(state.neighborCount, state.transportHealth, state.relayPlane)", "ConnectionStatusRow(state.neighborCount, state.transportHealth)"),
    ("                RelayNotice(reach = state.relayReach, onClick = { showRelayInfo = true })\n", ""),
    ("    var showRelayInfo by remember { mutableStateOf(false) }\n", ""),
    ("    onExplainRelay: (AttachmentRelay) -> Unit = {},\n", ""),
    ("                                    onExplainRelay = { relayMarkerExplained = it },\n", ""),
    ("    stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent,\n", ""),
    ("                        relay = stagedAttachmentRelay,\n", ""),
    ("    relay: AttachmentRelay,\n", "")
]
replace_exact("app/src/main/java/app/swarsetu/ui/chat/ChatScreen.kt", cs_pairs)

print("Done")
