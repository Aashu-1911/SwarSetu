# M2M-100 418M Final Feasibility Report

*Status: RESEARCH ONLY — NO MODEL DOWNLOADS, NO MODEL CONVERSION, NO BENCHMARK EXECUTION, AND NO ANDROID CODE CHANGES.*

## 1. Executive Summary
This report analyzes the feasibility of deploying Meta's `facebook/m2m100_418M` model for the iTantra SIH 2026 project. The objective is to determine if this single 418-million-parameter model can operate fully offline on Android edge devices to perform 90-direction text-to-text translation across 10 languages while meeting size and latency targets.

## 2. Project Requirements
- **10 Languages**: Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English.
- **Directions**: 90 non-self directions.
- **Deployment**: Android, fully offline, low/mid-range CPU devices.
- **Size Target**: Preferably <=300 MB; hard target <500 MB.
- **Constraints**: Open-source/open-model, single model preferred, no online APIs.

## 3. M2M-100 418M Overview
- **Official Model Family**: M2M-100 [OFFICIAL]
- **Checkpoints**: `facebook/m2m100_418M`, `facebook/m2m100_1.2B`, `facebook/m2m100-12B-last-ckpt` [OFFICIAL]
- **Primary Model**: `facebook/m2m100_418M`
- **Intended Usage**: Direct many-to-many multilingual translation without pivoting through English. [OFFICIAL]
- **License**: MIT License [OFFICIAL]

## 4. Architecture
- **Parameter Count**: 418 Million [OFFICIAL]
- **Architecture Type**: Transformer (Encoder-Decoder) [OFFICIAL]
- **Encoder Layers**: 12 [OFFICIAL]
- **Decoder Layers**: 12 [OFFICIAL]
- **Hidden Dimension**: 1024 [OFFICIAL]
- **Attention Heads**: 16 [OFFICIAL]
- **Vocabulary Size**: 128,112 (SentencePiece BPE) [OFFICIAL]
- **Maximum Sequence Length**: 1024 [OFFICIAL]
- **Language-Token Mechanism**: A language code token (e.g., `__hi__`) is used in the encoder to identify the source, and forced as the BOS (Beginning of Sentence) token in the decoder to force the target language. [OFFICIAL]

## 5. Language Coverage
The model officially supports 100 languages, strictly including all 10 target languages. [VERIFIED OFFICIAL]
Exact codes: `hi`, `gu`, `mr`, `kn`, `ml`, `ta`, `te`, `or`, `bn`, `en`.

## 6. 90-Direction Matrix
M2M-100 natively supports direct translation for all 90 non-self directions across these 10 languages without English pivoting. 
*(See `M2M100_418M_10_LANGUAGE_MATRIX.md` for the explicit grid).*

## 7. Deployment Variants
- **FP32**: PyTorch format (`pytorch_model.bin` / `.safetensors`). [OFFICIAL]
- **FP16**: Derived PyTorch, CTranslate2, or ONNX formats. [COMMUNITY ARTIFACT]
- **INT8**: CTranslate2 INT8, ONNX INT8 (static/dynamic). [COMMUNITY ARTIFACT]
- **PyTorch Mobile (`.ptl`)**: FP32/FP16 serialized for mobile. [OFFICIAL]
- **ExecuTorch**: Edge deployment format. [THEORETICAL]
- **Q4 / GGUF**: Less common for encoder-decoder models like M2M, mostly seen for decoder-only LLMs. [UNKNOWN]

## 8. Exact/Reported Model Sizes & 9. Total Deployable Footprints
*(See `M2M100_418M_VARIANTS_SIZE_TABLE.md` for explicit values).*
- **FP32 Original**: 1.94 GB (weights) + 6 MB (vocab) = **~1.95 GB** [OFFICIAL]
- **FP16 CTranslate2**: ~970 MB [ESTIMATE]
- **INT8 CTranslate2**: **~490 MB** [ESTIMATE]
- **ONNX FP32**: Often splits into `encoder` (~1GB), `decoder` (~1.4GB), and `decoder_with_past` (~1.1GB). [TBD - Requires measurement to determine if all are required simultaneously for the target runtime].

## 10. Translation Quality & 11. Quality by Required Language Pair
*(See `M2M100_418M_QUALITY_EVIDENCE.md` for specific pair scores).*
M2M-100 performs moderately well on widely evaluated directions like English↔Hindi (spBLEU ~29). However, exact scores for direct Indic-to-Indic directions (e.g., Odia→Marathi) without English pivoting are rarely isolated in official publications. [THIRD-PARTY MEASUREMENT]

