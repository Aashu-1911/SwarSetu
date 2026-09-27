package app.swarsetu.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
 * Manages continuous 16kHz AudioRecord capture and AudioTrack playback with
 * hardware Acoustic Echo Cancellation (AEC), Noise Suppression (NS), and audio routing.
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
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioFocusRequest: AudioFocusRequest? = null

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
            // 1. Request Audio Focus & Configure AudioManager for VoIP
            requestCallAudioFocus()
            previousAudioMode = audioManager.mode
            previousSpeakerphoneState = audioManager.isSpeakerphoneOn

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphone(speakerphone)

            // 2. Initialize AudioRecord (16kHz 16-bit Mono)
            val minRecordBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recordBufferSize = maxOf(minRecordBufferSize, sampleRate * 2) // 1 second buffer

            var record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferSize,
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "VOICE_COMMUNICATION audio source failed to initialize, falling back to MIC")
                record.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize,
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed with all audio sources!")
                record.release()
                return@withContext false
            }
            audioRecord = record

            // Attach Acoustic Echo Canceler and Noise Suppressor if supported
            attachAudioEffects(record.audioSessionId)

            // 3. Initialize AudioTrack (16kHz 16-bit Mono)
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
                releaseAudioEffects()
                audioRecord?.release()
                audioRecord = null
                return@withContext false
            }
            audioTrack = track
            track.play()
            isPlaying.set(true)
            Log.i(TAG, "CALL_AUDIO_PLAYBACK_START: AudioTrack initialized and playing")

            // 4. Start Continuous Capture Loop
            record.startRecording()
            isCapturing.set(true)
            Log.i(TAG, "CALL_AUDIO_CAPTURE_START: AudioRecord recording started at $sampleRate Hz")

            captureJob = scope.launch(Dispatchers.IO) {
                val pcmBuffer = ShortArray(CHUNK_SIZE_SAMPLES)
                while (isActive && isCapturing.get()) {
                    val currentRecord = audioRecord ?: break
                    val readCount = currentRecord.read(pcmBuffer, 0, CHUNK_SIZE_SAMPLES)
                    if (readCount > 0) {
                        if (!_isMuted.value) {
                            onPcmCaptured(pcmBuffer, readCount)
                        } else {
                            // Muted: pass silent chunk so VAD resets/stays quiet
                            val silentChunk = ShortArray(readCount)
                            onPcmCaptured(silentChunk, readCount)
                        }
                    } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_BAD_VALUE) {
                        Log.w(TAG, "AudioRecord read non-fatal status: $readCount")
                        kotlinx.coroutines.delay(20)
                    } else if (readCount == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.e(TAG, "AudioRecord dead object encountered! Breaking capture loop.")
                        break
                    }
                }
            }

            Log.d(TAG, "CallAudioEngine fully initialized and running")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting CallAudioEngine: ${e.message}", e)
            stop()
            false
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        try {
            val aecAvailable = AcousticEchoCanceler.isAvailable()
            val nsAvailable = NoiseSuppressor.isAvailable()
            Log.i(TAG, "AUDIO_HARDWARE_EFFECTS: AcousticEchoCanceler.isAvailable=$aecAvailable, NoiseSuppressor.isAvailable=$nsAvailable")

            if (aecAvailable) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session $audioSessionId")
            } else {
                Log.w(TAG, "Hardware AcousticEchoCanceler NOT available on this device chipset")
            }

            if (nsAvailable) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.i(TAG, "Hardware NoiseSuppressor enabled on session $audioSessionId")
            } else {
                Log.w(TAG, "Hardware NoiseSuppressor NOT available on this device chipset")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach hardware audio effects: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        } catch (_: Exception) {} finally {
            echoCanceler = null
        }
        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
        } catch (_: Exception) {} finally {
            noiseSuppressor = null
        }
    }

    private fun requestCallAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* handle focus loss if needed */ }
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed requesting audio focus: ${e.message}")
        }
    }

    private fun abandonCallAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed abandoning audio focus: ${e.message}")
        } finally {
            audioFocusRequest = null
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val earpieceDevice = devices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                    it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (on && speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                } else if (!on && earpieceDevice != null) {
                    audioManager.setCommunicationDevice(earpieceDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
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

        releaseAudioEffects()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            Log.i(TAG, "CALL_AUDIO_CAPTURE_STOP: AudioRecord stopped and released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.stop()
            audioTrack?.flush()
            audioTrack?.release()
            Log.i(TAG, "CALL_AUDIO_PLAYBACK_STOP: AudioTrack stopped and released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }

        abandonCallAudioFocus()

        try {
            audioManager.mode = previousAudioMode
            audioManager.isSpeakerphoneOn = previousSpeakerphoneState
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting AudioManager mode: ${e.message}")
        }

        Log.d(TAG, "CallAudioEngine stopped and released cleanly")
    }
}
