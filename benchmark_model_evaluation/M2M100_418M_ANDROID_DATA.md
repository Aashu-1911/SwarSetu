# M2M-100 418M Verified Android Data

*This table contains strictly verified Android device performance measurements for M2M-100 418M. Generic latency bounds or theoretical RAM footprints based on size have been excluded.*

| Variant | Runtime | Device | RAM | Latency | RTF | Source | Verification |
|---|---|---|---:|---:|---:|---|---|
| FP32 | PyTorch Mobile | Android ARM64 | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | N/A | NO VERIFIED MEASUREMENT FOUND |
| INT8 | ONNX Runtime Mobile | Android ARM64 | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | N/A | NO VERIFIED MEASUREMENT FOUND |
| FP16 | CTranslate2 | Android ARM64 | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | N/A | NO VERIFIED MEASUREMENT FOUND |
| INT8 | CTranslate2 | Android ARM64 | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | NO VERIFIED MEASUREMENT FOUND | N/A | NO VERIFIED MEASUREMENT FOUND |

## Unverified Claims / Removed Estimates
- **RAM Estimates**: Previous reports linking file size directly to inference RAM requirements (e.g., assuming a ~490 MB model takes ~800 MB RAM) have been removed due to lack of a verified experimental source on an Android device.
- **Latency Estimates**: Previous reports estimating 0.5 - 2 seconds per sentence on ARM CPUs were generic unverified bounds and have been excluded.
