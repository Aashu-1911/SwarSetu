import sys
import re

file_path = "app/src/main/java/app/swarsetu/ui/chat/ChatScreen.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Remove imports
content = re.sub(r'import app\.swarsetu\.data\.relay\.AttachmentRelay\n', '', content)
content = re.sub(r'import app\.swarsetu\.data\.relay\.RelayReach\n', '', content)

# 2. Remove stagedAttachmentRelay by viewModel.stagedAttachmentRelay.collectAsStateWithLifecycle()
content = re.sub(r'\s*val stagedAttachmentRelay by viewModel\.stagedAttachmentRelay\.collectAsStateWithLifecycle\(\)', '', content)

# 3. Remove stagedAttachmentRelay = stagedAttachmentRelay, from ChatScreenContent call
content = re.sub(r'\s*stagedAttachmentRelay = stagedAttachmentRelay,', '', content)

# 4. Remove stagedAttachmentRelay: AttachmentRelay = AttachmentRelay.Silent, from ChatScreenContent signature
content = re.sub(r'\s*// Reach of the staged photo.*?stagedAttachmentRelay: AttachmentRelay = AttachmentRelay\.Silent,', '', content, flags=re.DOTALL)

# 5. Remove relayMarkerExplained state
content = re.sub(r'\s*var relayMarkerExplained by remember \{ mutableStateOf<AttachmentRelay\?>\(null\) \}', '', content)

# 6. ConnectionStatusRow
content = re.sub(r'ConnectionStatusRow\(state\.neighborCount, state\.transportHealth, state\.relayPlane\)', 'ConnectionStatusRow(state.neighborCount, state.transportHealth)', content)

# 7. Remove RelayNotice invocation
content = re.sub(r'\s*RelayNotice\(reach = state\.relayReach, onClick = \{ showRelayInfo = true \}\)', '', content)

# 8. Remove showRelayInfo and relayMarkerExplained dialogs (lines 867-923 approx)
# It starts from `if (showRelayInfo) {` to the end of `relayMarkerExplained?.let { cause -> ... }`
# Let's match from `if (showRelayInfo) {` until `    }` that ends `relayMarkerExplained?.let`
content = re.sub(r'\s*if \(showRelayInfo\) \{.*?\s*\}\s*relayMarkerExplained\?\.let \{ cause ->.*?\s*\}', '', content, flags=re.DOTALL)
content = re.sub(r'\s*var showRelayInfo by remember \{ mutableStateOf\(false\) \}', '', content)

# 9. Remove RelayNotice function
content = re.sub(r'/\*\*[\s\S]*?fun RelayNotice\([\s\S]*?^\}$', '', content, flags=re.MULTILINE | re.DOTALL)

# 10. Remove onExplainRelay from ChatList
content = re.sub(r'\s*onExplainRelay: \(AttachmentRelay\) -> Unit = \{\},', '', content)

# 11. Remove logic for attachment relay in ChatRow
#  if (row.attachmentRelay == AttachmentRelay.TooLarge ||
#      row.attachmentRelay == AttachmentRelay.Unsupported
#  ) { ... }
#  else { Spacer(Modifier.width(4.dp)) }
# Let's replace the block containing row.attachmentRelay with just Spacer(Modifier.width(4.dp))
# wait, actually it might be easier to use regex
relay_marker_regex = r'''\s*if \(row\.attachmentRelay == AttachmentRelay\.TooLarge \|\|[\s\S]*?\} else \{\s*Spacer\(Modifier\.width\(4\.dp\)\)\s*\}'''
content = re.sub(relay_marker_regex, '\n                                Spacer(Modifier.width(4.dp))', content)

# 12. InputRow stagedAttachmentRelay
content = re.sub(r'\s*stagedAttachmentRelay: AttachmentRelay = AttachmentRelay\.Silent,', '', content)
content = re.sub(r'\s*relay = stagedAttachmentRelay,', '', content)

# 13. StagedAttachment signature relay: AttachmentRelay
content = re.sub(r'\s*relay: AttachmentRelay,', '', content)

# 14. stagedRelayMessage function
content = re.sub(r'@Composable\s*private fun stagedRelayMessage\([\s\S]*?^\}$', '', content, flags=re.MULTILINE | re.DOTALL)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)
print("Done")
