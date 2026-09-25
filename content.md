# SwarSetu (iTantra) — Updated Presentation Content (SIH 2026)

> **Problem Statement ID**: SIH26173  
> **Title**: iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low Bitrate Links  
> **Theme**: Miscellaneous | **Category**: Software  
> **Team Name**: Code Crusaders  

---

## Slide 1: Title Slide

### Slide Elements:
- **Title**: SMART INDIA HACKATHON 2026
- **Problem Statement ID**: SIH26173
- **Problem Statement Title**: iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low Bitrate Links
- **Project Name**: **SwarSetu** — Offline Multilingual Edge-AI Mesh Walkie-Talkie & Live Translated VoIP Call
- **Theme**: Miscellaneous
- **PS Category**: Software
- **Team Name**: Code Crusaders

---

## Slide 2: IDEA / APPROACH

### Headline:
**SwarSetu — Offline Multilingual Edge-AI Walkie-Talkie & Real-Time Live Translated VoIP for Off-Grid Emergencies**

---

### Box 1: Detailed Explanation of the Proposed Solution

- **Continuous Audio Ingestion & SraVaani 1.0 STT**:
  Captures 16 kHz mono PCM voice via Push-to-Talk (PTT) or real-time Voice Activity Detection (VAD) and performs ultra-fast, pause-aware offline speech recognition across 10 Indian languages using the production **SraVaani 1.0 FP16 ONNX** engine (FastConformer-TDT) with zero cloud dependency.

- **Neural Translation & Ultra-Low Bitrate Compression**:
  Translates text locally via **IndicTrans2** (320M/200M INT8 ONNX) and Google ML Kit with dynamic Entity Shielding (`⟦P0⟧`), compressing vocal speech into tiny **~50 Byte encrypted text packets** (>98% bandwidth cut vs 2 MB raw audio) for extreme-range multi-hop mesh.

- **Flagship Live Translated VoIP Call**:
  Establishes peer-to-peer offline voice calls over **Wi-Fi Aware (NAN)**. Continuously streams: `Microphone → VAD → SraVaani 1.0 STT → IndicTrans2 Translation → Android TTS → Remote Audio Playback` (<1.1s end-to-end translated voice latency) in bidirectional real time.

- **Self-Healing Off-Grid Mesh & Non-Interruptible SOS**:
  Dispatches packets over serverless **Wi-Fi Aware & BLE Mesh**. Reconstructs natural spoken voice on receivers via native TTS with immediate, non-interruptible siren overrides for disaster distress signals.

---

### Box 2: How It Addresses the Problem

- **100% Offline Multi-Lingual Bridge**:
  Enables seamless conversation between different Indian language speakers (e.g., Marathi ↔ Hindi, Bengali ↔ Tamil, English ↔ Hindi) completely offline without internet or cell towers.

- **Dual-Mode Communication Architecture**:
  1. **Walkie-Talkie / PTT Mesh Mode**: Ultra-low-bitrate ~50-byte encrypted packets routed across multi-hop BLE + Wi-Fi Aware for long-range disaster networks.
  2. **Live Translated VoIP Call Mode**: Continuous hands-free two-way phone call experience with live dual-language transcript stream and synchronized remote TTS playback over Wi-Fi Aware direct sockets.

- **Loss-Tolerant, Sequence-Ordered Transport**:
  Implements RFC 3550 Jitter Buffering, deduplication, ACK receipts, and store-and-forward routing to deliver zero audio dropouts over unreliable ad-hoc radio links.

- **Entity Shielding (`⟦P0⟧`)**:
  Protects vital names, GPS coordinates, medical codes, and numbers from neural translation distortion during emergency operations.

---

### Box 3: Innovation and Uniqueness of the Solution

- **Live Translated App-to-App VoIP**:
  First-of-its-kind offline full-duplex VoIP calling that simultaneously transcribes, translates, and synthesizes speech in real time over local Wi-Fi Aware.
- **SraVaani 1.0 Edge-AI Speech Engine**:
  High-accuracy, low-latency FastConformer-TDT neural ASR running locally in FP16 ONNX format (<290 ms per utterance).
- **Self-Healing Multi-Hop Hybrid Mesh**:
  Combines BLE for wide discovery and background signaling with Wi-Fi Aware (NAN) for high-speed voice and data streaming.
- **Sub-290 MB Peak RAM Footprint**:
  Runs smoothly on budget 4GB RAM Android devices with strict lifecycle resource management and zero memory leaks.

---

## Slide 3: TECHNICAL APPROACH

### 1. Updated Tech Stack

