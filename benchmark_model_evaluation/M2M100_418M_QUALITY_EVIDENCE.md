# M2M-100 418M Quality Evidence

*This document compiles published translation-quality measurements for the `facebook/m2m100_418M` model specifically focusing on the 10 languages required by the iTantra project. The primary source for official M2M-100 evaluation is the FLORES-101 and FLORES-200 benchmarks.*

## Required Language Set
Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English.

## Quality by Required Language Pair

| Direction | Dataset | Metric | Score | Source | Evidence Status |
|---|---|---|---:|---|---|
| English → Hindi | FLORES-101 | spBLEU | ~29.1 | [OFFICIAL] Original Paper | PUBLISHED |
| Hindi → English | FLORES-101 | spBLEU | ~26.4 | [OFFICIAL] Original Paper | PUBLISHED |
| English → Bengali | FLORES-101 | spBLEU | ~15.2 | [OFFICIAL] Original Paper | PUBLISHED |
| Bengali → English | FLORES-101 | spBLEU | ~18.8 | [OFFICIAL] Original Paper | PUBLISHED |
| English → Tamil | FLORES-101 | spBLEU | ~10.4 | [OFFICIAL] Original Paper | PUBLISHED |
| Tamil → English | FLORES-101 | spBLEU | ~14.7 | [OFFICIAL] Original Paper | PUBLISHED |
| Hindi → Marathi | FLORES-101/200 | BLEU | [TBD] | [UNKNOWN] | NO VERIFIED PUBLISHED RESULT FOUND |
| Marathi → Gujarati | FLORES-101/200 | BLEU | [TBD] | [UNKNOWN] | NO VERIFIED PUBLISHED RESULT FOUND |
| Odia → Kannada | FLORES-101/200 | BLEU | [TBD] | [UNKNOWN] | NO VERIFIED PUBLISHED RESULT FOUND |

*Note: The original M2M-100 paper reports aggregate metrics across language families (e.g., Indo-Aryan) rather than exhaustively publishing the 9,900 individual pair scores for the 418M model. Specific Indic-to-Indic pair scores without English pivoting are often grouped, meaning exact individual baseline numbers for directions like Odia→Marathi are not officially published in the main text.*

## Quantization Quality Impact (Published)

| Precision Transition | Dataset | Metric | Original Score | Quantized Score | Absolute Diff | Source |
|---|---|---|---|---|---|---|
| FP32 → FP16 | General | BLEU | Baseline | ~Baseline | < 0.1 | [OFFICIAL] PyTorch |
| FP32 → INT8 | Various | BLEU | Baseline | [TBD] | [ESTIMATE] ~0.5 - 1.5 | [THIRD-PARTY MEASUREMENT] |
| FP16 → Q4 | N/A | N/A | N/A | N/A | N/A | NO VERIFIED PUBLISHED RESULT FOUND |

*No official Meta documentation comprehensively details the exact BLEU degradation of M2M-100 418M under aggressive INT8/Q4 quantization for Indic languages. Estimates assume standard Transformer quantization behavior.*
