# M2M-100 418M Variants & Size Table

*This table tracks the exact storage footprint required to deploy various quantization and runtime formats of the `facebook/m2m100_418M` model. Note that deployment architectures using ONNX often require multiple duplicated decoder graphs, significantly increasing the disk footprint compared to the parameter count.*

| Variant | Precision | Format | Source | Components Required | Model Size | Total Deployable Size | Status |
|---|---|---|---|---|---:|---:|---|
| Original | FP32 | PyTorch | [OFFICIAL] | `pytorch_model.bin` (or `.safetensors`), tokenizer files, config | ~1.94 GB | ~1.95 GB | Verified |
| Optimum Export | FP32 | ONNX | [COMMUNITY ARTIFACT] | `encoder_model.onnx`, `decoder_model.onnx`, `decoder_with_past_model.onnx`, tokenizer | [TBD] | [TBD] | [TBD] |
| Quantized | INT8 | ONNX | [COMMUNITY ARTIFACT] | Quantized ONNX encoder and decoders, tokenizer | [TBD] | [TBD] | [TBD] |
| CTranslate2 | FP16 | CT2 | [COMMUNITY ARTIFACT] | `model.bin`, `shared_vocabulary.txt` | [ESTIMATE] ~970 MB | [ESTIMATE] ~975 MB | [TBD] |
| CTranslate2 | INT8 | CT2 | [COMMUNITY ARTIFACT] | `model.bin`, `shared_vocabulary.txt` | [ESTIMATE] ~485 MB | [ESTIMATE] ~490 MB | [TBD] |
| MobileEdge | FP16 | PyTorch Mobile | [OFFICIAL] | `.ptl` (optimized for TorchScript), tokenizer | [TBD] | [TBD] | [TBD] |
