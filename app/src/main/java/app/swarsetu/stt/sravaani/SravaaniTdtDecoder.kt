package app.swarsetu.stt.sravaani

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Executes the SraVaani 1.0 TDT / RNN-T greedy decoding loop over encoder acoustic frames.
 *
 * Model contract (from `config.json` & ONNX metadata):
 * - `vocab_size`: 5000 (token IDs 0..4999)
 * - `blank_id`: 5000
 * - `durations`: [0, 1, 2, 3, 4] mapped to logits indices 5001..5005
 * - `pred_hidden`: 640 (LSTM hidden/cell dimension)
 * - `max_symbols`: 10 (maximum symbols emitted per acoustic frame)
 *
 * Inputs to `decoder_joint.onnx`:
 * - `f`: `[1, 1024, 1]` float32
 * - `tgt`: `[1, 1]` int32 (previous token id, initialized to BLANK_ID = 5000)
 * - `tlen`: `[1]` int32 (constant 1)
 * - `h_in`: `[1, 1, 640]` float32 (predictor LSTM hidden state)
 * - `c_in`: `[1, 1, 640]` float32 (predictor LSTM cell state)
 *
 * Outputs from `decoder_joint.onnx`:
 * - `logits`: `[1, 1, 1, 5006]` float32
 * - `aux`: `[1]` int32
 * - `h_out`: `[1, 1, 640]` float32
 * - `c_out`: `[1, 1, 640]` float32
 */
