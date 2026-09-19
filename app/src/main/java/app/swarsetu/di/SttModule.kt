package app.swarsetu.di

import app.swarsetu.stt.PcmCapture
import app.swarsetu.stt.SttEngine
import app.swarsetu.stt.SttEngineFactory
import app.swarsetu.stt.SttModelManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin module for the STT (Speech-to-Text) subsystem. Bound alongside [appModule], [meshModule],
 * [moderationModule], and [uiModule] in [app.swarsetu.SwarSetuApplication].
 *
 * All STT objects are app-scoped singletons:
 * - [SttModelManager] — manages model availability and lifecycle
 * - [SttEngineFactory] — creates engine instances
 * - [SttEngine] — the shared, app-wide engine singleton (SraVaani 0.5-backed)
 * - [PcmCapture] — the shared, app-wide PCM capture instance
 *
 * The engine is a `single` (one instance, app-wide) because model loading is expensive
 * (the SraVaani encoder maps ~1.8 GB lazily on first use) and the microphone is an exclusive
 * system resource — only one transcription should run at a time. This mirrors
 * [app.swarsetu.ui.voice.VoicePlayer]'s pattern.
 *
 * **Fallback:** If ONNX Runtime is absent (exotic build variant), [SttEngineFactory] degrades
 * to [DefaultSttEngine] — empty results, no crash, and never a different STT model.
 */
val sttModule =
    module {
        single { SttModelManager(androidContext()) }
        single { SttEngineFactory(androidContext(), get()) }
        single<SttEngine> { get<SttEngineFactory>().create() }
        single { PcmCapture(androidContext()) }
        single { app.swarsetu.stt.SttPipeline(androidContext(), get()) }
    }
