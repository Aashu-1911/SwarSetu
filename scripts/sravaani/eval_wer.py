#!/usr/bin/env python3
"""P8 WER/CER evaluation harness for SraVaani 0.5 (desktop).

Evaluates a checkpoint (official TorchScript fp16 OR an optimized ONNX export) against a
staged corpus of audio + reference transcripts, producing per-language and aggregate
WER/CER tables. THIS SCRIPT MEASURES — it never fabricates: languages with no staged
corpus are reported as BLOCKED rows.

Corpus layout (staged under --corpus, one directory per language):
  corpus/
    hi/manifest.jsonl     {"audio": "utt001.wav", "reference": "यह एक परीक्षण है", ...}
    hi/utt001.wav         16 kHz mono PCM16 WAV
    ...

Manifest fields: audio (path relative to the language dir), reference (transcript string),
optional: speaker, duration_s, condition (quiet/noise/...).

WER normalization (must match app/src/main/java/app/swarsetu/stt/eval/WerCalculator.kt):
NFC → casefold → whitespace tokens → edge-punctuation strip (incl. danda) → drop empties.
CER is code-point level after the same normalization with whitespace removed.

Usage:
  python eval_wer.py --corpus corpus/ --engine torch \
      --model scratch/sravaani/hf/model-asr-streaming.fp16.ts \
      --preproc scratch/sravaani/hf/preproc.pt --tokenizer scratch/sravaani/hf/tokenizer.model \
      --out scratch/sravaani/logs/eval/baseline_fp16.json
"""
import argparse
import json
import sys
import unicodedata
from pathlib import Path

PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~।॥·–—…"
REQUIRED_LANGUAGES = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]


def normalize_tokens(text: str) -> list:
    """Mirror of WerCalculator.normalizeToTokens (Kotlin) — keep the two in lockstep."""
    nfc = unicodedata.normalize("NFC", text).casefold()
    toks = []
    for tok in nfc.split():
        tok = tok.strip(PUNCTUATION + "".join(c for c in tok if False)).strip()
        if tok:
            toks.append(tok)
    return toks


def normalize_chars(text: str) -> list:
    nfc = unicodedata.normalize("NFC", text).casefold()
    return [c for c in nfc if not c.isspace() and c not in PUNCTUATION]


def edit_counts(ref: list, hyp: list):
    """Levenshtein S/D/I via DP backtrace."""
    n, m = len(ref), len(hyp)
    if n == 0 or m == 0:
        return (0, n, m)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                dp[i][j] = dp[i - 1][j - 1]
            else:
                dp[i][j] = min(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1]) + 1
    sub = dele = ins = 0
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and ref[i - 1] == hyp[j - 1] and dp[i][j] == dp[i - 1][j - 1]:
            i -= 1
            j -= 1
        elif i > 0 and j > 0 and dp[i][j] == dp[i - 1][j - 1] + 1:
            sub += 1
            i -= 1
            j -= 1
        elif i > 0 and dp[i][j] == dp[i - 1][j] + 1:
            dele += 1
            i -= 1
        else:
            ins += 1
            j -= 1
    return (sub, dele, ins)


def load_engine(kind: str, args):
    if kind == "torch":
        sys.path.insert(0, str(Path(__file__).parent))
        from p2_smoke import TorchStreamEngine  # reuse the P2-verified wrapper

        return TorchStreamEngine(
            model_path=args.model,
            preproc_path=args.preproc,
            tokenizer_path=args.tokenizer,
        )
    raise SystemExit(f"engine kind {kind!r} not wired yet (onnx lands with P9 equivalence)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--engine", choices=["torch", "onnx"], default="torch")
    ap.add_argument("--model", default="scratch/sravaani/hf/model-asr-streaming.fp16.ts")
    ap.add_argument("--preproc", default="scratch/sravaani/hf/preproc.pt")
    ap.add_argument("--tokenizer", default="scratch/sravaani/hf/tokenizer.model")
    ap.add_argument("--label", default="unlabeled-run", help="e.g. baseline-fp16-desktop")
    ap.add_argument("--out", default=None, help="JSON results path")
    ap.add_argument("--limit", type=int, default=0, help="max utterances per language (0=all)")
    args = ap.parse_args()

    corpus = Path(args.corpus)
    if not corpus.is_dir():
        print(f"BLOCKED: corpus not staged at {corpus} — no WER can be reported (never fabricate).")
        print("Stage manifests per docs/STT_SRAVAANI_PRODUCTION.md §15 (license-verified datasets).")
        return 2

    engine = load_engine(args.engine, args)

    per_language = {}
    for lang in REQUIRED_LANGUAGES:
        ldir = corpus / lang
        manifest = ldir / "manifest.jsonl"
        if not manifest.is_file():
            per_language[lang] = {"status": "BLOCKED", "reason": f"no manifest at {manifest}"}
            continue

        rows = [json.loads(l) for l in manifest.open(encoding="utf-8") if l.strip()]
        if args.limit:
            rows = rows[: args.limit]

        wS = wD = wI = wN = 0
        cS = cD = cI = cN = 0
        utterances = []
        for row in rows:
            wav = ldir / row["audio"]
            ref = row["reference"]
            hyp = engine.transcribe_file(wav)
            s, d, i = edit_counts(normalize_tokens(ref), normalize_tokens(hyp))
            wS, wD, wI, wN = wS + s, wD + d, wI + i, wN + len(normalize_tokens(ref))
            cs, cd, ci = edit_counts(normalize_chars(ref), normalize_chars(hyp))
            cS, cD, cI, cN = cS + cs, cD + cd, cI + ci, cN + len(normalize_chars(ref))
            utterances.append({"audio": row["audio"], "reference": ref, "hypothesis": hyp})

        wer = (wS + wD + wI) / wN if wN else float("nan")
        cer = (cS + cD + cI) / cN if cN else float("nan")
        per_language[lang] = {
            "status": "MEASURED",
            "utterances": len(rows),
            "wer": round(wer, 4),
            "cer": round(cer, 4),
            "S_D_I": [wS, wD, wI],
            "N_words": wN,
            "N_chars": cN,
        }
        print(f"{lang}: WER={wer:.4f} CER={cer:.4f} n={len(rows)}")

    missing = [l for l, v in per_language.items() if v.get("status") != "MEASURED"]
    result = {
        "label": args.label,
        "engine": args.engine,
        "model": str(args.model),
        "per_language": per_language,
        "aggregate": {
            "measured_languages": [l for l in per_language if l not in missing],
            "blocked_languages": missing,
        },
    }
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"results: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
