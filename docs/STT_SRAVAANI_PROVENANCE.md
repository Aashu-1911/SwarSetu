# SraVaani 0.5 — Model Provenance (P1)

**Status:** VERIFIED (downloaded + hash-verified 2026-09-15)
**Hub repo:** [`ARTPARK-IISc/SraVaani-0.5-live`](https://huggingface.co/ARTPARK-IISc/SraVaani-0.5-live)
**Pinned revision:** `29a15303a55673367134eee9e1d18cb1a2dcaabf`
**License:** MIT (read from model card metadata via HF API, not inferred)
**Access policy:** repo is gated (`auto`); downloaded with an authorized account token; token never stored in the repository.
**Upstream paper:** VAANI (arXiv 2603.28714) — dataset and model lineage from Project VAANI, ARTPARK-IISc.

## Artifact table (measured 2026-09-15, `scripts/sravaani/verify_manifest.py` exit-0)

| File | Size (bytes) | SHA-256 (full, from manifest.json) | HF blob oid |
|---|---|---|---|
| model-asr-streaming.fp16.ts | 898,139,037 | `d99536a9f75e61ff…` (full hash in scratch/sravaani/manifest.json) | LFS/xet-backed |
| preproc.pt | 135,269 | `8119ff424215f42c…` | LFS/xet-backed |
| tokenizer.model | 325,287 | `0af4086ec53482ac…` | LFS/xet-backed |
| config.json | 870 | `de19b5750e7c54e2…` | plain |
| modeling_sravaani_streaming.py | 26,277 | `5fa4c219405ea0a4…` | plain |
| configuration_sravaani_streaming.py | 2,749 | `a29d100714c98528…` | plain |
| example_streaming.py | 2,661 | `c0f26957aa50fcfa…` | plain |
| README.md | 10,818 | `97548544ed2020eb…` | plain |

Full hashes are stored in `scratch/sravaani/manifest.json` (gitignored — regenerated reproducibly by `scripts/sravaani/download_model.py`).

## Download reproducibility

```bash
HF_TOKEN=hf_xxx python scripts/sravaani/download_model.py   # pinned revision, size+sha256 verified
python scripts/sravaani/verify_manifest.py                   # re-verify before any weight-touching step
```

- Pinned revision prevents silent upstream drift.
- Manifest records `hf_oid` per file so a future re-download can be diffed against the Hub.
- The HF token is used only via environment variable; it is never written to disk inside the repo.

## config.json contents (verbatim facts)

- `model_type`: `sravaani_streaming_tdt`; `architectures`: `["SraVaaniStreamingModel"]`
- `vocab_size`: 5000, `blank_id`: 5000
- `decoder_type`: **ctc** (this "live" checkpoint ships the CTC decode head; TDT fields exist in config defaults but the shipped graph exposes `encoder` + `ctc_decoder` only)
- `enc_d_model`: 1024, `enc_n_layers`: 17, `feat_in`: 128 (mel bins)
- `sample_rate`: 16000, `subsampling_factor`: 8
- `att_context_size`: [70,13]; `supported_att_context_sizes`: [70,13], [70,6], [70,1], [70,0]; `export_att_context_size`: [70,13]
- `chunk_size`: 112, `shift_size`: 112, `pre_encode_cache_size`: 9, `last_channel_cache_size`: 70, `last_time_cache_size`: 8, `min_sampling_frames`: 1

## License / redistribution statement

MIT license permits redistribution, modification, and commercial use with attribution. When bundling model artifacts into the APK/asset packs, the MIT license text and upstream attribution (ARTPARK-IISc, Project VAANI) are shipped alongside the model files. No additional usage restrictions were found on the model card.

## Language coverage (from HF card metadata — to be re-verified by output-script smoke in P6)

Card language tags include all 10 SIH-required languages: `hi, gu, mr, kn, ml, ta, te, or, bn, en`
(plus `as, brx, doi, kok, mai, mni, ne, pa, sa, sat, sd` — 21 total). Single multilingual model; no per-language artifacts.
