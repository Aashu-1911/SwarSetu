package app.swarsetu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.swarsetu.data.settings.SettingsStore
import app.swarsetu.ui.RouteInbox
import app.swarsetu.ui.SwarSetuApp
import app.swarsetu.ui.share.ShareInbox
import app.swarsetu.ui.share.SharedContent
import app.swarsetu.ui.theme.SwarSetuTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    // Single-shot holder for content arriving via the system share sheet; SwarSetuApp/ChatScreen drain it.
    private val shareInbox: ShareInbox by inject()

    // Single-shot holder for a notification-tap deep-link route (e.g. "chat/<id>"); SwarSetuApp drains it.
    private val routeInbox: RouteInbox by inject()

    private val settingsStore: SettingsStore by inject()

    override fun attachBaseContext(newBase: android.content.Context) {
        val koinSettingsStore = org.koin.java.KoinJavaComponent.getKoin().get<SettingsStore>()
        val langCode = kotlinx.coroutines.runBlocking { kotlinx.coroutines.flow.first(koinSettingsStore.sttLanguageCode) }
        val locale = java.util.Locale(langCode)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A cold-start share: stage the payload before composition so SwarSetuApp opens the picker.
        handleShareIntent(intent)
        // A cold-start notification tap: stage its deep-link route so SwarSetuApp navigates to that thread.
        handleRouteIntent(intent)
        val startRoute =
            if (BuildConfig.SEED_DEMO || BuildConfig.DEBUG) {
                intent?.getStringExtra(EXTRA_DEMO_ROUTE)
            } else {
                null
            }
        setContent {
            val themeMode by settingsStore.themePreference.collectAsStateWithLifecycle(initialValue = "system")
            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            val langCode by settingsStore.sttLanguageCode.collectAsStateWithLifecycle(initialValue = "en")
            val context = androidx.compose.ui.platform.LocalContext.current
            val locale = java.util.Locale(langCode)
            java.util.Locale.setDefault(locale)
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            val localizedContext = context.createConfigurationContext(configuration)

            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides localizedContext,
                androidx.compose.ui.platform.LocalConfiguration provides configuration
            ) {
                SwarSetuTheme(darkTheme = isDark) {
                    SwarSetuApp(startRoute = startRoute)
                }
            }
        }
    }

    // Share into an already-running instance (launchMode=singleTask). Re-stage into the inbox; SwarSetuApp
    // observes it and routes to the share-target picker.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // A notification tap on an already-running instance: stage the deep-link route; SwarSetuApp navigates.
        handleRouteIntent(intent)
    }

    /** Stage a notification deep-link route ([EXTRA_ROUTE], e.g. "chat/<id>") into the [RouteInbox]. */
    private fun handleRouteIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let { routeInbox.offer(it) }
    }

    /** Parse an ACTION_SEND intent into the [ShareInbox]. Other intents (incl. the launcher) are ignored. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        // EXTRA_STREAM is only meaningful (and read-granted) for the image/* filter we declare.
        val imageUri =
            if (intent.type?.startsWith("image/") == true) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
            } else {
                null
            }
        shareInbox.offer(SharedContent(text = text, imageUri = imageUri))
    }

    companion object {
        /** Deep-link route extra set by [app.swarsetu.notifications.MessageNotifier] on a notification tap. */
        const val EXTRA_ROUTE = "app.swarsetu.NOTIF_ROUTE"
        private const val EXTRA_DEMO_ROUTE = "demo_route"
    }
}
