package app.swarsetu.voice

import app.swarsetu.data.MessageRepository
import app.swarsetu.data.message.MessageEntity
import app.swarsetu.data.settings.SettingsStore
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.tts.TtsLanguage
import app.swarsetu.tts.TtsManager
import app.swarsetu.tts.TtsPriority
import app.swarsetu.tts.TtsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Intercepts incoming voice-text messages from the mesh layer (InboundPipeline) and routes them
 * to the TTS engine for playback.
 */
class VoiceMessageReceiver(
    private val ttsManager: TtsManager,
    private val scope: CoroutineScope,
    private val voiceController: VoiceConversationController,
    private val settingsStore: SettingsStore,
    private val translatorEngine: TranslatorEngine,
    private val messageRepository: MessageRepository,
) {
    fun onVoiceMessageReceived(entity: MessageEntity): Job {
        val t4 = System.currentTimeMillis()
        
        val isVoice = entity.messageType == MessageEntity.TYPE_TRANSLATED_VOICE ||
            entity.messageType == MessageEntity.TYPE_VOICE_NOTE ||
            entity.sourceText != null ||
            entity.voiceTextLanguage != null

        return scope.launch {
            val inputToTranslate = entity.sourceText ?: entity.body
            val detectedScriptLang = runCatching { translatorEngine.detectLanguageFromScript(inputToTranslate) }.getOrNull()
            val originalLanguage = (entity.sourceLanguage ?: entity.voiceTextLanguage ?: detectedScriptLang ?: "en").lowercase()
            val preferredLanguage = runCatching { settingsStore.sttLanguageCode.first() }.getOrDefault("en").lowercase()

            var textToSpeak = entity.translatedText ?: entity.body
            var languageToSpeak = entity.targetLanguage ?: originalLanguage

            // If the message has not been translated to receiver's preferred language, translate now
            if (originalLanguage != preferredLanguage && (languageToSpeak != preferredLanguage || entity.translatedText == null)) {
                if (inputToTranslate.isNotBlank()) {
                    android.util.Log.d("SW_RECEIVER", "RECEIVER_TRANSLATION_START ($originalLanguage -> $preferredLanguage)")
                    val myName = runCatching { settingsStore.displayName.first() }.getOrNull()
                    val translated = runCatching {
                        if (!myName.isNullOrBlank()) {
                            translatorEngine.translate(
                                text = inputToTranslate,
                                sourceLang = originalLanguage,
                                targetLang = preferredLanguage,
                                protectedNouns = listOf(myName),
                            )
                        } else {
                            translatorEngine.translate(
                                text = inputToTranslate,
                                sourceLang = originalLanguage,
                                targetLang = preferredLanguage,
                            )
                        }
                    }.onFailure {
                        android.util.Log.e("SW_RECEIVER", "RECEIVER_TRANSLATION_FAILED: ${it.message}", it)
                    }.getOrNull()
                    if (!translated.isNullOrBlank() && translated != inputToTranslate) {
                        textToSpeak = translated
                        languageToSpeak = preferredLanguage
                        android.util.Log.d("SW_RECEIVER", "RECEIVER_TRANSLATION_SUCCESS ($originalLanguage -> $preferredLanguage)")

                        // Update entity in DB with translated text for local display
                        val updatedEntity = entity.copy(
                            translatedText = textToSpeak,
                            targetLanguage = languageToSpeak,
                        )
                        runCatching { messageRepository.save(updatedEntity) }
                    } else {
                        textToSpeak = inputToTranslate
                        languageToSpeak = originalLanguage
                    }
                }
            }

            // Speak aloud automatically if it was sent as a voice message/note or auto-play TTS is enabled
            val autoPlayEnabled = runCatching { settingsStore.autoPlayTts.first() }.getOrDefault(false)
            if (isVoice || autoPlayEnabled) {
                val finalLanguage = parseLanguage(languageToSpeak, textToSpeak)
                android.util.Log.d("VoiceMessageReceiver", "Playing inbound message ${entity.id} in $finalLanguage: \"$textToSpeak\"")

                val request =
                    TtsRequest(
                        requestId = entity.id,
                        text = textToSpeak,
                        language = finalLanguage,
                        priority = if (entity.isAlert) TtsPriority.ALERT else TtsPriority.NORMAL,
                    )

                val t5 = System.currentTimeMillis()
                voiceController.reportInboundMessageMetrics(entity.id, t4, t5)

                ttsManager.speak(request)
            }
        }
    }

    private fun parseLanguage(languageName: String, textFallback: String? = null): TtsLanguage {
        return TtsLanguage.fromLanguageCode(languageName, textFallback) ?: TtsLanguage.HINDI
    }
}
