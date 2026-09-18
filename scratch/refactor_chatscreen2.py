import sys
import re

file_path = "app/src/main/java/app/swarsetu/ui/chat/ChatScreen.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# Remove dangling `onExplainRelay = { relayMarkerExplained = it },`
content = re.sub(r'\s*onExplainRelay = \{ relayMarkerExplained = it \},', '', content)

# Remove the remainder of the AlertDialog for relayMarkerExplained (lines starting with `icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },`)
# We can match from `icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },` until `    }` that is right before `/**` (EncryptionBadge)
content = re.sub(r'\s*icon = \{ Icon\(Icons\.Filled\.CloudOff, contentDescription = null\) \},.*?confirmButton = \{.*?\},\s*\)\s*\}\s*\}', '\n    }', content, flags=re.DOTALL)

# Remove NearbyOnlyMarker function
content = re.sub(r'/\*\*[\s\S]*?@Composable\s*private fun ColumnScope\.NearbyOnlyMarker\([\s\S]*?^\}$', '', content, flags=re.MULTILINE | re.DOTALL)

# Remove the attachmentRelay check in ChatRow
relay_check_regex = r'\s*// Reach, not delivery — kept on its own line above the tick[\s\S]*?if \(row\.attachmentRelay == AttachmentRelay\.TooLarge \|\|[\s\S]*?row\.attachmentRelay == AttachmentRelay\.Unsupported\s*\)\s*\{\s*NearbyOnlyMarker\(onClick = \{ onExplainRelay\(row\.attachmentRelay\) \}\)\s*\}'
content = re.sub(relay_check_regex, '', content)

# Look at StagedAttachment caption
caption_regex = r'\s*val caption =\s*when \(relay\) \{\s*AttachmentRelay\.TooLarge -> R\.string\.chat_relay_staged_too_large\s*AttachmentRelay\.Unsupported -> R\.string\.chat_relay_staged_unsupported\s*AttachmentRelay\.Silent, AttachmentRelay\.Relayable -> null\s*\}'
content = re.sub(caption_regex, '\n    val caption: Int? = null', content)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)
print("Done")