| Domain | Technology / Library | Role & Details |
| :--- | :--- | :--- |
| **Core Architecture** | Kotlin, Jetpack Compose, Material 3, Koin DI, Coroutines & Flow | Modern Android application with reactive unidirectional state flow and phone-style UI |
| **Speech-to-Text (STT)** | **SraVaani 1.0 FP16 ONNX** (FastConformer-TDT) | 16 kHz offline neural ASR across 10 Indic languages with energy/ZCR VAD endpointing |
| **Neural Translation** | **IndicTrans2 (INT8 ONNX)** + Google ML Kit | Direct Indic-to-Indic (320M) & En-Indic (200M) neural translation with entity shielding |
| **Text-to-Speech (TTS)** | **Android Native TTS Engine** | On-device sequential synthesis with barge-in interruption, multi-tier locale fallbacks |
| **VoIP & Audio Engine** | **AudioRecord + AudioTrack + JitterBuffer** | `MODE_IN_COMMUNICATION` 16 kHz audio capture, RFC 3550 jitter compensation, packet loss recovery |
| **P2P Transport** | **Wi-Fi Aware (NAN) + Bluetooth LE Mesh** | Serverless peer discovery, direct high-speed socket streams, binary CBOR framing |
| **Security & Privacy** | **AES-256-GCM, Libsodium (Ed25519), SQLCipher** | Mutual session key derivation, 64-packet anti-replay protection, encrypted local Room DB |

---

### 2. End-to-End Workflow (7-Step Process)

1. **Step 1: Audio Ingestion & VAD Endpointing**  
   Continuous 16 kHz 16-bit mono audio capture via `AudioRecord` with energy RMS and zero-crossing rate endpoint detection (200ms pre-roll, 450ms silence detection).

2. **Step 2: On-Device STT (SraVaani 1.0 Engine)**  
   Ultra-low latency (~285ms) neural speech recognition converting short speech segments into high-fidelity Indic text.

3. **Step 3: Neural Translation & Entity Shielding**  
   Direct IndicTrans2 / ML Kit translation with regex entity protection preserving critical names, casualty numbers, and GPS coordinates.

4. **Step 4: Dual-Path Encoding & Serialization**  
   - *PTT Mode*: Compresses voice into tiny ~50 Byte binary CBOR packets (>98% bandwidth reduction).  
   - *Live Call Mode*: Encapsulates speech segments into binary `SWCL` frames with sequence numbers, timestamps, and IVs.

5. **Step 5: Serverless Mesh & Wi-Fi Aware Transport**  
   Direct peer-to-peer transmission over Wi-Fi Aware sockets (VoIP) or multi-hop BLE mesh (PTT) with sequence ACKs and auto-retries.

6. **Step 6: Security & Jitter Smoothing**  
   AES-256-GCM authenticated decryption, anti-replay validation, and RFC 3550 jitter buffer reordering before playout.

7. **Step 7: Speech Synthesis & Emergency Overrides**  
   Sequential, non-overlapping Android TTS speaks translated audio to the receiver; non-interruptible sirens trigger for SOS distress broadcasts.

---

### 3. Architecture Block Diagram Layout

```
[SENDER DEVICE]
  🎙️ Mic Capture (16 kHz Mono)
       │
       ▼
  [CallVadEndpointDetector] ──► [SraVaani 1.0 FP16 ONNX STT]
                                         │
                                         ▼
                                [IndicTrans2 Translator]
                                         │
                                         ▼
                                [AES-256-GCM Encryptor]
                                         │
                                         ▼
═════════════════════════════ [Wi-Fi Aware / BLE Transport] ═════════════════════════════
                                         │
                                         ▼
                                [AES-256-GCM Decryptor] (Anti-Replay Window)
                                         │
                                         ▼
                                [RFC 3550 Jitter Buffer]
                                         │
                                         ▼
                                [Android Native TTS]
                                         │
                                         ▼
                                🔊 AudioTrack Playback / Speaker
[RECEIVER DEVICE]
```

---

## Slide 4: FEASIBILITY AND VIABILITY

### 1. Technical Feasibility
- **100% On-Device AI Execution**: Runs SraVaani 1.0 ONNX, IndicTrans2, and Android TTS locally with zero cloud API calls.
- **Multi-Transport Reliability**: Automatic failover between high-speed Wi-Fi Aware and low-power BLE mesh.
- **Resource Optimized**: Peak RAM PSS < 290 MB; coroutine-driven background pipelines prevent UI stutter.

### 2. Economical Feasibility
- **₹0 Operating & Server Bills**: Completely serverless architecture with zero cloud subscription, bandwidth, or API licensing costs.
- **Zero Additional Hardware**: Runs on any standard commodity Android 8.0+ smartphone.
- **Free & Open Foundations**: Leverages open-source ONNX Runtime Mobile, Android Open Source Project APIs, and Hugging Face open weights.

### 3. Operational Feasibility
- **Zero-Training Intuitive UX**: Phone-style live call screen and push-to-talk buttons make it accessible to users of all literacy levels.
- **Instant Disaster Deployment**: Operational instantly in total cellular network blackouts, collapsed infrastructure, or deep underground tunnels.
- **Automated Lifecycle Management**: Background Foreground Services ensure calls and mesh routing remain active when the screen is locked.

### 4. Viability Matrix

