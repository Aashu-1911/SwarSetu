package app.swarsetu.tts.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Handles Android system Audio Focus requests for the TTS engine.
 */
class TtsAudioFocusManager(
    context: Context,
    private val onAudioFocusLost: () -> Unit,
) : AudioManager.OnAudioFocusChangeListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

            focusRequest =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build()
        }
    }

    /**
     * Requests audio focus before starting speech.
     * @return true if focus was granted, false otherwise.
     */
    fun requestFocus(): Boolean {
        val req = focusRequest
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req != null) {
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Abandons audio focus after speech completes.
     */
    fun abandonFocus() {
        val req = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req != null) {
            audioManager.abandonAudioFocusRequest(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // If we lose focus, interrupt TTS.
                onAudioFocusLost()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                // We don't automatically resume TTS on focus gain.
            }
        }
    }
}
