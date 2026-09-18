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

# MeshController.kt
mc_pairs = [
    ("import app.swarsetu.mesh.spool.SpoolStatus\n", ""),
    ("    fun mintGroupRoots()\n", ""),
    ("    fun spoolStatus(): List<SpoolStatus> = emptyList()\n", "")
]
replace_exact("app/src/main/java/app/swarsetu/mesh/MeshController.kt", mc_pairs)

# ContactsViewModel.kt
# Looking at compile errors: ContactsViewModel.kt:103:25 Unresolved reference 'mintGroupRoots'.
cvm_pairs = [
    ("                    mesh.mintGroupRoots()\n", "")
]
replace_exact("app/src/main/java/app/swarsetu/ui/contacts/ContactsViewModel.kt", cvm_pairs)

print("Done")
