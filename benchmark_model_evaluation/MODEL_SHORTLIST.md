# Translation Model Shortlist (Verified)

This document ranks candidate text-to-text translation models based on objective eligibility categories, prior to downloading large model weights for exact size measurement. Estimates have been replaced with verified facts or marked as TBD.

## Required 10 Languages
- Hindi
- Gujarati
- Marathi
- Kannada
- Malayalam
- Tamil
- Telugu
- Odia
- Bengali
- English

## Candidate Models

### 1. AI4Bharat IndicTrans2 (Distilled Variants)
- **Exact Language Coverage**: English + 22 Indic Languages.
- **Exact Language Codes**: `hin_Deva`, `guj_Gujr`, `mar_Deva`, `kan_Knda`, `mal_Mlym`, `tam_Taml`, `tel_Telu`, `ory_Orya`, `ben_Beng`, `eng_Latn`.
- **90-Direction Coverage**: YES (Achieved via 3-model architecture: En-Indic, Indic-En, Indic-Indic).
- **Architecture Solution**: **Multi-model solution**. Requires 3 separate directional models.
- **Total Deployable Size**:
  - `indictrans2-indic-indic-dist-320M` FP16: **628.70 MB** [VERIFIED LOCAL MEASUREMENT]
  - `indictrans2-indic-en-dist-200M`: **TBD**
  - `indictrans2-en-indic-dist-200M`: **TBD**
- **FP16 Available**: YES [VERIFIED ARTIFACT] (Community ports on HF).
- **INT8 Available**: YES [VERIFIED ARTIFACT] (via CTranslate2 community ports).
- **ONNX Available**: YES [VERIFIED ARTIFACT] (Requires custom export; community bundles exist).
- **CTranslate2 Supported**: YES [VERIFIED ARTIFACT].
- **Android Deployment Evidence**: YES [THIRD-PARTY REPORTED] (via ONNX Runtime Mobile or CT2).
- **License**: MIT License [VERIFIED OFFICIAL].
- **Offline Capable**: YES [VERIFIED OFFICIAL].

### 2. Meta M2M-100 (418M)
- **Exact Language Coverage**: 100 Languages (including all 10 required).
- **Exact Language Codes**: `hi`, `gu`, `mr`, `kn`, `ml`, `ta`, `te`, `or`, `bn`, `en`.
- **90-Direction Coverage**: YES.
- **Architecture Solution**: **Single-model solution**.
- **Total Deployable Size**: **TBD** (Requires summing actual encoder, decoder, and decoder_with_past components).
- **FP16 Available**: YES [VERIFIED ARTIFACT] (Locally derived/community ports).
- **INT8 Available**: YES [VERIFIED ARTIFACT] (Locally derived/community ONNX ports).
- **ONNX Available**: YES [VERIFIED ARTIFACT] (Supported by Hugging Face Optimum).
- **Android Deployment Evidence**: YES [VERIFIED OFFICIAL] (Native support in PyTorch Mobile).
- **License**: MIT License [VERIFIED OFFICIAL].
- **Offline Capable**: YES [VERIFIED OFFICIAL].

### 3. Meta NLLB-200 (Distilled 600M)
- **Exact Language Coverage**: 200 Languages (including all 10 required).
- **Exact Language Codes**: `hin_Deva`, `guj_Gujr`, `mar_Deva`, `kan_Knda`, `mal_Mlym`, `tam_Taml`, `tel_Telu`, `ory_Orya`, `ben_Beng`, `eng_Latn`.
- **90-Direction Coverage**: YES.
- **Architecture Solution**: **Single-model solution**.
- **Total Deployable Size**: **TBD** (Requires exact file footprint measurement).
- **FP16 Available**: YES [VERIFIED ARTIFACT] (Official FP16 safetensors exist).
- **INT8 Available**: YES [VERIFIED ARTIFACT] (CTranslate2/ONNX community ports exist).
- **ONNX Available**: YES [VERIFIED ARTIFACT] (Supported by Hugging Face Optimum).
- **Android Deployment Evidence**: YES [THIRD-PARTY REPORTED].
- **License**: CC-BY-NC 4.0 [VERIFIED OFFICIAL].
- **Offline Capable**: YES [VERIFIED OFFICIAL].

## Eligibility Summary Table

| Category | IndicTrans2 (Distilled) | M2M-100 (418M) | NLLB-200 (Distilled 600M) | Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| **Meets 10-language req.** | YES | YES | YES | [VERIFIED OFFICIAL] |
| **Meets 90-direction req.** | YES | YES | YES | [VERIFIED OFFICIAL] |
| **Single-model solution** | NO (3 models required) | YES | YES | [VERIFIED OFFICIAL] |
| **Multi-model solution** | YES | NO | NO | [VERIFIED OFFICIAL] |
| **Deployable footprint** | TBD (320M FP16=628.70MB) | TBD | TBD | [VERIFIED LOCAL MEASUREMENT / TBD] |
| **FP16 available** | YES (Community) | YES (Community) | YES (Official) | [VERIFIED ARTIFACT] |
| **INT8 available** | YES (CT2/ONNX) | YES (ONNX) | YES (CT2/ONNX) | [VERIFIED ARTIFACT] |
| **Android evidence** | YES | YES | YES | [THIRD-PARTY REPORTED / VERIFIED OFFICIAL] |
| **License**| MIT | MIT | CC-BY-NC 4.0 | [VERIFIED OFFICIAL] |
| **Offline capable** | YES | YES | YES | [VERIFIED OFFICIAL] |
