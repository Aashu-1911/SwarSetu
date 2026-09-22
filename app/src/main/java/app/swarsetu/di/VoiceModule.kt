package app.swarsetu.di

import app.swarsetu.voice.DefaultVoiceConversationController
import app.swarsetu.voice.VoiceConversationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

val voiceModule =
    module {
        // Provide a dedicated CoroutineScope for the VoiceConversationController
        single(named("voiceScope")) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        single<VoiceConversationController> {
            DefaultVoiceConversationController(
                scope = get(named("voiceScope")),
                sttPipeline = get(),
                ttsManager = get(),
            )
        }

        single {
            app.swarsetu.voice.VoiceMessageReceiver(
                ttsManager = get(),
                scope = get(named("voiceScope")),
                voiceController = get(),
                settingsStore = get(),
                translatorEngine = get(),
                messageRepository = get(),
            )
        }

        single {
            app.swarsetu.voice.VoiceMessageAdapter(
                scope = get(named("voiceScope")),
                sttPipeline = get(),
                meshController = get(),
                voiceController = get(),
                translatorEngine = get(),
                peers = get(),
                groups = get(),
                settingsStore = get(),
            )
        }
    }
