import sys
import re

mesh_controller = "app/src/main/java/app/swarsetu/mesh/MeshController.kt"
with open(mesh_controller, "r", encoding="utf-8") as f:
    mc = f.read()
mc = re.sub(r'import app\.swarsetu\.mesh\.spool\.SpoolStatus\n', '', mc)
mc = re.sub(r'\s*fun mintGroupRoots\(\)', '', mc)
mc = re.sub(r'\s*fun spoolStatus\(\): List<SpoolStatus> = emptyList\(\)', '', mc)
with open(mesh_controller, "w", encoding="utf-8") as f:
    f.write(mc)


mesh_manager = "app/src/main/java/app/swarsetu/mesh/MeshManager.kt"
with open(mesh_manager, "r", encoding="utf-8") as f:
    mm = f.read()

# 1. Imports
mm = re.sub(r'import app\.swarsetu\.mesh\.spool\..*?\n', '', mm)

# 2. Constructor parameters
mm = re.sub(r'\s*// The spool plane\'s shared group roots[\s\S]*?private val groupRoots: GroupRootStore,', '', mm)
mm = re.sub(r'\s*// Opens WebSocket sessions to spools[\s\S]*?private val spoolDialer: SpoolDialer\? = null,', '', mm)

# 3. AttachmentDeferPolicy and scopeSync
defer_regex = r'\s*/\*\*[\s\S]*?private val attachmentDefer =[\s\S]*?\}\s*\)\s*'
mm = re.sub(defer_regex, '\n', mm)

sync_regex = r'\s*/\*\*[\s\S]*?private val scopeSync: ScopeSync\? =[\s\S]*?\}\s*\)\s*\}\s*'
mm = re.sub(sync_regex, '\n', mm)

# 4. Lifecycle methods
mm = re.sub(r'\s*scopeSync\?\.start\(session\)', '', mm)
mm = re.sub(r'\s*scopeSync\?\.stop\(\)', '', mm)

# 5. spoolStatus
mm = re.sub(r'\s*override fun spoolStatus\(\): List<SpoolStatus> = scopeSync\?\.status\(\)\.orEmpty\(\)', '', mm)

# 6. Sweep calls
mm = re.sub(r'\s*groupRoots\.sweep\(clock\(\)\) // drop rotated-away group roots past their drain window', '', mm)
mm = re.sub(r'\s*mintGroupRootsIfDue\(\) // mint a group\'s spool root when it is our turn \(spec §3\.2\)', '', mm)
mm = re.sub(r'\s*groupRoots\.sweep\(clock\(\)\)', '', mm)

# 7. group root logic
mm = re.sub(r'\s*override fun mintGroupRoots\(\) \{\s*sessionScope\?\.launch \{ mintGroupRootsIfDue\(\) \}\s*\}', '', mm)

mint_regex = r'\s*/\*\*[\s\S]*?private suspend fun mintGroupRootsIfDue\(\) \{[\s\S]*?\}\s*\}'
mm = re.sub(mint_regex, '\n', mm)

adopt_regex = r'\s*/\*\*[\s\S]*?private suspend fun adoptGroupRoot\([\s\S]*?\}\s*'
mm = re.sub(adopt_regex, '\n', mm)

# 8. onCustodyChanged
mm = re.sub(r'\s*scopeSync\?\.onCustodyChanged\(\)', '', mm)

# 9. scopeBlobs
blobs_regex = r'\s*/\*\*[\s\S]*?internal fun scopeBlobs\(\): ScopeBlobs =[\s\S]*?\}\s*\}'
mm = re.sub(blobs_regex, '\n', mm)

# 10. groupScopeRoots
roots_regex = r'\s*/\*\*[\s\S]*?private suspend fun groupScopeRoots\(\): List<GroupScopeRoots> \{[\s\S]*?\}\s*\}'
mm = re.sub(roots_regex, '\n', mm)

# Let's verify if there are any other GroupRootPolicy occurrences left
# Wait, let me also check `onGroupKeyReceived` logic inside MeshManager if there is anything left that calls adoptGroupRoot
on_group_key_regex = r'\s*val adopted = adoptGroupRoot\(senderId, gk, now\)\s*\}'
mm = re.sub(on_group_key_regex, '\n        }', mm)
# wait, actually the onGroupKeyReceived looks like this:
#     private suspend fun onGroupKeyReceived(
#         senderId: String,
#         gk: GroupKeyPayload,
#         now: Long,
#     ) {
#         val adopted = adoptGroupRoot(senderId, gk, now)
#         metrics.onGroupKeyReceived()
#         // ...
#         if (adopted) {
#             scopeSync?.onCustodyChanged()
#             gossipGroupRoot(gk.groupId, skip = senderId)
#             return
#         }
#         val held = groupRoots.find(gk.groupId)?.takeIf { it.root != null } ?: return
#         val theirs = gk.gr
#         // ...
# Let's just remove everything inside `onGroupKeyReceived` after `metrics.onGroupKeyReceived()` related to roots.
# Actually, wait, `CTL_GROUP_KEY` was specifically for group roots (gossip). If group roots are removed, `onGroupKeyReceived` should just do nothing or we should remove it?
# Let's remove `onGroupKeyReceived` completely? Wait, is `CTL_GROUP_KEY` used for anything else?
# I'll check how it is called.

with open(mesh_manager, "w", encoding="utf-8") as f:
    f.write(mm)
print("Done")