class SravaaniTdtDecoder(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : Closeable {

    companion object {
        const val TAG = "SravaaniTdtDecoder"
        const val VOCAB_SIZE = 5000
        const val BLANK_ID = 5000
        const val VOCAB_WITH_BLANK = 5001 // indices 0..5000
        const val NUM_DURATIONS = 5       // durations 0..4
        const val TOTAL_LOGITS = 5006     // 5001 + 5
        val DURATIONS = intArrayOf(0, 1, 2, 3, 4)
        const val PRED_HIDDEN = 640
        const val PRED_RNN_LAYERS = 1
        const val MAX_SYMBOLS_PER_FRAME = 10
    }

    private val inputNames = session.inputNames
    private val outputNames = session.outputNames

    private val hInputName: String = if (inputNames.contains("h")) "h" else "h_in"
    private val cInputName: String = if (inputNames.contains("c")) "c" else "c_in"

    private val fBuf: FloatBuffer = ByteBuffer.allocateDirect(1024 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val tgtBuf: IntBuffer = ByteBuffer.allocateDirect(4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()

    private val tlenBuf: IntBuffer = ByteBuffer.allocateDirect(4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()

    private val hBuf: FloatBuffer = ByteBuffer.allocateDirect(PRED_HIDDEN * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val cBuf: FloatBuffer = ByteBuffer.allocateDirect(PRED_HIDDEN * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val frameScratch = FloatArray(1024)

    /**
     * Decode an entire sequence of acoustic frames produced by [SravaaniOnnxEncoder].
     *
     * @param encoderOutput The time-major acoustic frames from [SravaaniOnnxEncoder].
     * @param allowedMask Optional boolean array of size >= 5001 indicating allowed token IDs
     *                    for the selected language script (blank index 5000 is always allowed).
     * @return List of decoded token IDs.
     */
    fun decode(
        encoderOutput: SravaaniOnnxEncoder.EncoderOutput,
        allowedMask: BooleanArray? = null,
    ): List<Int> {
        val numFrames = encoderOutput.numFrames
        if (numFrames <= 0) return emptyList()

        val tokens = mutableListOf<Int>()

        // 1. Initialize predictor LSTM state (h=0, c=0) and prev_token = BLANK_ID
        hBuf.clear()
        for (i in 0 until PRED_HIDDEN) hBuf.put(0f)
        hBuf.rewind()

        cBuf.clear()
        for (i in 0 until PRED_HIDDEN) cBuf.put(0f)
        cBuf.rewind()

        var last = BLANK_ID

        // 2. Iterate through acoustic frames with duration-based skipping
        var t = 0
        while (t < numFrames) {
            encoderOutput.getFrame(t, frameScratch)

            fBuf.clear()
            fBuf.put(frameScratch)
            fBuf.rewind()

            var added = 0
            var need = true

            while (need && added < MAX_SYMBOLS_PER_FRAME) {
                tgtBuf.put(0, minOf(last, BLANK_ID))
                tgtBuf.rewind()

                tlenBuf.put(0, 1)
                tlenBuf.rewind()

                hBuf.rewind()
                cBuf.rewind()

                val fTensor = OnnxTensor.createTensor(env, fBuf, longArrayOf(1, 1024, 1))
                val tgtTensor = OnnxTensor.createTensor(env, tgtBuf, longArrayOf(1, 1))
                val tlenTensor = OnnxTensor.createTensor(env, tlenBuf, longArrayOf(1))
                val hTensor = OnnxTensor.createTensor(env, hBuf, longArrayOf(1, 1, PRED_HIDDEN.toLong()))
                val cTensor = OnnxTensor.createTensor(env, cBuf, longArrayOf(1, 1, PRED_HIDDEN.toLong()))

                val inputs = mapOf(
                    "f" to fTensor,
                    "tgt" to tgtTensor,
                    "tlen" to tlenTensor,
                    hInputName to hTensor,
                    cInputName to cTensor,
                )

                val stepResult = try {
                    session.run(inputs)
                } finally {
                    fTensor.close()
                    tgtTensor.close()
                    tlenTensor.close()
                    hTensor.close()
                    cTensor.close()
                }

                stepResult.use { sr ->
                    val logitsTensor = sr.get(0) as OnnxTensor
                    val logitsBuf = logitsTensor.floatBuffer
                    logitsBuf.rewind()
                    val totalLogits = logitsBuf.remaining()

                    val vocabLimit = minOf(totalLogits - NUM_DURATIONS, VOCAB_WITH_BLANK)

                    // Find best token k from logits[:-5]
                    var bestToken = BLANK_ID
                    var maxTokenLogit = Float.NEGATIVE_INFINITY

                    if (allowedMask != null) {
                        for (v in 0 until vocabLimit) {
                            if (v < BLANK_ID && v < allowedMask.size && !allowedMask[v]) continue
                            val l = logitsBuf.get(v)
                            if (l > maxTokenLogit) {
                                maxTokenLogit = l
                                bestToken = v
                            }
                        }
                    } else {
                        for (v in 0 until vocabLimit) {
                            val l = logitsBuf.get(v)
                            if (l > maxTokenLogit) {
                                maxTokenLogit = l
                                bestToken = v
                            }
                        }
                    }

                    // Find best duration from logits[-5:] (durations 0..4)
                    var bestDurIdx = 0
                    var maxDurLogit = Float.NEGATIVE_INFINITY
                    val durStart = maxOf(0, totalLogits - NUM_DURATIONS)
                    for (d in 0 until NUM_DURATIONS) {
                        val idx = durStart + d
                        if (idx < totalLogits) {
                            val l = logitsBuf.get(idx)
                            if (l > maxDurLogit) {
                                maxDurLogit = l
                                bestDurIdx = d
                            }
                        }
                    }
                    val skip = if (bestDurIdx in DURATIONS.indices) DURATIONS[bestDurIdx] else 1

                    if (bestToken != BLANK_ID && bestToken < VOCAB_SIZE) {
                        tokens.add(bestToken)
                        last = bestToken

                        // Update h and c
                        val nextH = if (outputNames.contains("h_out")) {
                            (sr.get("h_out").get() as OnnxTensor).floatBuffer
                        } else {
                            (sr.get(2) as OnnxTensor).floatBuffer
                        }
                        val nextC = if (outputNames.contains("c_out")) {
                            (sr.get("c_out").get() as OnnxTensor).floatBuffer
                        } else {
                            (sr.get(3) as OnnxTensor).floatBuffer
                        }
                        nextH.rewind()
                        nextC.rewind()

                        hBuf.clear()
                        hBuf.put(nextH)
                        hBuf.rewind()

                        cBuf.clear()
                        cBuf.put(nextC)
                        cBuf.rewind()
                    }

                    added++
                    t += skip
                    need = (skip == 0)
                }
            }

            if (added == MAX_SYMBOLS_PER_FRAME) {
                t += 1
            }
        }

        return tokens
    }

    override fun close() {
        // Direct buffers do not require explicit close; session closed by caller.
    }
}
