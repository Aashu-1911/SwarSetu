package app.swarsetu.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance low-latency Audio Engine for Live Translated Calls.
 * Manages continuous 16kHz AudioRecord capture and AudioTrack playback.
 */
class CallAudioEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sampleRate: Int = 16000,
    private val onPcmCaptured: (pcmChunk: ShortArray, length: Int) -> Unit,
) {
    companion object {
        private const val TAG = "CallAudioEngine"
        private const val CHUNK_SIZE_SAMPLES = 320 // 20 ms at 16 kHz
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var captureJob: Job? = null
    private val isCapturing = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(true)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState: Boolean = false

    /**
     * Initializes and starts the audio engine for an active call.
     */
    @SuppressLint("MissingPermission")
    suspend fun start(speakerphone: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Configure Android AudioManager for VoIP communication
            previousAudioMode = audioManager.mode
            previousSpeakerphoneState = audioManager.isSpeakerphoneOn

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphone(speakerphone)

            // 2. Initialize AudioRecord
            val minRecordBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recordBufferSize = maxOf(minRecordBufferSize, sampleRate * 2) // At least 1 second buffer

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize,
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed!")
                record.release()
                return@withContext false
            }
            audioRecord = record

            // 3. Initialize AudioTrack
            val minTrackBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val trackBufferSize = maxOf(minTrackBufferSize, sampleRate * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed!")
                track.release()
                audioRecord?.release()
                audioRecord = null
                return@withContext false
            }
            audioTrack = track
            track.play()
            isPlaying.set(true)

            // 4. Start Capture Loop
            record.startRecording()
            isCapturing.set(true)

            captureJob = scope.launch(Dispatchers.IO) {
                val pcmBuffer = ShortArray(CHUNK_SIZE_SAMPLES)
                while (isActive && isCapturing.get()) {
                    val readCount = audioRecord?.read(pcmBuffer, 0, CHUNK_SIZE_SAMPLES) ?: -1
                    if (readCount > 0) {
                        if (!_isMuted.value) {
                            onPcmCaptured(pcmBuffer, readCount)
                        } else {
                            // Muted: pass silent chunk so VAD resets/stays quiet
                            val silentChunk = ShortArray(readCount)
                            onPcmCaptured(silentChunk, readCount)
                        }
                    } else if (readCount < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $readCount")
                    }
                }
            }

            Log.d(TAG, "CallAudioEngine started successfully at $sampleRate Hz")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting CallAudioEngine: ${e.message}", e)
            stop()
            false
        }
    }

    /**
     * Plays a chunk of 16-bit PCM audio samples to the active earpiece/speakerphone.
     */
    fun writePcmToSpeaker(pcmData: ShortArray, length: Int = pcmData.size): Int {
        if (!isPlaying.get() || audioTrack == null) return -1
        return audioTrack?.write(pcmData, 0, length, AudioTrack.WRITE_BLOCKING) ?: -1
    }

    /**
     * Toggles microphone mute.
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        Log.d(TAG, "Microphone muted: $muted")
    }

    /**
     * Toggles speakerphone output vs earpiece.
     */
    fun setSpeakerphone(on: Boolean) {
        _isSpeakerphoneOn.value = on
        try {
            audioManager.isSpeakerphoneOn = on
            Log.d(TAG, "Speakerphone set to: $on")
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting speakerphone: ${e.message}")
        }
    }

    /**
     * Stops audio capture and playback and restores system audio mode.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        isCapturing.set(false)
        isPlaying.set(false)
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.stop()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }

        try {
            audioManager.mode = previousAudioMode
            audioManager.isSpeakerphoneOn = previousSpeakerphoneState
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting AudioManager mode: ${e.message}")
        }

        Log.d(TAG, "CallAudioEngine stopped and released")
    }
}
