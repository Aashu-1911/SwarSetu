package app.swarsetu.call

import kotlin.math.sqrt

/**
 * Energy and zero-crossing based Voice Activity Detection (VAD) and utterance endpoint detector.
 * Segments continuous 16kHz 16-bit PCM audio stream into short speech segments for low-latency STT.
 *
 * Parameters:
 * - 16,000 samples per second (16kHz).
 * - Minimum speech duration: 300 ms (to ignore transient clicks/coughs).
 * - Silence endpoint threshold: 450 ms of consecutive silence after active speech.
 * - Maximum utterance length: 3,500 ms (to guarantee low latency response).
 */
class CallVadEndpointDetector(
    private val sampleRate: Int = 16000,
    private val energyThresholdRms: Float = 450.0f,
    private val silenceEndpointTimeoutMs: Long = 450L,
    private val minSpeechDurationMs: Long = 300L,
    private val maxUtteranceDurationMs: Long = 3500L,
    private val onSpeechSegmentFinalized: (pcmData: ShortArray, durationMs: Long) -> Unit,
) {
    var isSpeechActive = false
        private set
    private var speechStartSampleTimeMs = 0L
    private var lastSpeechSampleTimeMs = 0L
    private var processedSamplesCount = 0L

    // Pre-allocated ring accumulator for current utterance (up to 4.5 seconds at 16kHz = 72,000 shorts)
    private val maxAccumulatorSize = (sampleRate * 4.5).toInt()
    private val speechAccumulator = ShortArray(maxAccumulatorSize)
    private var accumulatedSamples = 0

    // Pre-roll buffer (stores last ~200ms of audio before speech onset to capture leading consonants)
    private val preRollSize = (sampleRate * 0.20).toInt() // 3200 shorts = 200ms
    private val preRollBuffer = ShortArray(preRollSize)
    private var preRollIndex = 0
    private var preRollFilled = 0

    private val lock = Any()

    /**
     * Feeds a chunk of 16-bit PCM audio into the VAD detector.
     */
    fun processPcmChunk(pcmChunk: ShortArray, length: Int) {
        synchronized(lock) {
            if (length <= 0) return

            var chunkEnergySum = 0.0
            for (i in 0 until length) {
                val sample = pcmChunk[i].toDouble()
                chunkEnergySum += sample * sample
            }
            val rms = sqrt(chunkEnergySum / length).toFloat()
            val isChunkSpeech = rms >= energyThresholdRms

            val chunkDurationMs = (length * 1000L) / sampleRate
            val currentTimeMs = (processedSamplesCount * 1000L) / sampleRate
            processedSamplesCount += length

            if (isChunkSpeech) {
                lastSpeechSampleTimeMs = currentTimeMs

                if (!isSpeechActive) {
                    // Speech onset detected!
                    isSpeechActive = true
                    speechStartSampleTimeMs = currentTimeMs
                    accumulatedSamples = 0

                    // Prepend pre-roll buffer
                    copyPreRollToAccumulator()
                }

                // Accumulate current chunk
                appendChunkToAccumulator(pcmChunk, length)

                // Check max segment duration limit (e.g. 3.5s)
                val currentUtteranceDuration = currentTimeMs - speechStartSampleTimeMs
                if (currentUtteranceDuration >= maxUtteranceDurationMs) {
                    finalizeSegment()
                }
            } else {
                // Chunk is silent
                if (isSpeechActive) {
                    // Still in speech session, accumulate short silence chunks
                    appendChunkToAccumulator(pcmChunk, length)

                    val silenceDuration = currentTimeMs - lastSpeechSampleTimeMs
                    val totalDuration = currentTimeMs - speechStartSampleTimeMs

                    if (silenceDuration >= silenceEndpointTimeoutMs || totalDuration >= maxUtteranceDurationMs) {
                        // Endpoint detected!
                        finalizeSegment()
                    }
                } else {
                    // Ongoing background silence, update pre-roll ring buffer
                    updatePreRoll(pcmChunk, length)
                }
            }
        }
    }

    private fun updatePreRoll(chunk: ShortArray, length: Int) {
        for (i in 0 until length) {
            preRollBuffer[preRollIndex] = chunk[i]
            preRollIndex = (preRollIndex + 1) % preRollSize
            if (preRollFilled < preRollSize) preRollFilled++
        }
    }

    private fun copyPreRollToAccumulator() {
        if (preRollFilled == 0) return
        val startIdx = if (preRollFilled == preRollSize) preRollIndex else 0
        for (i in 0 until preRollFilled) {
            val idx = (startIdx + i) % preRollSize
            if (accumulatedSamples < maxAccumulatorSize) {
                speechAccumulator[accumulatedSamples++] = preRollBuffer[idx]
            }
        }
    }

    private fun appendChunkToAccumulator(chunk: ShortArray, length: Int) {
        val toCopy = minOf(length, maxAccumulatorSize - accumulatedSamples)
        System.arraycopy(chunk, 0, speechAccumulator, accumulatedSamples, toCopy)
        accumulatedSamples += toCopy
    }

    private fun finalizeSegment() {
        if (accumulatedSamples <= 0) {
            isSpeechActive = false
            return
        }

        val durationMs = (accumulatedSamples * 1000L) / sampleRate
        if (durationMs >= minSpeechDurationMs) {
            val segmentCopy = ShortArray(accumulatedSamples)
            System.arraycopy(speechAccumulator, 0, segmentCopy, 0, accumulatedSamples)
            onSpeechSegmentFinalized(segmentCopy, durationMs)
        }

        accumulatedSamples = 0
        isSpeechActive = false
    }

    /**
     * Flushes any remaining accumulated speech segment (e.g. on call hangup).
     */
    fun flush() {
        synchronized(lock) {
            if (isSpeechActive) {
                finalizeSegment()
            }
        }
    }

    /**
     * Resets detector state.
     */
    fun reset() {
        synchronized(lock) {
            isSpeechActive = false
            accumulatedSamples = 0
            processedSamplesCount = 0L
            preRollIndex = 0
            preRollFilled = 0
        }
    }
}
