# M2M-100 418M Verified Size Data

*This table contains strictly verified artifact sizes and published measurements. Estimates, theoretical compressions, and generic assumptions have been explicitly removed.*

| Variant | Precision | Runtime/Format | Actual Artifact Size | Total Deployable Size | Source | Verification |
|---|---|---|---:|---:|---|---|
| Original | FP32 | PyTorch | `pytorch_model.bin`: ~1.94 GB<br>`vocab.json`: 3.71 MB<br>`sentencepiece.bpe.model`: 2.42 MB | ~1.95 GB | Official Hugging Face repo (`facebook/m2m100_418M`) | VERIFIED ARTIFACT SIZE |
| ONNX Export | INT8 | ONNX (optimum-cli dynamic) | `encoder_model.onnx`: ~287 MB<br>`decoder_model.onnx`: ~470 MB<br>`sentencepiece.bpe.model`: ~2.4 MB | ~759 MB | Community Artifact on Hugging Face | VERIFIED ARTIFACT SIZE |
| CTranslate2 | INT8 | CT2 | NO VERIFIED SIZE FOUND | NO VERIFIED SIZE FOUND | N/A | NO VERIFIED SIZE FOUND |
| CTranslate2 | FP16 | CT2 | NO VERIFIED SIZE FOUND | NO VERIFIED SIZE FOUND | N/A | NO VERIFIED SIZE FOUND |
| MobileEdge | FP16 | PyTorch Mobile | NO VERIFIED SIZE FOUND | NO VERIFIED SIZE FOUND | N/A | NO VERIFIED SIZE FOUND |
| GGUF | Q4 | GGUF | NO VERIFIED SIZE FOUND | NO VERIFIED SIZE FOUND | N/A | NO VERIFIED SIZE FOUND |

## Unverified Claims / Removed Estimates
- **CTranslate2 INT8**: Previously estimated at ≈490 MB based on theoretical 4x compression ratios. Excluded due to lack of a verified exact artifact.
- **ONNX FP32 Total Deployment**: Size of complete encoder + decoder + decoder_with_past graph remains unmeasured locally.