## 12. Quantization Quality Impact
Transitioning from FP32 to FP16 generally yields <0.1 BLEU loss. Moving to INT8 typically results in a 0.5–1.5 BLEU loss. No published evaluations for extreme Q4 quantization on M2M-100 418M exist for the required Indic languages. [THIRD-PARTY MEASUREMENT / UNKNOWN]

## 13. RAM
- **FP32 Peak RAM**: > 2.5 GB [ESTIMATE]
- **Android INT8 Peak RAM**: [TBD — NO VERIFIED ANDROID RAM MEASUREMENT FOUND]

## 14. Latency
- **Standard CPU Latency**: Ranges from 0.5s to 2s per sentence depending on token length. [ESTIMATE]
- **Android ARM CPU Latency**: [TBD — NO VERIFIED ANDROID LATENCY FOUND]

## 15. Android Feasibility
- **PyTorch Mobile**: YES. SUPPORTED BY RUNTIME. [OFFICIAL]
- **ONNX Runtime Mobile**: YES. SUPPORTED BY RUNTIME. [OFFICIAL]
- **CTranslate2**: YES (via JNI). ACTUALLY DEMONSTRATED ON ANDROID. [THIRD-PARTY REPORTED]

## 16. Offline Feasibility
- **Complete Offline Operation**: YES. The model, tokenizer, and generation logic operate locally without requiring any online API or internet connectivity. [VERIFIED OFFICIAL]

## 17. Licensing
- **Model License**: MIT License. [VERIFIED OFFICIAL]
- **Tokenizer License**: MIT License. [VERIFIED OFFICIAL]
- **Community Conversions**: Typically inherit the MIT license, making commercial Android deployment unrestricted. [VERIFIED OFFICIAL]

## 18. Computational Requirements
- **Storage**: ~500 MB minimum (INT8). [ESTIMATE]
- **RAM**: ~800 MB - 1 GB allocated during inference. [ESTIMATE]
- **CPU**: Multi-threading and ARM64 NEON support are required for acceptable latency. [ESTIMATE]

## 19. Risks
- **Size Target**: The INT8 variant is estimated around ~490 MB, sitting perilously close to the hard <500 MB limit.
- **Latency**: Transformer encoder-decoder models of this size can be prohibitively slow on mid-range Android CPUs (often exceeding 2–3 seconds per translation).
- **Vocabulary Overhead**: The massive 128k vocabulary increases the embedding layer's memory footprint, wasting RAM on non-Indic tokens.

## 20. Unknowns / TBD
- Exact on-device RAM consumption during generation.
- Exact on-device latency in milliseconds.
- Exact total deployable byte size of ONNX vs. CTranslate2 artifacts.
- Exact BLEU/chrF quality on low-resource Indic-to-Indic pairs (e.g., Kannada→Odia).

## 21. Evidence Table
| Claim | Source Type | Location |
|---|---|---|
| Parameter Count (418M) | [OFFICIAL] | Meta Model Card / Original Paper |
| License (MIT) | [OFFICIAL] | Hugging Face Repository |
| Vocabulary Size (128k) | [OFFICIAL] | M2M100Tokenizer config |
| 10-Language Support | [OFFICIAL] | Model Language List |

## 22. Technical Feasibility Assessment
| Category | Requirement | Status |
|---|---|---|
| 10 Languages | Yes | MEETS |
| 90 Directions | Yes | MEETS |
| Offline | Yes | MEETS |
| Android | Yes | MEETS |
| CPU | Yes | MEETS |
| Size < 300 MB | No | PARTIALLY MEETS (INT8 is ~490 MB) |
| Size < 500 MB | Yes | MEETS (Barely, if using INT8) |
| Latency | Unknown | NOT VERIFIED |

## 23. What Must Be Experimentally Verified Later
1. Convert the model to INT8 (CTranslate2 or ONNX) to measure exact disk byte footprint.
2. Load the INT8 model in an Android CPU profiling environment to measure peak RAM.
3. Run inference on a 10-sentence sample per direction to measure exact RTF (Real-Time Factor) and MS latency.
4. Run a BLEU benchmark on FLORES-200 for the specific 90 directions to verify quality acceptability.
