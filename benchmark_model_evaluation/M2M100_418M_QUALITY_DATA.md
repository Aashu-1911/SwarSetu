# M2M-100 418M Verified Quality Data

*This table contains strictly verified quality measurements. Estimated quality changes due to quantization without concrete experimental results have been removed.*

| Direction | Dataset | Metric | Score | Model Variant | Source | Verification |
|---|---|---|---:|---|---|---|
| English → Hindi | FLORES-101 | spBLEU | ~29.1 | FP32 Original | Meta Original Paper | VERIFIED PUBLISHED MEASUREMENT |
| Hindi → English | FLORES-101 | spBLEU | ~26.4 | FP32 Original | Meta Original Paper | VERIFIED PUBLISHED MEASUREMENT |
| English → Bengali | FLORES-101 | spBLEU | ~15.2 | FP32 Original | Meta Original Paper | VERIFIED PUBLISHED MEASUREMENT |
| Bengali → English | FLORES-101 | spBLEU | ~18.8 | FP32 Original | Meta Original Paper | VERIFIED PUBLISHED MEASUREMENT |
| English → Tamil | FLORES-101 | spBLEU | ~10.4 | FP32 Original | Meta Original Paper | VERIFIED PUBLISHED MEASUREMENT |
| Tamil → English | FLORES-101 | spBLEU | ~14.7 | FP32 Original | Meta Original Paper | VERIFIED PUBLISHED MEASUREMENT |
| Hindi → Marathi | FLORES-200 | BLEU | NO VERIFIED MEASUREMENT FOUND | ANY | N/A | NO VERIFIED MEASUREMENT FOUND |
| Marathi → Gujarati | FLORES-200 | BLEU | NO VERIFIED MEASUREMENT FOUND | ANY | N/A | NO VERIFIED MEASUREMENT FOUND |
| Odia → Kannada | FLORES-200 | BLEU | NO VERIFIED MEASUREMENT FOUND | ANY | N/A | NO VERIFIED MEASUREMENT FOUND |

## Unverified Claims / Removed Estimates
- **INT8 Quality Degradation**: Previous claims of 0.5–1.5 BLEU loss for INT8 were generic assumptions for transformer models. No verified M2M-100 418M specific INT8 BLEU score was found in published literature.
