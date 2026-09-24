import os
import sys
import hashlib
from pathlib import Path
from huggingface_hub import HfApi

def compute_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(64 * 1024):
            h.update(chunk)
    return h.hexdigest()

def main():
    token_file = Path.home() / ".cache" / "huggingface" / "token"
    if not token_file.exists():
        print("Error: Hugging Face token not found at", token_file)
        sys.exit(1)

    token = token_file.read_text().strip()
    api = HfApi(token=token)
    repo_id = "ashishbiradar/sravaani-1.0-onnx"

    print(f"Ensuring repository {repo_id} exists...")
    api.create_repo(repo_id=repo_id, repo_type="model", exist_ok=True, private=False)

    src_dir = Path("scratch/sravaani10/onnx")
    files = ["tokens.txt", "tables.bin", "decoder_joint.onnx", "encoder.onnx"]

    for fname in files:
        fpath = src_dir / fname
        if not fpath.exists():
            print(f"Warning: {fpath} does not exist!")
            continue

        size_mb = fpath.stat().st_size / (1024 * 1024)
        print(f"Uploading {fname} ({size_mb:.2f} MB)...")
        api.upload_file(
            path_or_fileobj=str(fpath),
            path_in_repo=fname,
            repo_id=repo_id,
            repo_type="model",
            commit_message=f"Upload {fname} ({size_mb:.2f} MB)"
        )
        print(f"[OK] Successfully uploaded {fname}")

    print("\nAll files uploaded successfully to:", f"https://huggingface.co/{repo_id}")
    repo_files = api.list_repo_files(repo_id=repo_id)
    print("Files currently in repo:", repo_files)

if __name__ == "__main__":
    main()
