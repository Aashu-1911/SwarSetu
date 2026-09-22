import requests
import onnx
import io

token = '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'
headers = {'Authorization': f'Bearer {token}'}

# Test YashM21
url = 'https://huggingface.co/YashM21/SraVaani-live-0.5-onnx-export-v2/resolve/main/latency_480ms/model.onnx'
print('Downloading header for YashM21 latency_480ms/model.onnx...')
r = requests.get(url, headers=headers, stream=True)
print('Status:', r.status_code)
if r.status_code == 200:
    chunk = b''
    for c in r.iter_content(chunk_size=1024*1024):
        chunk += c
        if len(chunk) > 10*1024*1024: # first 10MB to read proto structure if possible
            break
    print(f'Read {len(chunk)} bytes')
