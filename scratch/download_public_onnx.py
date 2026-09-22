import requests
import os
import sys
from pathlib import Path
import onnx

token = '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'
headers = {'Authorization': f'Bearer {token}'}

out_dir = Path("scratch/sravaani/onnx")
out_dir.mkdir(parents=True, exist_ok=True)

# 1. Download tokens.txt
tokens_url = 'https://huggingface.co/YashM21/SraVaani-live-0.5-onnx-export-v2/raw/main/latency_480ms/tokens.txt'
print("Downloading tokens.txt...")
r = requests.get(tokens_url, headers=headers)
if r.status_code == 200:
    (out_dir / "tokens.txt").write_bytes(r.content)
    print(f"Saved tokens.txt ({len(r.content)} bytes)")

# 2. Download ONNX model from killbanhar/sravaani-onnx or YashM21 or Trendova
# Let's check killbanhar encoder
for repo, filename, local_name in [
    ('killbanhar/sravaani-onnx', 'encoder.onnx', 'sravaani_encoder.onnx'),
    ('killbanhar/sravaani-onnx', 'decoder_joint.onnx', 'sravaani_ctc.onnx'),
]:
    url = f'https://huggingface.co/{repo}/resolve/main/{filename}'
    dest = out_dir / local_name
    print(f"Downloading {repo}/{filename} -> {dest} ...")
    with requests.get(url, headers=headers, stream=True) as resp:
        if resp.status_code == 200:
            with open(dest, 'wb') as f:
                for chunk in resp.iter_content(chunk_size=1024*1024):
                    f.write(chunk)
            print(f"Downloaded {dest} ({dest.stat().st_size} bytes)")
        else:
            print(f"Failed {filename}: status {resp.status_code}")

print("Done download script.")
