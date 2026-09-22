package app.swarsetu.tts.backend

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.swarsetu.tts.TtsEngine
import app.swarsetu.tts.TtsLanguage
import app.swarsetu.tts.TtsLanguageCapability
import app.swarsetu.tts.TtsRequest
import app.swarsetu.tts.TtsResult
import app.swarsetu.tts.metrics.TtsMetricsCollector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidTtsEngine(
    private val context: Context,
    private val metricsCollector: TtsMetricsCollector,
) : TtsEngine,
    TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var isReady = false
    private val previousVolumeMap = java.util.concurrent.ConcurrentHashMap<String, Int>()

    override suspend fun initialize(): Boolean =
        withContext(Dispatchers.Main) {
            if (isReady && tts != null) return@withContext true

            val existing = initDeferred
            if (existing != null && existing.isActive) {
                return@withContext existing.await()
            }

            Log.d("SW_TTS", "TTS_START")
            val deferred = CompletableDeferred<Boolean>()
            initDeferred = deferred

            try {
                // Prefer Google TTS engine if available for full 10-language Indic support
                var chosenEngine: String? = null
                val tempTts = TextToSpeech(context.applicationContext, null)
                val engines = tempTts.engines.orEmpty()
                try { tempTts.shutdown() } catch (_: Exception) {}

                if (engines.any { it.name == "com.google.android.tts" }) {
                    chosenEngine = "com.google.android.tts"
                }

                if (chosenEngine != null) {
                    android.util.Log.d("AndroidTtsEngine", "Initializing with Google TTS engine ($chosenEngine)")
                    tts = TextToSpeech(context.applicationContext, this@AndroidTtsEngine, chosenEngine)
                } else {
                    android.util.Log.d("AndroidTtsEngine", "Initializing with default TTS engine")
                    tts = TextToSpeech(context.applicationContext, this@AndroidTtsEngine)
                }
                deferred.await()
            } catch (e: Exception) {
                Log.e("SW_TTS", "TTS_ERROR: ${e.message}", e)
                isReady = false
                initDeferred?.complete(false)
                initDeferred = null
                false
            }
        }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            setupListeners()
            Log.d("SW_TTS", "TTS_INIT_SUCCESS")
            initDeferred?.complete(true)
        } else {
            isReady = false
            Log.e("SW_TTS", "TTS_ERROR: onInit returned status $status")
            initDeferred?.complete(false)
        }
        initDeferred = null
    }

    private fun setupListeners() {
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceId?.let { metricsCollector.onPlaybackStart(it) }
                }

                override fun onDone(utteranceId: String?) {
                    Log.d("SW_TTS", "TTS_DONE (id=$utteranceId)")
                    utteranceId?.let { 
                        restoreAlarmVolumeIfNeeded(it)
                        metricsCollector.onCompleted(it) 
                    }
                }

                @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, TextToSpeech.ERROR)"))
                override fun onError(utteranceId: String?) {
                    Log.e("SW_TTS", "TTS_ERROR (id=$utteranceId, Unknown error)")
                    utteranceId?.let { 
                        restoreAlarmVolumeIfNeeded(it)
                        metricsCollector.onError(it, "Unknown TTS Error") 
                    }
                }

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    Log.e("SW_TTS", "TTS_ERROR (id=$utteranceId, code=$errorCode)")
                    utteranceId?.let { 
                        restoreAlarmVolumeIfNeeded(it)
                        metricsCollector.onError(it, "TTS Error code: $errorCode") 
                    }
                }

                private fun restoreAlarmVolumeIfNeeded(utteranceId: String) {
                    val prevVolume = previousVolumeMap.remove(utteranceId)
                    if (prevVolume != null) {
                        try {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, prevVolume, 0)
                        } catch (e: Exception) {
                            android.util.Log.w("AndroidTtsEngine", "Failed to restore volume", e)
                        }
                    }
                }

                override fun onStop(
                    utteranceId: String?,
                    interrupted: Boolean,
                ) {
                    if (interrupted) {
                        utteranceId?.let { metricsCollector.onInterrupted(it) }
                    } else {
                        utteranceId?.let { metricsCollector.onCompleted(it) }
                    }
                }

                override fun onBeginSynthesis(
                    utteranceId: String?,
                    sampleRateInHz: Int,
                    audioFormat: Int,
                    channelCount: Int,
                ) {
                    super.onBeginSynthesis(utteranceId, sampleRateInHz, audioFormat, channelCount)
                    // We use the metrics collector's synthesis begin to mark this, but the actual tracking starts before speak()
                }

                override fun onAudioAvailable(
                    utteranceId: String?,
                    audio: ByteArray?,
                ) {
                    super.onAudioAvailable(utteranceId, audio)
                    utteranceId?.let { metricsCollector.onFirstAudioChunk(it) }
                }
            },
        )
    }

    private fun getCandidateLocales(language: TtsLanguage): List<java.util.Locale> {
        val langCode = language.locale.language.lowercase()
        return when (langCode) {
            "en" -> listOf(
                language.locale,
                java.util.Locale.US,
                java.util.Locale.UK,
                java.util.Locale.ENGLISH,
                java.util.Locale.forLanguageTag("en-IN"),
                java.util.Locale.getDefault(),
            )
            "mr" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("mr-IN"),
                java.util.Locale("mr"),
                java.util.Locale.forLanguageTag("hi-IN"),
                java.util.Locale("hi"),
            )
            "hi" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("hi-IN"),
                java.util.Locale("hi"),
            )
            "gu" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("gu-IN"),
                java.util.Locale("gu"),
            )
            "bn" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("bn-IN"),
                java.util.Locale("bn"),
            )
            "ta" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("ta-IN"),
                java.util.Locale("ta"),
            )
            "te" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("te-IN"),
                java.util.Locale("te"),
            )
            "kn" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("kn-IN"),
                java.util.Locale("kn"),
            )
            "ml" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("ml-IN"),
                java.util.Locale("ml"),
            )
            "or" -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag("or-IN"),
                java.util.Locale("or"),
                java.util.Locale.forLanguageTag("hi-IN"),
            )
            else -> listOf(
                language.locale,
                java.util.Locale.forLanguageTag(langCode),
                java.util.Locale(langCode),
            )
        }.distinct()
    }

    override fun isLanguageAvailable(language: TtsLanguage): TtsLanguageCapability {
        if (!isReady || tts == null) return TtsLanguageCapability.Error("TTS Engine not ready")

        val engineName = tts?.defaultEngine ?: "Unknown"
        val candidates = getCandidateLocales(language)
        val availableVoices = runCatching { tts?.voices }.getOrNull().orEmpty()

        for (candidate in candidates) {
            val matchingVoice = availableVoices.firstOrNull { v ->
                v.locale.language.equals(candidate.language, ignoreCase = true) &&
                    (v.locale.country.isBlank() || v.locale.country.equals(candidate.country, ignoreCase = true))
            } ?: availableVoices.firstOrNull { v ->
                v.locale.language.equals(candidate.language, ignoreCase = true)
            }

            if (matchingVoice != null) {
                return TtsLanguageCapability.Supported(engineName, matchingVoice.name)
            }

            val result = runCatching { tts?.isLanguageAvailable(candidate) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            ) {
                val voice = availableVoices.firstOrNull { it.locale.language.equals(candidate.language, ignoreCase = true) }
                return TtsLanguageCapability.Supported(engineName, voice?.name)
            }
        }

        return TtsLanguageCapability.Supported(engineName, null)
    }

    override suspend fun speak(request: TtsRequest): TtsResult =
        withContext(Dispatchers.Main) {
            if (!isReady || tts == null) {
                initialize()
            }

            if (!isReady || tts == null) {
                return@withContext TtsResult.Error("TTS Engine initialization failed or not ready")
            }

            val capability = isLanguageAvailable(request.language)
            try {
                val candidates = getCandidateLocales(request.language)
                var languageConfigured = false

                for (candidate in candidates) {
                    val res = runCatching { tts?.setLanguage(candidate) ?: TextToSpeech.LANG_NOT_SUPPORTED }
                        .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                    if (res >= TextToSpeech.LANG_AVAILABLE) {
                        languageConfigured = true
                        break
                    }
                }

                if (!languageConfigured && request.language.locale.language == "en") {
                    tts?.setLanguage(java.util.Locale.US)
                }

                val availableVoices = runCatching { tts?.voices }.getOrNull().orEmpty()
                val langCode = request.language.locale.language.lowercase()
                val matchingVoice = availableVoices.firstOrNull { v ->
                    v.locale.language.equals(langCode, ignoreCase = true) &&
                        (v.locale.country.isBlank() || v.locale.country.equals(request.language.locale.country, ignoreCase = true))
                } ?: availableVoices.firstOrNull { v ->
                    v.locale.language.equals(langCode, ignoreCase = true)
                } ?: if (langCode == "mr" || langCode == "or") {
                    availableVoices.firstOrNull { v -> v.locale.language.equals("hi", ignoreCase = true) }
                } else null

                if (matchingVoice != null) {
                    runCatching { tts?.voice = matchingVoice }
                }

                val isAlert = request.priority == app.swarsetu.tts.TtsPriority.ALERT || request.isAlert
                val audioUsage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(audioUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                
                val utteranceId = request.utteranceId ?: request.requestId
                
                if (isAlert) {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val prevVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                    previousVolumeMap[utteranceId] = prevVolume
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVolume, 0)
                }
            } catch (e: Exception) {
                android.util.Log.w("AndroidTtsEngine", "Failed setting TTS language/attributes: ${e.message}")
            }

            val utteranceId = request.utteranceId ?: request.requestId
            val voiceName = (capability as? TtsLanguageCapability.Supported)?.voiceName

            metricsCollector.onSynthesisBegin(utteranceId, request.language, voiceName, request.text.length)

            Log.d("SW_TTS", "TTS_SPEAK (id=$utteranceId, lang=${request.language.locale}, len=${request.text.length})")
            val bundle = Bundle()
            val result =
                try {
                    tts?.speak(request.text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
                } catch (e: Exception) {
                    Log.e("SW_TTS", "TTS_ERROR (speak exception: ${e.message})", e)
                    TextToSpeech.ERROR
                }

            if (result == TextToSpeech.SUCCESS) {
                TtsResult.Success
            } else {
                TtsResult.Error("Speak failed with code $result")
            }
        }

    override suspend fun synthesizeToFile(
        request: TtsRequest,
        outputFile: File,
    ): TtsResult =
        withContext(Dispatchers.Main) {
            if (!isReady || tts == null) {
                initialize()
            }

            if (!isReady || tts == null) {
                return@withContext TtsResult.Error("TTS Engine initialization failed or not ready")
            }

            val capability = isLanguageAvailable(request.language)
            try {
                val locale = request.language.locale
                val availableVoices = tts?.voices.orEmpty()
                val matchingVoice = availableVoices.firstOrNull { v ->
                    v.locale.language.equals(locale.language, ignoreCase = true)
                }
                if (matchingVoice != null) {
                    tts?.voice = matchingVoice
                }
                var setRes = tts?.setLanguage(locale)
                if (setRes == TextToSpeech.LANG_MISSING_DATA || setRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val genericLocale = java.util.Locale.forLanguageTag(locale.language)
                    setRes = tts?.setLanguage(genericLocale)
                    if ((setRes == TextToSpeech.LANG_MISSING_DATA || setRes == TextToSpeech.LANG_NOT_SUPPORTED) && locale.language == "en") {
                        tts?.setLanguage(java.util.Locale.US)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AndroidTtsEngine", "Failed setting TTS language: ${e.message}")
            }

            val utteranceId = request.utteranceId ?: request.requestId
            val voiceName = (capability as? TtsLanguageCapability.Supported)?.voiceName

            metricsCollector.onSynthesisBegin(utteranceId, request.language, voiceName, request.text.length)

            val result =
                try {
                    tts?.synthesizeToFile(request.text, null, outputFile, utteranceId)
                } catch (e: Exception) {
                    android.util.Log.e("AndroidTtsEngine", "Exception during synthesizeToFile: ${e.message}", e)
                    TextToSpeech.ERROR
                }

            if (result == TextToSpeech.SUCCESS) {
                TtsResult.Success
            } else {
                TtsResult.Error("SynthesizeToFile failed with code $result")
            }
        }

    override fun stop() {
        if (isReady) {
            tts?.stop()
        }
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        initDeferred?.cancel()
        initDeferred = null
    }
}
