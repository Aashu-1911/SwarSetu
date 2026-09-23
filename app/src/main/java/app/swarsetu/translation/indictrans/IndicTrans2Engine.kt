package app.swarsetu.translation.indictrans

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

data class TranslationResult(
    val translatedText: String,
    val sourceLanguage: IndicLanguage,
    val targetLanguage: IndicLanguage,
    val latencyMs: Long,
    val tokensGenerated: Int,
    val modelBundle: IndicTransModelBundle,
)

/**
 * Executes local on-device neural translation using IndicTrans2 ONNX models
 * with ONNX Runtime Mobile.
 */
class IndicTrans2Engine(
    private val modelManager: IndicTransModelManager,
) {
    companion object {
        private const val TAG = "IndicTrans2Engine"
        private const val METASPACE = "\u2581"
        private const val UNK_TOKEN = "<unk>"
        private const val BOS_TOKEN = "<s>"
        private const val EOS_TOKEN = "</s>"
        private const val PAD_TOKEN = "<pad>"

        private const val DEFAULT_START_ID = 2L
        private const val DEFAULT_EOS_ID = 2L
        private const val DEFAULT_UNK_ID = 3L
        private const val MAX_NEW_TOKENS = 128
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val loadedSessions = ConcurrentHashMap<IndicTransModelBundle, SessionBundle>()

    private class SessionBundle(
        val env: OrtEnvironment,
        val encoderSession: OrtSession,
        val decoderSession: OrtSession,
        val decoderWithPastSession: OrtSession?,
        val srcVocab: Map<String, Long>,
        val tgtDecoder: Map<Long, String>,
        val startTokenId: Long,
        val eosTokenId: Long,
        val unkTokenId: Long,
        val numLayers: Int,
    ) {
        fun close() {
            try { encoderSession.close() } catch (_: Exception) {}
            try { decoderSession.close() } catch (_: Exception) {}
            try { decoderWithPastSession?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Determines the required model bundle for the given source and target languages.
     */
    fun selectBundle(source: IndicLanguage, target: IndicLanguage): IndicTransModelBundle {
        return when {
            source == IndicLanguage.ENGLISH && target.isIndic -> IndicTransModelBundle.EN_INDIC
            source.isIndic && target == IndicLanguage.ENGLISH -> IndicTransModelBundle.INDIC_EN
            else -> IndicTransModelBundle.INDIC_INDIC
        }
    }

    /**
     * Translates input text from [source] language to [target] language using local ONNX model.
     */
    suspend fun translate(
        text: String,
        source: IndicLanguage,
        target: IndicLanguage,
        preferredBundle: IndicTransModelBundle? = null,
    ): TranslationResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val trimmed = text.trim()
        if (trimmed.isEmpty() || source == target) {
            return@withContext TranslationResult(
                translatedText = text,
                sourceLanguage = source,
                targetLanguage = target,
                latencyMs = 0L,
                tokensGenerated = 0,
                modelBundle = preferredBundle ?: selectBundle(source, target),
            )
        }

        val bundle = preferredBundle ?: selectBundle(source, target)
        if (!modelManager.isModelDownloaded(bundle)) {
            throw IllegalStateException(
                "Model bundle '${bundle.displayName}' is not downloaded. Please download it via Diagnostics.",
            )
        }

        val sessionBundle = getOrLoadSession(bundle)
        val env = sessionBundle.env

        // 1. Tokenize source sequence: [srcTag, tgtTag, pieces..., </s>]
        val srcTag = source.floresTag
        val tgtTag = target.floresTag
        val inputTokens = mutableListOf<String>()
        inputTokens.add(srcTag)
        inputTokens.add(tgtTag)

        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        for (w in words) {
            val pieces = segmentWord(METASPACE + w, sessionBundle.srcVocab)
            inputTokens.addAll(pieces)
        }
        inputTokens.add(EOS_TOKEN)

        val seqLen = inputTokens.size.toLong()
        val inputIds = LongArray(inputTokens.size) { i ->
            sessionBundle.srcVocab[inputTokens[i]] ?: sessionBundle.unkTokenId
        }
        val attentionMask = LongArray(inputTokens.size) { 1L }

        val inputIdsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(inputIds),
            longArrayOf(1L, seqLen),
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(attentionMask),
            longArrayOf(1L, seqLen),
        )

        var encResult: OrtSession.Result? = null
        try {
            // 2. Run Encoder
            val encInputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
            )
            encResult = sessionBundle.encoderSession.run(encInputs)
            val encHiddenState = encResult.get(0) as OnnxTensor

            // 3. Autoregressive greedy decoding
            val outputIds = mutableListOf<Long>()
            outputIds.add(sessionBundle.startTokenId)

            var decInputId = sessionBundle.startTokenId
            var pastOutputs: List<OnnxTensor>? = null

            for (step in 0 until MAX_NEW_TOKENS) {
                val stepInputTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(decInputId)),
                    longArrayOf(1L, 1L),
                )

                val decResult: OrtSession.Result = if (step == 0 || sessionBundle.decoderWithPastSession == null || pastOutputs == null) {
                    val decInputs = mapOf(
                        "input_ids" to stepInputTensor,
                        "encoder_hidden_states" to encHiddenState,
                        "encoder_attention_mask" to attentionMaskTensor,
                    )
                    sessionBundle.decoderSession.run(decInputs)
                } else {
                    val feed = mutableMapOf<String, OnnxTensor>()
                    feed["input_ids"] = stepInputTensor
                    feed["encoder_attention_mask"] = attentionMaskTensor

                    val numLayers = sessionBundle.numLayers
                    for (i in 0 until numLayers) {
                        val base = i * 4
                        if (base + 3 < pastOutputs.size) {
                            feed["past_key_values.$i.decoder.key"] = pastOutputs[base]
                            feed["past_key_values.$i.decoder.value"] = pastOutputs[base + 1]
                            feed["past_key_values.$i.encoder.key"] = pastOutputs[base + 2]
                            feed["past_key_values.$i.encoder.value"] = pastOutputs[base + 3]
                        }
                    }
                    sessionBundle.decoderWithPastSession.run(feed)
                }

                stepInputTensor.close()

                val logitsTensor = decResult.get(0) as OnnxTensor
                val logits = logitsTensor.value as Array<Array<FloatArray>>
                val lastStepLogits = logits[0][0]

                var maxIdx = 0
                var maxVal = Float.NEGATIVE_INFINITY
                for (j in lastStepLogits.indices) {
                    if (lastStepLogits[j] > maxVal) {
                        maxVal = lastStepLogits[j]
                        maxIdx = j
                    }
                }

                val nextId = maxIdx.toLong()
                outputIds.add(nextId)

                // Cache next past_key_values tensors
                if (decResult.size() > 1) {
                    pastOutputs?.forEach { it.close() }
                    pastOutputs = (1 until decResult.size()).map { decResult.get(it) as OnnxTensor }
                }

                decInputId = nextId
                if (nextId == sessionBundle.eosTokenId) {
                    break
                }
            }

            pastOutputs?.forEach { it.close() }

            // 4. Detokenize output tokens
            val rawTokens = outputIds.mapNotNull { id ->
                if (id == sessionBundle.startTokenId || id == sessionBundle.eosTokenId) null
                else sessionBundle.tgtDecoder[id]
            }

            val joined = rawTokens.joinToString("")
                .replace(METASPACE, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            // 5. Script transliteration if generated in unified Devanagari space
            val finalTransliterated = if (target.isIndic && target != IndicLanguage.HINDI && target != IndicLanguage.MARATHI) {
                IndicLanguage.transliterateIndic(joined, IndicLanguage.HINDI, target)
            } else {
                joined
            }

            val latency = System.currentTimeMillis() - t0
            TranslationResult(
                translatedText = finalTransliterated.ifEmpty { trimmed },
                sourceLanguage = source,
                targetLanguage = target,
                latencyMs = latency,
                tokensGenerated = outputIds.size - 1,
                modelBundle = bundle,
            )
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            encResult?.close()
        }
    }

    private fun segmentWord(word: String, vocab: Map<String, Long>): List<String> {
        if (vocab.containsKey(word)) return listOf(word)
        val pieces = mutableListOf<String>()
        var i = 0
        val n = word.length
        while (i < n) {
            var longestMatch: String? = null
            for (len in minOf(n - i, 20) downTo 1) {
                val sub = word.substring(i, i + len)
                if (vocab.containsKey(sub)) {
                    longestMatch = sub
                    break
                }
            }
            if (longestMatch != null) {
                pieces.add(longestMatch)
                i += longestMatch.length
            } else {
                pieces.add(word.substring(i, i + 1))
                i++
            }
        }
        return pieces
    }

    @Synchronized
    private fun getOrLoadSession(bundle: IndicTransModelBundle): SessionBundle {
        loadedSessions[bundle]?.let { return it }

        Log.i(TAG, "Loading ONNX sessions and dictionaries for ${bundle.displayName}...")
        val dir = modelManager.getModelDir(bundle)
        val env = OrtEnvironment.getEnvironment()

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val encFile = File(dir, "encoder_model.onnx")
        val decFile = File(dir, "decoder_model.onnx")
        val decPastFile = File(dir, "decoder_with_past_model.onnx")

        val encoderSession = env.createSession(encFile.absolutePath, opts)
        val decoderSession = env.createSession(decFile.absolutePath, opts)
        val decoderWithPastSession = if (decPastFile.exists()) {
            try { env.createSession(decPastFile.absolutePath, opts) } catch (e: Exception) {
                Log.w(TAG, "Could not load decoder_with_past, falling back to basic decoder: ${e.message}")
                null
            }
        } else null

        opts.close()

        // Load dictionaries
        val srcDictFile = File(dir, "dict.SRC.json")
        val tgtDictFile = File(dir, "dict.TGT.json")

        val srcVocab = parseDictJson(srcDictFile)
        val tgtVocab = parseDictJson(tgtDictFile)
        val tgtDecoder = tgtVocab.entries.associate { (k, v) -> v to k }

        // Load generation config if present
        val genConfigFile = File(dir, "generation_config.json")
        var startId = DEFAULT_START_ID
        var eosId = DEFAULT_EOS_ID
        if (genConfigFile.exists()) {
            try {
                val parsed = json.decodeFromString<JsonObject>(genConfigFile.readText())
                startId = parsed["decoder_start_token_id"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_START_ID
                eosId = parsed["eos_token_id"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_EOS_ID
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing generation_config.json: ${e.message}")
            }
        }

        val numLayers = (decoderSession.outputNames.size - 1) / 4

        val sessionBundle = SessionBundle(
            env = env,
            encoderSession = encoderSession,
            decoderSession = decoderSession,
            decoderWithPastSession = decoderWithPastSession,
            srcVocab = srcVocab,
            tgtDecoder = tgtDecoder,
            startTokenId = startId,
            eosTokenId = eosId,
            unkTokenId = DEFAULT_UNK_ID,
            numLayers = numLayers.coerceAtLeast(1),
        )

        loadedSessions[bundle] = sessionBundle
        Log.i(TAG, "Successfully loaded IndicTrans2 ONNX bundle ${bundle.displayName} ($numLayers layers)")
        return sessionBundle
    }

    private fun parseDictJson(file: File): Map<String, Long> {
        if (!file.exists()) return emptyMap()
        return try {
            val content = file.readText()
            val jsonObject = json.decodeFromString<JsonObject>(content)
            val map = HashMap<String, Long>(jsonObject.size)
            for ((key, value) in jsonObject) {
                val id = value.jsonPrimitive.intOrNull?.toLong()
                if (id != null) {
                    map[key] = id
                }
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing dictionary file ${file.name}", e)
            emptyMap()
        }
    }

    fun releaseAll() {
        loadedSessions.values.forEach { it.close() }
        loadedSessions.clear()
        Log.i(TAG, "All IndicTrans2 ONNX sessions released")
    }
}
