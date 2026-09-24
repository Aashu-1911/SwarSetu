# M2M-100 418M 10-Language Matrix

*This matrix maps the direct translation capability of Meta's `facebook/m2m100_418M` model for the 10 languages required by the iTantra project. M2M-100 is a direct many-to-many model, meaning it translates directly between any two supported languages without pivoting through English.*

**Language Codes Used:**
- `hi` (Hindi)
- `gu` (Gujarati)
- `mr` (Marathi)
- `kn` (Kannada)
- `ml` (Malayalam)
- `ta` (Tamil)
- `te` (Telugu)
- `or` (Odia)
- `bn` (Bengali)
- `en` (English)

## Translation Support Matrix

| Source \ Target | `hi` | `gu` | `mr` | `kn` | `ml` | `ta` | `te` | `or` | `bn` | `en` |
|:---|:---|:---|:---|:---|:---|:---|:---|:---|:---|:---|
| **`hi`** | - | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT |
| **`gu`** | DIRECT | - | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT |
| **`mr`** | DIRECT | DIRECT | - | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT |
| **`kn`** | DIRECT | DIRECT | DIRECT | - | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT |
| **`ml`** | DIRECT | DIRECT | DIRECT | DIRECT | - | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT |
| **`ta`** | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | - | DIRECT | DIRECT | DIRECT | DIRECT |
| **`te`** | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | - | DIRECT | DIRECT | DIRECT |
| **`or`** | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | - | DIRECT | DIRECT |
| **`bn`** | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | - | DIRECT |
| **`en`** | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | DIRECT | - |

**Total non-self directions required:** 90
**Total non-self directions supported DIRECTLY by this single model:** 90 [VERIFIED OFFICIAL]
