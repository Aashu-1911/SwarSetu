import sys
import re

mesh_manager = "app/src/main/java/app/swarsetu/mesh/MeshManager.kt"
with open(mesh_manager, "r", encoding="utf-8") as f:
    mm = f.read()

# 1. Imports
mm = re.sub(r'import app\.swarsetu\.mesh\.spool\..*?\n', '', mm)

# 2. Constructor parameters
mm = re.sub(r'\s*// The spool plane\'s shared group roots[\s\S]*?private val groupRoots: GroupRootStore,', '', mm)
mm = re.sub(r'\s*// Opens WebSocket sessions to spools[\s\S]*?private val spoolDialer: SpoolDialer\? = null,', '', mm)

# 3. AttachmentDeferPolicy and scopeSync
mm = re.sub(r'\s*/\*\*[\s\S]*?private val attachmentDefer =[\s\S]*?clock = clock,\n\s*\)\n', '\n', mm)

mm = re.sub(r'\s*/\*\*[\s\S]*?private val scopeSync: ScopeSync\? =[\s\S]*?clock = clock,\n\s*\)\n\s*\}\n', '\n', mm)

# Remove `adoptGroupRoot` and `onGroupRootCtl` from MeshPipeline constructor call
mm = re.sub(r'\s*adoptGroupRoot = ::adoptGroupRoot,', '', mm)
mm = re.sub(r'\s*onGroupRootCtl = ::onGroupRootCtl,', '', mm)

# 4. Lifecycle methods
mm = re.sub(r'\s*scopeSync\?\.start\(session\)', '', mm)
mm = re.sub(r'\s*scopeSync\?\.stop\(\)', '', mm)

# 5. spoolStatus
mm = re.sub(r'\s*override fun spoolStatus\(\): List<SpoolStatus> = scopeSync\?\.status\(\)\.orEmpty\(\)', '', mm)

# 6. Sweep calls
mm = re.sub(r'\s*groupRoots\.sweep\(clock\(\)\).*?\n', '\n', mm)
mm = re.sub(r'\s*mintGroupRootsIfDue\(\).*?\n', '\n', mm)

# 7. group root logic
mm = re.sub(r'\s*override fun mintGroupRoots\(\) \{\s*sessionScope\?\.launch \{ mintGroupRootsIfDue\(\) \}\s*\}', '', mm)

def remove_function(content, fun_name):
    pattern = r'\s*/\*\*(?:(?!\*/).)*\*/\s*(?:@\w+\s*)*\s*(?:private |internal |public )?(?:suspend )?fun ' + fun_name + r'\b[^{]*\{'
    match = re.search(pattern, content, re.DOTALL)
    if not match:
        # try without javadoc
        pattern = r'\s*(?:@\w+\s*)*\s*(?:private |internal |public )?(?:suspend )?fun ' + fun_name + r'\b[^{]*\{'
        match = re.search(pattern, content, re.DOTALL)
        if not match:
            print(f"Could not find function {fun_name}")
            return content
    
    start_idx = match.start()
    # Now find the matching closing brace
    idx = match.end() - 1
    brace_count = 0
    while idx < len(content):
        if content[idx] == '{':
            brace_count += 1
        elif content[idx] == '}':
            brace_count -= 1
            if brace_count == 0:
                break
        idx += 1
    
    end_idx = idx + 1
    print(f"Removing {fun_name}")
    return content[:start_idx] + "\n" + content[end_idx:]

mm = remove_function(mm, "mintGroupRootsIfDue")
mm = remove_function(mm, "adoptGroupRoot")
mm = remove_function(mm, "onGroupRootCtl")
mm = remove_function(mm, "scopeBlobs")
mm = remove_function(mm, "groupScopeRoots")

with open(mesh_manager, "w", encoding="utf-8") as f:
    f.write(mm)
print("Done")
