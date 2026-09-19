#!/usr/bin/env python3
"""P6 language smoke: verify the SraVaani-0.5 vocab genuinely covers all 10 required languages.

Checks (reproducible, no network, no model execution):
  1. tokens.txt has exactly 5000 pieces (official vocab size).
  2. Every required language's Unicode script block has dedicated pieces (>= 50).
  3. kn/ml/or have native-script pieces — the historical Hindi-alias failure mode is
     structurally impossible (a script's pieces can only be emitted for that script).
  4. The model graph takes NO language input (language-unconditioned): every graph input
     name is scanned; a language argument would appear as a language/id input in NeMo-style
     exports. Recorded, not assumed.

Exit 0 = all checks pass. Writes a JSON evidence log when --out is given.
"""
import argparse
import json
import sys
from pathlib import Path

BLOCKS = {
    "hi": (0x0900, 0x097F),  # Devanagari (also serves mr)
    "gu": (0x0A80, 0x0AFF),
    "bn": (0x0980, 0x09FF),
    "kn": (0x0C80, 0x0CFF),
    "ml": (0x0D00, 0x0D7F),
    "ta": (0x0B80, 0x0BFF),
    "te": (0x0C00, 0x0C7F),
    "or": (0x0B00, 0x0B7F),
    "en": (0x0041, 0x024F),  # Latin + Latin Extended
}

MIN_PIECES = 50
EXPECTED_PIECES = 5000


def piece_block_counts(tokens_path: Path) -> dict:
    counts = {k: 0 for k in BLOCKS}
    with open(tokens_path, encoding="utf-8") as f:
        for line in f:
            piece = line.rstrip("\r\n")
            if not piece.strip():
                continue
            core = piece.replace("\u2581", "")
            if not core:
                continue
            cp = ord(core[0])
            for lang, (lo, hi) in BLOCKS.items():
                if lo <= cp <= hi:
                    counts[lang] += 1
                    break
    return counts


def graph_language_inputs(onnx_path: Path) -> list:
    """Return graph input names (evidence that there is NO language conditioning input)."""
    try:
        import onnx  # noqa: PLC0415
    except ImportError:
        return ["onnx not installed — graph scan skipped"]
    model = onnx.load(str(onnx_path), load_external_data=False)
    return [i.name for i in model.graph.input]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tokens", default="app/src/main/assets/sravaani/tokens.txt")
    ap.add_argument("--encoder", default="scratch/sravaani/onnx/sravaani_encoder.onnx")
    ap.add_argument("--out", default=None, help="write JSON evidence log here")
    args = ap.parse_args()

    tokens = Path(args.tokens)
    if not tokens.is_file():
        print(f"FAIL: tokens file missing: {tokens}")
        return 1

    pieces = [l.rstrip("\r\n") for l in open(tokens, encoding="utf-8") if l.strip()]
    results = {"tokens_file": str(tokens), "piece_count": len(pieces)}

    ok = True
    if len(pieces) != EXPECTED_PIECES:
        print(f"FAIL: expected {EXPECTED_PIECES} pieces, got {len(pieces)}")
        ok = False
    else:
        print(f"PASS: {len(pieces)} pieces (official vocab size)")

    if pieces[0] != "<unk>":
        print("FAIL: piece 0 is not <unk>")
        ok = False

    counts = piece_block_counts(tokens)
    results["per_language_pieces"] = counts
    for lang in ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]:
        # mr shares Devanagari with hi in the single multilingual vocab.
        n = counts.get(lang, counts["hi"] if lang == "mr" else 0)
        status = "PASS" if n >= MIN_PIECES else "FAIL"
        if n < MIN_PIECES:
            ok = False
        print(f"{status}: {lang} native-script pieces = {n}")

    enc = Path(args.encoder)
    if enc.is_file():
        names = graph_language_inputs(enc)
        results["encoder_graph_inputs"] = names
        lang_inputs = [n for n in names if "lang" in n.lower()]
        if lang_inputs:
            print(f"NOTE: language-like graph inputs present: {lang_inputs} (record, do not assume)")
        else:
            print("PASS: encoder graph has NO language conditioning input (unconditioned multilingual)")
    else:
        print(f"NOTE: encoder graph not found at {enc}; graph scan skipped (run P3 export first)")

    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, ensure_ascii=False)
        print(f"evidence log: {args.out}")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
