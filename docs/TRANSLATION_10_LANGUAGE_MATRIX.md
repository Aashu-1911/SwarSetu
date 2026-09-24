# Translation 10-Language Matrix

The SIH PS 26173 Phase 1 requires exactly 10 languages to be fully supported offline.

**Languages:**
Hindi (hi), Gujarati (gu), Marathi (mr), Kannada (kn), Malayalam (ml), Tamil (ta), Telugu (te), Odia (or), Bengali (bn), English (en).

## Current State (Google ML Kit)
Google ML Kit provides on-device neural machine translation, but its coverage of Indic languages is incomplete.

| Source \ Target | HI | GU | MR | KN | ML | TA | TE | OR | BN | EN |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **HI (Hindi)** | - | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ |
| **GU (Gujarati)** | ✅ | - | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ |
| **MR (Marathi)** | ✅ | ✅ | - | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ |
| **KN (Kannada)** | ✅ | ✅ | ✅ | - | ❌ | ✅ | ✅ | ❌ | ✅ | ✅ |
| **ML (Malayalam)**| ❌ | ❌ | ❌ | ❌ | - | ❌ | ❌ | ❌ | ❌ | ❌ |
| **TA (Tamil)** | ✅ | ✅ | ✅ | ✅ | ❌ | - | ✅ | ❌ | ✅ | ✅ |
| **TE (Telugu)** | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | - | ❌ | ✅ | ✅ |
| **OR (Odia)** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | - | ❌ | ❌ |
| **BN (Bengali)** | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | - | ✅ |
| **EN (English)** | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ | - |

*Note: In the current `TranslatorEngine.kt`, Malayalam (`ml`) and Odia (`or`) are illegally aliased to `hi` (Hindi) to prevent crashes, meaning they are completely non-functional.*

## Target State (IndicTrans2 `indic-indic-dist-320M`)
IndicTrans2 provides state-of-the-art translation across 22 Indic languages. However, the `indic-indic` model specifically does **NOT** support English. English is handled by a separate `en-indic` or `indic-en` model.

| Source \ Target | HI | GU | MR | KN | ML | TA | TE | OR | BN | EN |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **HI (Hindi)** | - | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **GU (Gujarati)** | ✅ | - | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **MR (Marathi)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **KN (Kannada)** | ✅ | ✅ | ✅ | - | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **ML (Malayalam)**| ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **TA (Tamil)** | ✅ | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | ✅ | ⚠️ |
| **TE (Telugu)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | ⚠️ |
| **OR (Odia)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - | ✅ | ⚠️ |
| **BN (Bengali)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - | ⚠️ |
| **EN (English)** | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ | - |

*(✅ = Native Support in IndicTrans2, ⚠️ = Requires fallback to ML Kit or a separate model)*

## Integration Strategy: The Hybrid Router
Because the `indic-indic` model lacks English, and to conserve memory, we must implement a Hybrid Router in `TranslatorEngine.kt`:

1. **If Source or Target is English:** Use ML Kit natively.
2. **If Source AND Target are Indic:** Use IndicTrans2.
3. **If Source AND Target are Indic, but IndicTrans2 fails/OOMs:** Fallback to ML Kit pivot (Source -> English -> Target).
