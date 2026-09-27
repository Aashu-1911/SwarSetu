package app.swarsetu.stt.sravaani

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.swarsetu.stt.SttConfig
import app.swarsetu.stt.SttEngine
import app.swarsetu.stt.SttException
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttLiveStream
import app.swarsetu.stt.SttModelInfo
import app.swarsetu.stt.SttModelManager
import app.swarsetu.stt.SttResult
import app.swarsetu.stt.SttResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * SraVaani 1.0 Production Speech-to-Text Engine.
 *
 * Architecture:
 * - 16 kHz Mono AudioCapture
 * - [SravaaniFeaturizer]: 128-dim Log-Mel Spectrogram (mean/std normalized)
 * - [SravaaniOnnxEncoder]: FastConformer FP16 ONNX encoder (`feats` [B, 128, T] -> `enc_out` [B, 1024, T'])
 * - [SravaaniTdtDecoder]: Token-and-Duration Transducer (TDT) decoder_joint ONNX graph
 * - [SravaaniTokenizer]: 5,000-piece SentencePiece tokenizer with 10 Indic script masks
 * - [SttModelManager]: Model lifecycle manager with atomic download and SHA-256 verification
 */
class Sravaani10Engine(
    private val context: Context,
    private val modelManager: SttModelManager,
    private val executionProvider: String = "CPU",
) : SttEngine {

    private val mutex = Mutex()
    private var _config: SttConfig? = null
    private var _currentLanguage: SttLanguage? = null
    private var _isReady: Boolean = false

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var tables: SravaaniTables? = null
    private var tokenizer: SravaaniTokenizer? = null
    private var featurizer: SravaaniFeaturizer? = null
    private var encoder: SravaaniOnnxEncoder? = null
    private var decoder: SravaaniTdtDecoder? = null

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    override val config: SttConfig? get() = _config
    override val isReady: Boolean get() = _isReady
    override val currentLanguage: SttLanguage? get() = _currentLanguage

    override suspend fun initialize(config: SttConfig): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_isReady && _config?.language == config.language) return@withLock

            _config = config
            _currentLanguage = config.language

            loadTablesAndTokenizer()
            ensureSessionsLoaded()

            _isReady = true
            modelManager.markLoaded(
                SttModelInfo(
                    language = config.language,
                    modelPath = modelManager.getModelDir("1.0").absolutePath,
                    available = true,
                    description = "SraVaani 1.0 Multilingual (${config.language.englishName})",
                ),
            )
            Log.d(TAG, "Initialized SraVaani 1.0 for ${config.language.code} (${config.language.englishName})")
        }
    }

    override suspend fun setLanguage(language: SttLanguage): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            _currentLanguage = language
            _config = _config?.copy(language = language) ?: SttConfig(language = language)
            Log.d(TAG, "Language set to ${language.code} (${language.englishName})")
        }
    }

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isReady) return@withLock
            Log.d(TAG, "Warming up SraVaani 1.0 (tables, tokenizer, ONNX sessions)...")
            loadTablesAndTokenizer()
            ensureSessionsLoaded()
            Log.d(TAG, "SraVaani 1.0 warmUp complete and ready")
        }
    }

    override suspend fun transcribe(pcm: ShortArray, language: SttLanguage): SttResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (pcm.isEmpty()) {
                Log.d(TAG, "transcribe called with empty PCM buffer")
                return@withContext SttResult.empty(language)
            }
            if (!_isReady) {
                Log.e(TAG, "transcribe called but engine not ready (_isReady=false)")
                throw SttException.EngineNotReady("engine never initialized or already released")
            }

            val t0 = System.currentTimeMillis()
            val durationMs = pcm.size * 1000L / SttLanguage.SAMPLE_RATE
            Log.d(TAG, "transcribe: ${pcm.size} samples (${durationMs}ms) for ${language.code} (${language.englishName})")

            loadTablesAndTokenizer()
            ensureSessionsLoaded()

            val curFeaturizer = featurizer ?: throw SttException.EngineNotReady("Featurizer not initialized")
            val curEncoder = encoder ?: throw SttException.EngineNotReady("Encoder not initialized")
            val curDecoder = decoder ?: throw SttException.EngineNotReady("Decoder not initialized")
            val curTokenizer = tokenizer ?: throw SttException.EngineNotReady("Tokenizer not initialized")

            // 1. Featurize PCM to 128-dim Log-Mel Spectrogram
            val tFeat0 = System.currentTimeMillis()
            val utteranceFeats = curFeaturizer.extractUtterance(pcm)
            val tFeat = System.currentTimeMillis() - tFeat0
            Log.d(TAG, "Featurization: ${utteranceFeats.numFrames} frames extracted in ${tFeat}ms")
            if (utteranceFeats.numFrames <= 0) {
                Log.w(TAG, "Featurization produced 0 frames")
                return@withContext SttResult.empty(language)
            }

            // 2. Run FastConformer ONNX Encoder
            val tEnc0 = System.currentTimeMillis()
            val encOut = curEncoder.encode(utteranceFeats.feats, utteranceFeats.numFrames)
            val tEnc = System.currentTimeMillis() - tEnc0
            Log.d(TAG, "Encoder: ${encOut.numFrames} acoustic frames in ${tEnc}ms")
            if (encOut.numFrames <= 0) {
                Log.w(TAG, "Encoder produced 0 frames")
                return@withContext SttResult.empty(language)
            }

            // 3. Run TDT Greedy Decoder with Language Script Mask
            val tDec0 = System.currentTimeMillis()
            val mask = curTokenizer.getAllowedMask(language)
            val tokenIds = curDecoder.decode(encOut, mask)
            val tDec = System.currentTimeMillis() - tDec0
            Log.d(TAG, "Decoder: ${tokenIds.size} tokens in ${tDec}ms: $tokenIds")

            // 4. Decode Token IDs to text
            val text = curTokenizer.decode(tokenIds)
            val totalTime = System.currentTimeMillis() - t0
            Log.i(TAG, "Transcribed [${language.code}]: '$text' (total ${totalTime}ms)")

            val confidence = if (text.isNotBlank()) 0.95f else 0.0f

            return@withContext SttResult(
                text = text,
                language = language,
                confidence = confidence,
                durationMs = durationMs,
                type = SttResultType.FINAL,
            )
        }
    }

    override fun transcribeStream(pcm: ShortArray, language: SttLanguage): Flow<SttResult> = flow {
        val result = transcribe(pcm, language)
        emit(result)
    }

    override fun startLiveStream(language: SttLanguage): SttLiveStream {
        return Sravaani10LiveStream(this, language)
    }

    override suspend fun release(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            _isReady = false
            _currentLanguage?.let { modelManager.markReleased(it) }

            encoder?.close()
            encoder = null

            decoder?.close()
            decoder = null

            try {
                encoderSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing encoder session: ${e.message}")
            }
            encoderSession = null

            try {
                decoderSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing decoder session: ${e.message}")
            }
            decoderSession = null

            featurizer = null
            Log.d(TAG, "SraVaani 1.0 engine released")
        }
    }

    private fun loadTablesAndTokenizer() {
        if (tables != null && tokenizer != null && featurizer != null) return

        val dir10 = modelManager.getModelDir("1.0")

        // 1. Load tables.bin
        if (tables == null) {
            val stream: InputStream = try {
                val extracted = File(dir10, SttModelManager.SravaaniGraphNames.TABLES)
                if (extracted.isFile) {
                    extracted.inputStream()
                } else {
                    context.assets.open("sravaani/tables.bin")
                }
            } catch (e: Exception) {
                throw SttException.ModelLoadError("Cannot load tables.bin: ${e.message}")
            }
            tables = stream.use { SravaaniTables.read(it) }
        }

        // 2. Load tokens.txt
        if (tokenizer == null) {
            val stream: InputStream = try {
                val extracted = File(dir10, SttModelManager.SravaaniGraphNames.TOKENS)
                if (extracted.isFile) {
                    extracted.inputStream()
                } else {
                    context.assets.open("sravaani/tokens.txt")
                }
            } catch (e: Exception) {
                throw SttException.ModelLoadError("Cannot load tokens.txt: ${e.message}")
            }
            val lines = stream.bufferedReader().useLines { it.toList() }
            tokenizer = SravaaniTokenizer(lines)
        }

        val tbl = tables
        if (featurizer == null && tbl != null) {
            featurizer = SravaaniFeaturizer(tbl)
        }
    }

    private fun ensureSessionsLoaded() {
        if (encoder != null && decoder != null) return

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(1)
            if (executionProvider.equals("NNAPI", ignoreCase = true)) {
                try {
                    addNnapi()
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI provider unavailable, falling back to CPU: ${e.message}")
                }
            }
        }

        val encFile = modelManager.getEncoderFile("1.0")
        val decFile = modelManager.getDecoderJointFile("1.0")

        if (!encFile.isFile || !decFile.isFile) {
            val available = modelManager.isSraVaaniAvailable("1.0")
            if (!available) {
                throw SttException.ModelLoadError("SraVaani 1.0 model files not available in ${modelManager.getModelDir("1.0").path}")
            }
            extractPackagedAssetIfMissing(SttModelManager.SravaaniGraphNames.ENCODER, encFile)
            extractPackagedAssetIfMissing(SttModelManager.SravaaniGraphNames.DECODER_JOINT, decFile)
        }

        if (!encFile.isFile || !decFile.isFile) {
            throw SttException.ModelLoadError("Model files missing: enc=${encFile.path} (exists=${encFile.isFile}), dec=${decFile.path} (exists=${decFile.isFile})")
        }

        val encSess = env.createSession(encFile.absolutePath, opts)
        val decSess = env.createSession(decFile.absolutePath, opts)

        encoderSession = encSess
        decoderSession = decSess

        encoder = SravaaniOnnxEncoder(env, encSess)
        decoder = SravaaniTdtDecoder(env, decSess)
    }

    private fun extractPackagedAssetIfMissing(assetName: String, targetFile: File) {
        if (targetFile.isFile && targetFile.length() > 0) return
        try {
            val list = context.assets.list("sravaani").orEmpty()
            if (!list.contains(assetName)) return
            val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
            context.assets.open("sravaani/$assetName").use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tmp.renameTo(targetFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting asset $assetName: ${e.message}")
        }
    }

    private class Sravaani10LiveStream(
        private val engine: Sravaani10Engine,
        private val language: SttLanguage,
    ) : SttLiveStream {
        private val chunks = mutableListOf<ShortArray>()
        private var totalSamples = 0
        private var isFinished = false
        override var onPartial: ((String) -> Unit)? = null

        override suspend fun pushPcm(samples: ShortArray) {
            check(!isFinished) { "Live stream already finished" }
            if (!engine.isReady) {
                throw SttException.EngineNotReady("engine never initialized or already released")
            }
            if (samples.isNotEmpty()) {
                chunks.add(samples.clone())
                totalSamples += samples.size
            }
        }

        override suspend fun finish(): SttResult {
            check(!isFinished) { "Live stream already finished" }
            isFinished = true
            if (!engine.isReady) {
                throw SttException.EngineNotReady("engine never initialized or already released")
            }
            if (totalSamples == 0) {
                Log.d(TAG, "LiveStream finished with 0 samples")
                return SttResult.empty(language)
            }
            Log.d(TAG, "LiveStream finishing: assembling $totalSamples samples from ${chunks.size} chunks")
            val allPcm = ShortArray(totalSamples)
            var offset = 0
            for (c in chunks) {
                System.arraycopy(c, 0, allPcm, offset, c.size)
                offset += c.size
            }
            return engine.transcribe(allPcm, language)
        }

        override fun release() {
            chunks.clear()
            totalSamples = 0
        }
    }

    private companion object {
        const val TAG = "Sravaani10Engine"
    }
}