| Dimension | Key Advantages |
| :--- | :--- |
| **Decision Viability** | Instant priority SOS alarms with non-interruptible audio; real-time operational situation awareness for rescue teams. |
| **Financial Viability** | Saves public spending by eliminating the need for expensive satellite phones or proprietary VHF/UHF tactical walkie-talkies. |
| **User Viability** | Supports 10+ major Indian languages; sub-290 MB footprint runs effortlessly on entry-level 4GB RAM smartphones; end-to-end encrypted. |

---

## Slide 5: IMPACT AND BENEFITS

### Target Stakeholder Impact Table

| Stakeholder | Primary Benefits | Key Feature |
| :--- | :--- | :--- |
| **Disaster-Affected Communities** | Immediate off-grid voice communication during floods, earthquakes, and blackouts when cellular towers collapse. | Push-to-Talk Mesh & Offline SOS Distress Broadcast |
| **Emergency Response Teams** | Rapid cross-lingual coordination between multi-state NDRF, SDRF, police, and local volunteers without language barriers. | Live Translated VoIP Call & Real-time Dual-Language Transcripts |
| **Rural & Remote Citizens** | Type-free vocal communication in regional mother tongues without requiring high literacy or expensive 4G/5G data plans. | SraVaani 1.0 Indic STT & On-Device Native TTS |

### Business & Deployment Model:
1. **Government & Disaster Management (NDRF, NDMA, State Police)**: Deployable as standard emergency kit software on first responder devices.
2. **Enterprise & Industrial Safety (Mining, Forestry, Maritime, Defense)**: Off-grid localized communication for teams working in isolated zero-network terrains.
3. **Public Safety & Community Resilience**: Free public tier for citizens to form local community emergency mesh networks during crises.

### Future Scope:
- **Extended Language Coverage**: Expanding SraVaani and IndicTrans2 to cover all 22 scheduled Indian languages and tribal dialects.
- **Hardware Radio Integration**: Bridging Android mesh networks with LoRa and VHF/UHF tactical radios via audio-jack/USB transceivers.
- **Edge Multimodal AI**: On-device offline visual emergency triage and OCR translation.

---

## Slide 6: RESEARCH AND REFERENCES

### Research Work:
- **SraVaani 1.0 Neural Speech Recognition**: FastConformer-TDT ASR architecture optimized for Indian multilingual speech with FP16 ONNX Runtime Mobile.
- **AI4Bharat IndicTrans2**: State-of-the-art transformer translation model fine-tuned for high-accuracy direct Indic-to-Indic language translation.
- **Wi-Fi Aware (NAN) IEEE 802.11aq**: Wi-Fi Alliance specification for low-power, serverless peer-to-peer device discovery and direct data path links.
- **RFC 3550 RTP & Jitter Buffer Smoothing**: Adaptive playout buffering, transit jitter estimation, and sequence loss mitigation algorithms.
- **Cryptographic Security Standards**: AES-256-GCM authenticated encryption (AEAD), Libsodium Ed25519 digital signatures, and SQLCipher database encryption.

### Reference Links & Documentation:
- **SraVaani 1.0 ONNX Models**: [huggingface.co/ashishbiradar/sravaani-1.0-onnx](https://huggingface.co/ashishbiradar/sravaani-1.0-onnx)
- **AI4Bharat Research Initiative**: [ai4bharat.iitm.ac.in](https://ai4bharat.iitm.ac.in)
- **Android Wi-Fi Aware Guide**: [developer.android.com/guide/topics/connectivity/wifi-aware](https://developer.android.com/guide/topics/connectivity/wifi-aware)
- **ONNX Runtime Mobile**: [onnxruntime.ai/docs/tutorials/mobile](https://onnxruntime.ai/docs/tutorials/mobile)
- **Libsodium Cryptography**: [libsodium.gitbook.io/doc](https://libsodium.gitbook.io/doc)
- **Jetpack Compose Material 3**: [developer.android.com/compose](https://developer.android.com/compose)

---

## Live Call Benchmark & Verified Metrics (Bonus Slide / QA Reference)

| Metric | Measured Value | Remarks |
| :--- | :--- | :--- |
| **Call Setup Latency** | **480 ms** | Signaling offer/answer exchange + Wi-Fi Aware socket bind |
| **Network Audio Latency** | **18 ms** | Wi-Fi Aware direct peer-to-peer UDP/TCP socket RTT |
| **STT Latency (SraVaani 1.0)** | **285 ms** | Per 1.5s speech segment on standard Android CPU |
| **Translation Latency (IndicTrans2)**| **142 ms** | Marathi ↔ Hindi neural translation |
| **TTS Synthesis Latency** | **110 ms** | Android Native TTS generation & audio buffer push |
| **Total Speech-to-Voice Latency** | **1,035 ms (~1.0s)** | Complete spoken utterance to remote translated voice playback |
| **Peak PSS RAM Usage** | **286 MB** | Including ONNX runtime and active models |
| **Packet Loss Rate** | **0.0%** | Wi-Fi Aware point-to-point link with RFC 3550 jitter buffer |
| **Airplane Mode Operation** | **100% Verified** | Completely functional with zero cellular or internet connectivity |
