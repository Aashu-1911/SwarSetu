package app.swarsetu

import android.app.Application
import app.swarsetu.crash.CrashHandler
import app.swarsetu.crash.crashStore
import app.swarsetu.crash.currentCrashEnvironment
import app.swarsetu.data.blob.BlobDao
import app.swarsetu.data.settings.SettingsStore
import app.swarsetu.di.appModule
import app.swarsetu.di.meshModule
import app.swarsetu.di.moderationModule
import app.swarsetu.di.seedDemoIfEnabled
import app.swarsetu.di.startDemoDirectorIfEnabled
import app.swarsetu.di.sttModule
import app.swarsetu.di.ttsModule
import app.swarsetu.di.uiModule
import app.swarsetu.di.voiceModule
import app.swarsetu.moderation.MlTextModerator
import app.swarsetu.notifications.Notifier
import app.swarsetu.stt.SttLanguage
import app.swarsetu.stt.SttPipeline
import app.swarsetu.ui.image.BlobFetcher
import app.swarsetu.ui.image.BlobKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class SwarSetuApplication :
    Application(),
    SingletonImageLoader.Factory {
    // Resolved lazily — first touched in newImageLoader(), which Coil calls well after startKoin().
    private val blobDao: BlobDao by inject()

    override fun onCreate() {
        super.onCreate()
        // Before startKoin, deliberately. The crashes worth capturing most are the ones in startup itself:
        // an AndroidKeyStore fault in KeystoreSecret/DatabaseKey, a SQLCipher or tflite .so that won't load,
        // a Koin graph that throws while building MeshDatabase. Every one of those kills the app before any
        // injectable object exists, which is also why the store is built by hand here rather than resolved.
        // Chains to whatever handler was already default, so the "Knit keeps stopping" dialog and the
        // process kill still happen exactly as before.
        CrashHandler.install(crashStore(this), currentCrashEnvironment())
        val koinApp =
            startKoin {
                androidLogger()
                androidContext(this@SwarSetuApplication)
                modules(appModule, meshModule, moderationModule, sttModule, ttsModule, uiModule, voiceModule)
            }
        // Register the message notification channel up front so it appears in system settings.
        koinApp.koin.get<Notifier>().createChannel()

        // Warm the toxicity model off the send path. The first classify() lazily loads a ~16 MB TFLite
        // model + tokenizer + Interpreter and pays first-inference allocation; done on the first outgoing
        // send it freezes the UI on a cold start (worst on low-end devices). Fire-and-forget on the
        // app-lifetime scope (Dispatchers.Default) so it never blocks startup; MlTextModerator degrades
        // gracefully if the assets fail to load, and warmUp() dedupes against a racing first send.
        koinApp.koin.get<CoroutineScope>().launch {
            koinApp.koin.get<MlTextModerator>().warmUp()
        }

        // Pre-warm the SraVaani STT engine off the PTT hot-path with the user's preferred language.
        koinApp.koin.get<CoroutineScope>().launch {
            val settings = koinApp.koin.get<SettingsStore>()
            val langCode = runCatching { settings.sttLanguageCode.first() }.getOrDefault("en")
            val targetLang = SttLanguage.fromCode(langCode) ?: SttLanguage.ENGLISH
            koinApp.koin.get<SttPipeline>().prewarm(targetLang)
        }

        // Seed the shipped default spools once (res/values/spools.xml). Opens no socket by itself — the
        // Internet plane stays off until the user turns it on — and a later removal sticks. A no-op while
        // the plane is dark (`BuildConfig.INTERNET_PLANE`), including the seeded marker, so the defaults
        // land on the first run of the build that introduces the feature.
        //
        // Its OWN coroutine, deliberately: chained behind warmUp() it inherited a ~16 MB model load, so a
        // fresh install sat with an unconfigured spool list for tens of seconds (observed on a Pixel 8),
        // and any throw from the warm-up would have skipped the seed entirely. The two share a scope, not
        // a sequence.
        koinApp.koin.get<CoroutineScope>().launch {
            koinApp.koin.get<SettingsStore>().seedDefaultSpools(resources.getStringArray(R.array.default_spools).toList())
        }

        // Demo-screenshot mode (`-PseedDemo=true`): fill the DB with a realistic conversation history so
        // the app renders populated on an emulator. Debug-only — the seeder lives in `src/debug`, so this is
        // a no-op in release (see the per-variant di/DemoWiring). Off by default even in debug.
        seedDemoIfEnabled(koinApp.koin)
        // Demo-trailer mode (`-PdemoDirector=true`): play the scripted, animated promo conversation instead
        // of the static seed. Also debug-only and a no-op in release.
        startDemoDirectorIfEnabled(koinApp.koin)
    }

    /**
     * App-wide Coil loader. Images come exclusively from the encrypted `blobs` table via
     * [BlobFetcher]/[BlobKeyer]; the disk cache is disabled so decrypted bytes are never persisted to
     * disk (only the in-memory bitmap cache is used). The animated decoder keeps GIFs/WebP animating.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .diskCache(null)
            .components {
                add(BlobKeyer())
                add(BlobFetcher.Factory(blobDao))
                add(AnimatedImageDecoder.Factory())
            }.build()
}
