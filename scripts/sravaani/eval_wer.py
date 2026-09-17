#!/usr/bin/env python3
"""P8/P9 WER/CER evaluation for SraVaani 0.5 — real measurements, fp32 vs int8.

The ONNX engines replicate the ANDROID RUNTIME semantics exactly (SravaaniEngine.kt +
SravaaniStreamingState.kt + SravaaniCtcDecoder.kt + SravaaniTokenizer.kt):

- featurizer graph: wav [1,N] f32, wav_len [1] i64 -> feats [1,128,T] f32, feat_len [1]
- streaming loop: chunk 112 frames, shift 112, min_sampling 1; the pre-encode cache (9
  frames, zero pad on step 0) is INSIDE the exported encoder graph (verified by the P3
  golden test), so the runtime feeds the raw 112-frame window.
- encoder caches (cache_last_channel [1,17,70,1024], cache_last_time [1,17,1024,8],
  cache_last_channel_len) carried across chunks.
- CTC head: enc [1,1024,T'] -> log_probs [1,T',5001] (TIME-major, vocab axis 2 — OUR export
  flattens the TimeMask/LogSoftmax tail to (1,T,V); the official NeMo export is (1,V,T)).
  Verified against the official TorchScript wrapper on real FLEURS speech 2026-09-17.
- decode: per-frame argmax, collapse with `prev` carried ACROSS chunk seams,
  blank = 5000 (config.json blank_id), pieces from tokens.txt
  (join, U+2581 -> space, strip leading space).

Corpus (Task F): HuggingFace `google/fleurs`, dev split (the "validation" split), CC-BY-4.0
(license verified on the dataset card), all 10 required languages, official transcripts.
Recorded caveat: FLEURS audio is prompted readings of Wikipedia sentences, so references
may not be verbatim transcripts of the audio; measured WER is therefore a valid
COMPARATIVE baseline (fp32 vs int8 see identical data), not an absolute accuracy claim.

Usage:
  python scripts/sravaani/eval_wer.py --stage-corpus
  python scripts/sravaani/eval_wer.py --label baseline-fp32 --out scratch/sravaani/logs/eval/baseline_fp32.json
  python scripts/sravaani/eval_wer.py --engine onnx-int8 --label int8-candidate --out scratch/sravaani/logs/eval/int8.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import unicodedata
from pathlib import Path

import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~।॥·–—…"
REQUIRED_LANGUAGES = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]
FLEURS_LANG = {
    "hi": "hi_in", "gu": "gu_in", "mr": "mr_in", "kn": "kn_in", "ml": "ml_in",
    "ta": "ta_in", "te": "te_in", "or": "or_in", "bn": "bn_in", "en": "en_us",
}

MEL, VOCAB, BLANK = 128, 5001, 5000
CHUNK, SHIFT, PEC = 112, 112, 9

CORPUS_DIR = Path("scratch/sravaani/corpus/fleurs")


# ------------------------------------------------------------------ normalization
def normalize_tokens(text: str) -> list:
    """Mirror of WerCalculator.normalizeToTokens (Kotlin) — keep in lockstep."""
    nfc = unicodedata.normalize("NFC", text).casefold()
    toks = []
    for tok in nfc.split():
        tok = tok.strip(PUNCTUATION).strip()
        if tok:
            toks.append(tok)
    return toks


def normalize_chars(text: str) -> list:
    nfc = unicodedata.normalize("NFC", text).casefold()
    return [c for c in nfc if not c.isspace() and c not in PUNCTUATION]


def levenshtein(ref: list, hyp: list) -> int:
    n, m = len(ref), len(hyp)
    if n == 0 or m == 0:
        return max(n, m)
    prev = list(range(m + 1))
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                cur[j] = prev[j - 1]
            else:
                cur[j] = min(prev[j - 1], prev[j], cur[j - 1]) + 1
        prev = cur
    return prev[m]


# ------------------------------------------------------------------ ONNX engine
class SraVaaniOnnxEngine:
    """Exact port of the Android runtime loop (see module docstring)."""

    def __init__(self, featurizer: str, encoder: str, ctc: str, tokens: str,
                 threads: int = 4):
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self._fe = ort.InferenceSession(featurizer, so, providers=["CPUExecutionProvider"])
        self._enc = ort.InferenceSession(encoder, so, providers=["CPUExecutionProvider"])
        self._ctc = ort.InferenceSession(ctc, so, providers=["CPUExecutionProvider"])
        self._pieces = [
            ln for ln in Path(tokens).read_text(encoding="utf-8").splitlines() if ln
        ]
        assert len(self._pieces) == 5000 and self._pieces[0] == "<unk>"

    def transcribe_file(self, path: Path) -> str:
        import soundfile as sf

        wav, sr = sf.read(str(path), dtype="float32")
        if sr != 16000:
            raise ValueError(f"corpus wav must be 16 kHz, got {sr}: {path}")
        if wav.ndim > 1:
            wav = wav.mean(axis=1)
        return self.transcribe_pcm(wav)

    def transcribe_pcm(self, wav: np.ndarray) -> str:
        """Exact port of the Kotlin runtime loop (SravaaniStreamingState.assembleChunk):
        block = PEC pre-encode cache [start-9, start) (zero-pad where <0) + chunk body
        [start, start+112); feat_len = total block frames; caches carried; prev-id collapse
        across seams; finalize drains the tail at any length >= 1 frame."""
        feats, feat_len = self._fe.run(None, {
            "wav": wav[None, :].astype(np.float32),
            "wav_len": np.array([len(wav)], dtype=np.int64),
        })
        T = int(feat_len[0])
        # Per-feature normalization exactly as the Kotlin runtime does it
        # (SravaaniFeaturizer: stats over ALL valid frames, SAMPLE std (denominator N-1),
        # NaN->0, +CONSTANT 1e-5) — the featurizer graph emits RAW log-mel.
        x = feats[:, :, :T]
        mu = x.mean(axis=2, keepdims=True)
        var = ((x - mu) ** 2).sum(axis=2, keepdims=True) / max(T - 1, 1)
        std = np.where(np.isnan(np.sqrt(var)), 0.0, np.sqrt(var)) + 1e-5
        feats = (x - mu) / std
        tokens: list = []
        prev = BLANK
        ch = np.zeros((1, 17, 70, 1024), np.float32)
        tc = np.zeros((1, 17, 1024, 8), np.float32)
        chlen = 0
        start = 0
        while start < T:
            pending = T - start
            body = min(CHUNK, pending) if pending >= CHUNK else pending  # finalize tail
            end = start + body
            pec = np.zeros((1, MEL, PEC), np.float32)
            have = min(PEC, start)
            if have > 0:
                pec[:, :, PEC - have:] = feats[:, :, start - have:start]
            block = np.concatenate([pec, feats[:, :, start:end]], axis=2)
            enc, enc_len, ch, tc, chlen_next = self._enc.run(None, {
                "feats": np.ascontiguousarray(block, dtype=np.float32),
                "feat_len": np.array([block.shape[2]], dtype=np.int64),
                "cache_last_channel": ch,
                "cache_last_time": tc,
                "cache_last_channel_len": np.array([chlen], dtype=np.int64),
            })
            chlen = int(np.asarray(chlen_next).reshape(-1)[0])
            lp = self._ctc.run(None, {"enc": enc})[0]  # [1, T', 5001] TIME-major
            valid = min(int(enc_len[0]), lp.shape[1])
            for t in range(valid):
                k = int(lp[0, t, :].argmax())
                if k != prev and k != BLANK:
                    tokens.append(k)
                prev = k
            start += SHIFT
        text = "".join(self._pieces[i] for i in tokens).replace("\u2581", " ")
        return text[1:] if text.startswith(" ") else text


# ------------------------------------------------------------------ FLEURS staging
def stage_corpus(limit: int) -> int:
    """Download the FLEURS validation tarball + transcript TSV per language directly from
    the google/fleurs dataset repo (huggingface_hub), avoiding the `datasets` audio-decode
    stack (torchcodec), which is fragile on Windows. Writes 16 kHz PCM16 wavs + manifests."""
    import soundfile as sf
    from huggingface_hub import hf_hub_download

    CORPUS_DIR.mkdir(parents=True, exist_ok=True)
    for lang in REQUIRED_LANGUAGES:
        cfg = FLEURS_LANG[lang]
        out_dir = CORPUS_DIR / lang
        out_dir.mkdir(parents=True, exist_ok=True)
        tsv_path = hf_hub_download(
            repo_id="google/fleurs", repo_type="dataset",
            filename=f"data/{cfg}/dev.tsv")
        tar_path = hf_hub_download(
            repo_id="google/fleurs", repo_type="dataset",
            filename=f"data/{cfg}/audio/dev.tar.gz")
        rows = {}
        with open(tsv_path, encoding="utf-8") as f:
            # FLEURS TSVs are HEADERLESS (verified): id, filename, raw_transcript,
            # normalized_transcript, pronunciation_transcript, num_samples, gender.
            for line in f:
                cols = line.rstrip("\n").split("\t")
                if len(cols) >= 4 and cols[1].endswith(".wav"):
                    rows[cols[1][:-4]] = cols[3].strip()  # keyed by stem; normalized transcript
        import io
        import tarfile
        staged = 0
        manifest_lines: list[str] = []  # written once at the end (idempotent re-runs)
        with tarfile.open(tar_path, "r:gz") as tar:
            for member in tar:
                if not member.isfile() or not member.name.lower().endswith(".wav"):
                    continue
                stem = Path(member.name).stem
                if stem not in rows:
                    continue
                if staged >= limit:
                    break
                f_in = tar.extractfile(member)
                wav, sr = sf.read(io.BytesIO(f_in.read()), dtype="float32")
                if sr != 16000:
                    raise ValueError(f"unexpected sample rate {sr}")
                name = f"{lang}_{staged:04d}.wav"
                sf.write(str(out_dir / name), wav, 16000, subtype="PCM_16")
                ref = rows[stem]
                manifest_lines.append(
                    json.dumps({"audio": name, "reference": ref,
                                "fleurs_id": stem}, ensure_ascii=False))
                staged += 1
        (out_dir / "manifest.jsonl").write_text(
            "\n".join(manifest_lines) + ("\n" if manifest_lines else ""), encoding="utf-8")
        print(f"staged {staged} utterances for {lang}")
    print(f"corpus staged under {CORPUS_DIR} (FLEURS dev split, CC-BY-4.0)")
    return 0


# ------------------------------------------------------------------ main
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage-corpus", action="store_true",
                    help="download+stage FLEURS validation split once, then exit")
    ap.add_argument("--limit", type=int, default=10, help="utterances per language")
    ap.add_argument("--engine", choices=["onnx", "onnx-int8"], default="onnx")
    ap.add_argument("--label", default="unlabeled")
    ap.add_argument("--out", default=None)
    ap.add_argument("--encoder", default=None)
    ap.add_argument("--featurizer", default="scratch/sravaani/onnx/sravaani_featurizer.onnx")
    ap.add_argument("--ctc", default="scratch/sravaani/onnx/sravaani_ctc.onnx")
    ap.add_argument("--tokens", default="scratch/sravaani/onnx/tokens.txt")
    ap.add_argument("--corpus", default=str(CORPUS_DIR))
    ap.add_argument("--languages", default="",
                    help="comma list to restrict (e.g. hi,kn) — default: all staged")
    ap.add_argument("--merge-batches", nargs="+", metavar="JSON",
                    help="merge per-language batch result JSONs (same engine/label) into "
                         "--out with recomputed micro aggregates; no inference")
    args = ap.parse_args()

    if args.stage_corpus:
        return stage_corpus(args.limit)

    if args.merge_batches:
        merged: dict = {"label": args.label, "per_language": {}}
        for p in args.merge_batches:
            part = json.loads(Path(p).read_text(encoding="utf-8"))
            merged.setdefault("engine", part.get("engine"))
            if part.get("engine") != merged["engine"]:
                print(f"BLOCKED: engine mismatch in {p}: {part.get('engine')} != {merged['engine']}")
                return 2
            merged["per_language"].update(part["per_language"])
        pl = merged["per_language"]
        S = sum(v["S"] for v in pl.values() if v.get("status") == "MEASURED")
        N = sum(v["N_words"] for v in pl.values() if v.get("status") == "MEASURED")
        CS = sum(v["char_err"] for v in pl.values() if v.get("status") == "MEASURED")
        CN = sum(v["N_chars"] for v in pl.values() if v.get("status") == "MEASURED")
        merged["aggregate"] = {
            "measured_languages": sorted(pl),
            "WER_micro": round(S / N, 4) if N else None,
            "CER_micro": round(CS / CN, 4) if CN else None,
            "S_total": S, "N_total": N, "char_err_total": CS, "N_chars_total": CN,
        }
        merged["note"] = "merged from per-language batches; identical engine/config"
        if args.out:
            out = Path(args.out)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(merged, indent=2, ensure_ascii=False), encoding="utf-8")
            print(f"merged -> {out} ({sorted(pl)})")
        return 0

    corpus = Path(args.corpus)
    if not corpus.is_dir():
        print("BLOCKED: corpus not staged — run with --stage-corpus first. No WER fabricated.")
        return 2

    if args.engine == "onnx-int8":
        encoder = args.encoder or "scratch/sravaani/onnx/sravaani_encoder.int8.onnx"
    else:
        encoder = args.encoder or "scratch/sravaani/onnx/sravaani_encoder.onnx"
    engine = SraVaaniOnnxEngine(args.featurizer, encoder, args.ctc, args.tokens)

    langs = ([l.strip() for l in args.languages.split(",") if l.strip()]
             if args.languages else REQUIRED_LANGUAGES)
    per_language: dict = {}
    werr_total = wN_total = cerr_total = cN_total = 0
    measured = []
    t0 = time.perf_counter()
    audio_s = 0.0
    for lang in langs:
        ldir = corpus / lang
        manifest = ldir / "manifest.jsonl"
        if not manifest.is_file():
            per_language[lang] = {"status": "BLOCKED", "reason": f"no manifest at {manifest}"}
            continue
        rows = [json.loads(l) for l in manifest.open(encoding="utf-8") if l.strip()]
        rows = rows[: args.limit]
        werr = wN = cerr = cN = 0
        for row in rows:
            wav_path = ldir / row["audio"]
            import soundfile as sf

            info = sf.info(str(wav_path))
            audio_s += info.frames / info.samplerate
            hyp = engine.transcribe_file(wav_path)
            ref = row["reference"]
            rt, ht = normalize_tokens(ref), normalize_tokens(hyp)
            werr += levenshtein(rt, ht)
            wN += len(rt)
            rc, hc = normalize_chars(ref), normalize_chars(hyp)
            cerr += levenshtein(rc, hc)
            cN += len(rc)
        per_language[lang] = {
            "status": "MEASURED", "utterances": len(rows),
            "wer": round(werr / wN, 4) if wN else None,
            "cer": round(cerr / cN, 4) if cN else None,
            "S": werr, "N_words": wN, "char_err": cerr, "N_chars": cN,
        }
        measured.append(lang)
        werr_total += werr
        wN_total += wN
        cerr_total += cerr
        cN_total += cN
        print(f"{lang}: WER={per_language[lang]['wer']} CER={per_language[lang]['cer']} "
              f"n={len(rows)}")
    wall = time.perf_counter() - t0

    result = {
        "label": args.label,
        "engine": args.engine,
        "encoder": encoder,
        "per_language": per_language,
        "aggregate": {
            "measured_languages": measured,
            "WER_micro": round(werr_total / wN_total, 4) if wN_total else None,
            "CER_micro": round(cerr_total / cN_total, 4) if cN_total else None,
            "S_total": werr_total, "N_total": wN_total,
        },
        "wall_s": round(wall, 1),
        "audio_s": round(audio_s, 1),
        "rtf_encoder_side": round(wall / audio_s, 4) if audio_s else None,
        "license": "google/fleurs CC-BY-4.0 (dev split)",
        "caveat": "FLEURS = prompted readings of Wikipedia sentences; references may not be "
                  "verbatim -> comparative baseline (fp32 vs int8), not absolute accuracy.",
    }
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"results: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
