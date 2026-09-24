package app.swarsetu.stt.sravaani

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Executes the SraVaani 1.0 FastConformer ONNX encoder graph.
 *
 * Input:
 * - `feats`: Float tensor `[1, 128, T]` representing 128-dimensional log-mel filterbank features.
 * - `feature_lengths`: Int64 tensor `[1]` containing the frame length `T`.
 *
 * Output:
 * - `enc_out`: Float tensor `[1, 1024, T']` where `T'` is the subsampled acoustic frame count.
 * - `enc_lengths`: Int64 tensor `[1]` containing `T'`.
 */
class SravaaniOnnxEncoder(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : Closeable {

    companion object {
        const val MEL_BINS = 128
        const val ENC_D_MODEL = 1024
    }

    private var featsDirectBuf: FloatBuffer? = null
    private var featsCapacityFloats = 0
    private val lenBuf: LongBuffer = ByteBuffer.allocateDirect(8)
        .order(ByteOrder.nativeOrder())
        .asLongBuffer()

    private fun getOrCreateFeatsBuffer(requiredFloats: Int): FloatBuffer {
        val cur = featsDirectBuf
        if (cur != null && featsCapacityFloats >= requiredFloats) {
            cur.clear()
            return cur
        }
        val newCapacity = maxOf(requiredFloats, featsCapacityFloats * 2, 128 * 200)
        val buf = ByteBuffer.allocateDirect(newCapacity * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        featsDirectBuf = buf
        featsCapacityFloats = newCapacity
        return buf
    }

    data class EncoderOutput(
        val frames: FloatArray,
        val numFrames: Int,
        val dModel: Int = ENC_D_MODEL,
    ) {
        /** Slice feature vector at acoustic frame index [t] (size [dModel]). */
        fun getFrame(t: Int, out: FloatArray) {
            require(t in 0 until numFrames)
            require(out.size >= dModel)
            val offset = t * dModel
            System.arraycopy(frames, offset, out, 0, dModel)
        }
    }

    /**
     * Run the encoder on a sequence of 128-dim mel spectrogram frames.
     *
     * @param feats Flat FloatArray of shape `[1, 128, T]` (time-contiguous or channel-contiguous).
     *              In SraVaani 1.0, shape is `[1, 128, T]` (channel-major: mel 0 for all T, then mel 1 for all T).
     * @param numFrames Number of time frames T.
     */
    fun encode(feats: FloatArray, numFrames: Int): EncoderOutput {
        require(numFrames > 0) { "numFrames must be > 0" }
        require(feats.size >= MEL_BINS * numFrames) {
            "feats array size ${feats.size} < expected ${MEL_BINS * numFrames}"
        }

        val totalFloats = MEL_BINS * numFrames
        val buf = getOrCreateFeatsBuffer(totalFloats)
        buf.put(feats, 0, totalFloats)
        buf.rewind()

        lenBuf.clear()
        lenBuf.put(0, numFrames.toLong())
        lenBuf.rewind()

        val featsTensor = OnnxTensor.createTensor(
            env,
            buf,
            longArrayOf(1, MEL_BINS.toLong(), numFrames.toLong()),
        )
        val lenTensor = OnnxTensor.createTensor(env, lenBuf, longArrayOf(1))

        try {
            val result = session.run(
                mapOf(
                    "feats" to featsTensor,
                    "feature_lengths" to lenTensor,
                ),
            )

            result.use { r ->
                val encOutTensor = r.get(0) as OnnxTensor
                val encLenTensor = r.get(1) as OnnxTensor

                val encLenBuf = encLenTensor.longBuffer
                encLenBuf.rewind()
                val outFramesCount = encLenBuf.get(0).toInt()

                val encOutBuf = encOutTensor.floatBuffer
                encOutBuf.rewind()

                // enc_out has shape [1, 1024, enc_time]
                // Layout in tensor buffer: [1024 * outFramesCount]
                // Channel d at time t is at index: d * enc_time + t
                // We reorder to [outFramesCount * 1024] (time-major: frame t contains 1024 floats)
                // for O(1) frame slicing during TDT step decoding.
                val totalOutputFloats = ENC_D_MODEL * outFramesCount
                val timeMajorOut = FloatArray(totalOutputFloats)

                val encTime = outFramesCount
                for (t in 0 until encTime) {
                    val frameOffset = t * ENC_D_MODEL
                    for (d in 0 until ENC_D_MODEL) {
                        timeMajorOut[frameOffset + d] = encOutBuf.get(d * encTime + t)
                    }
                }

                return EncoderOutput(timeMajorOut, outFramesCount, ENC_D_MODEL)
            }
        } finally {
            featsTensor.close()
            lenTensor.close()
        }
    }

    override fun close() {
        featsDirectBuf = null
        featsCapacityFloats = 0
    }
}
