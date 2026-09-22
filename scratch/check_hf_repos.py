import requests
import time

token = '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'
headers = {'Authorization': f'Bearer {token}'}

repos = [
    'ARTPARK-IISc/SraVaani-0.5-live',
    'killbanhar/sravaani-onnx',
    'YashM21/SraVaani-live-0.5-onnx-export-v2',
    'Trendova/sravaani-int8',
    'mobilebytesensei/betterflow-sravaani-streaming-onnx'
]

for repo in repos:
    for attempt in range(3):
        try:
            res = requests.get(f'https://huggingface.co/api/models/{repo}', headers=headers, timeout=10)
            print(f'=== {repo} (status {res.status_code}) ===')
            if res.status_code == 200:
                data = res.json()
                for sib in data.get('siblings', []):
                    name = sib.get('rfilename')
                    size = sib.get('size')
                    print(f'  {name}: {size} bytes')
            break
        except Exception as e:
            print(f'Attempt {attempt+1} failed for {repo}: {e}')
            time.sleep(2)
