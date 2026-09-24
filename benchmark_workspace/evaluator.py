import os
import time
import json
import psutil
import gc
import torch
from huggingface_hub import HfApi
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, MBartForConditionalGeneration, MBart50TokenizerFast

LANGUAGES = ['hi', 'gu', 'mr', 'kn', 'ml', 'ta', 'te', 'or', 'bn', 'en']

MODELS = [
    {"id": "facebook/m2m100_418M", "type": "m2m100"},
    {"id": "ai4bharat/indictrans2-indic-indic-dist-320M", "type": "indictrans"},
    {"id": "ai4bharat/indictrans2-en-indic-dist-200M", "type": "indictrans"},
    {"id": "ai4bharat/indictrans2-indic-en-dist-200M", "type": "indictrans"}
]

# Simple hardcoded dataset for fast execution
TEST_SENTENCES = {
    "en": ["The weather is very nice today.", "Location is 18.5204, 73.8567.", "Stop."],
    "hi": ["आज मौसम बहुत अच्छा है।", "स्थान 18.5204, 73.8567 है।", "रुकें।"],
    "mr": ["आज हवामान खूप चांगले आहे.", "स्थान 18.5204, 73.8567 आहे.", "थांबा."],
    "gu": ["આજે હવામાન ખૂબ સારું છે.", "સ્થાન 18.5204, 73.8567 છે.", "થોભો."],
    "bn": ["আজ আবহাওয়া খুব ভালো।", "অবস্থান 18.5204, 73.8567।", "থামুন।"],
    "ta": ["இன்று வானிலை மிகவும் நன்றாக உள்ளது.", "இடம் 18.5204, 73.8567.", "நிறுத்து."],
    "te": ["ఈ రోజు వాతావరణం చాలా బాగుంది.", "స్థానం 18.5204, 73.8567.", "ఆగు."],
    "kn": ["ಇಂದು ಹವಾಮಾನ ತುಂಬಾ ಚೆನ್ನಾಗಿದೆ.", "ಸ್ಥಳ 18.5204, 73.8567 ಆಗಿದೆ.", "ನಿಲ್ಲಿಸು."],
    "ml": ["ഇന്ന് കാലാവസ്ഥ വളരെ നല്ലതാണ്.", "സ്ഥാനം 18.5204, 73.8567 ആണ്.", "നിർത്തുക."],
    "or": ["ଆଜି ପାଗ ବହୁତ ଭଲ ଅଛି।", "ସ୍ଥାନ 18.5204, 73.8567 ଅଟେ।", "ଅଟକନ୍ତୁ।"]
}

def get_actual_size(repo_id):
    api = HfApi()
    try:
        files = api.list_repo_files(repo_id)
        info = api.model_info(repo_id, files_metadata=True)
        total_size = 0
        bin_size = 0
        tokenizer_size = 0
        for s in info.siblings:
            if s.rfilename.endswith(".bin") or s.rfilename.endswith(".safetensors"):
                bin_size += s.size
            elif 'token' in s.rfilename or 'vocab' in s.rfilename or 'spm' in s.rfilename or 'dict' in s.rfilename:
                tokenizer_size += s.size
            total_size += s.size
        return total_size, bin_size, tokenizer_size
    except Exception as e:
        return 0, 0, 0

def measure_memory():
    process = psutil.Process(os.getpid())
    return process.memory_info().rss / (1024 * 1024)

def run_evaluation():
    results = {}
    baseline_mem = measure_memory()
    print(f"Baseline Memory: {baseline_mem:.2f} MB")

    for model_info in MODELS:
        m_id = model_info["id"]
        print(f"\nEvaluating {m_id}...")
        try:
            total_size, bin_size, tok_size = get_actual_size(m_id)
            print(f"Total Size on Hub: {total_size/1024/1024:.2f} MB")
            
            # Simulated local FP32 loading (actual download and load)
            mem_before = measure_memory()
            t0 = time.time()
            if model_info["type"] == "m2m100":
                tokenizer = MBart50TokenizerFast.from_pretrained(m_id)
                model = MBartForConditionalGeneration.from_pretrained(m_id)
            else:
                tokenizer = AutoTokenizer.from_pretrained(m_id, trust_remote_code=True)
                model = AutoModelForSeq2SeqLM.from_pretrained(m_id, trust_remote_code=True)
            load_time = time.time() - t0
            
            mem_after = measure_memory()
            peak_ram = mem_after - mem_before
            print(f"Loaded in {load_time:.2f}s, Peak RAM delta: {peak_ram:.2f} MB")
            
            # Simple latency test
            latencies = []
            for src_lang in ['en', 'hi']:
                for tgt_lang in ['hi', 'mr']:
                    if src_lang == tgt_lang: continue
                    for text in TEST_SENTENCES.get(src_lang, []):
                        if model_info["type"] == "m2m100":
                            # M2M100 style
                            tokenizer.src_lang = src_lang
                            encoded = tokenizer(text, return_tensors="pt")
                            t_inf_start = time.time()
                            generated_tokens = model.generate(**encoded, forced_bos_token_id=tokenizer.get_vocab().get(tgt_lang, tokenizer.lang_code_to_id.get(tgt_lang, 0)))
                            latencies.append(time.time() - t_inf_start)
                        else:
                            # IndicTrans2 style
                            encoded = tokenizer(text, return_tensors="pt")
                            t_inf_start = time.time()
                            generated_tokens = model.generate(**encoded)
                            latencies.append(time.time() - t_inf_start)
                            
            avg_latency = sum(latencies)/len(latencies) if latencies else 0
            
            del model
            del tokenizer
            gc.collect()
            torch.cuda.empty_cache() if torch.cuda.is_available() else None
            
            results[m_id] = {
                "total_hub_size_mb": total_size / (1024*1024),
                "model_weights_mb": bin_size / (1024*1024),
                "tokenizer_size_mb": tok_size / (1024*1024),
                "load_time_s": load_time,
                "peak_ram_mb": peak_ram,
                "avg_latency_s": avg_latency
            }
        except Exception as e:
            print(f"Error evaluating {m_id}: {e}")
            results[m_id] = {"error": str(e)}

    with open("eval_results.json", "w") as f:
        json.dump(results, f, indent=4)
        
    print("Evaluation completed.")

if __name__ == "__main__":
    run_evaluation()
