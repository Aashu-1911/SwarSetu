package app.swarsetu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.swarsetu.stt.SttLanguage
import app.swarsetu.ui.i18n.LocalAppLanguage
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.swarsetu.BuildConfig
import app.swarsetu.data.message.Conversations
import app.swarsetu.mesh.MeshController
import app.swarsetu.mesh.MeshService
import app.swarsetu.review.ReviewPrompter
import app.swarsetu.tts.ui.TtsTestScreen
import app.swarsetu.ui.blocked.BlockedUsersScreen
import app.swarsetu.call.CallManager
import app.swarsetu.call.CallState
import app.swarsetu.ui.call.LiveCallScreen
import app.swarsetu.ui.chat.ChatScreen
import app.swarsetu.ui.chat.MessageDetailsScreen
import app.swarsetu.ui.chatlist.ChatListScreen
import app.swarsetu.ui.contacts.ContactsScreen
import app.swarsetu.ui.diagnostics.CrashLogScreen
import app.swarsetu.ui.diagnostics.DiagnosticsScreen
import app.swarsetu.ui.donate.DonateScreen
import app.swarsetu.ui.group.GroupDetailsScreen
import app.swarsetu.ui.onboarding.LanguageInitScreen
import app.swarsetu.ui.onboarding.OnboardingScreen
import app.swarsetu.ui.onboarding.ProfileSetupScreen
import app.swarsetu.ui.profile.ProfileDetailsScreen
import app.swarsetu.ui.profile.ProfileScreen

import app.swarsetu.ui.requests.MessageRequestsScreen
import app.swarsetu.ui.share.ShareInbox
import app.swarsetu.ui.share.ShareTargetScreen
import app.swarsetu.ui.translation.TranslationTestScreen
import app.swarsetu.ui.verify.VerifyContactScreen
import org.koin.compose.koinInject

private object Routes {
    const val ONBOARDING = "onboarding"
    const val LANGUAGE_INIT = "language_init"
    const val PROFILE_SETUP = "profile_setup"
    const val CHAT_LIST = "chatlist"
    const val CONTACTS = "contacts"
    const val PROFILE = "profile"
    const val DIAGNOSTICS = "diagnostics"
    const val CRASH_LOG = "crash"
    const val BLOCKED_USERS = "blocked"
    const val MESSAGE_REQUESTS = "requests"
    const val DONATE = "donate"
    const val VERIFY = "verify"
    const val SHARE = "share"
    const val TTS_TEST = "tts_test"
    const val TRANSLATION_TEST = "translation_test"
    const val LIVE_CALL = "live_call/{peerId}/{peerName}"

    fun liveCall(peerId: String, peerName: String = "Peer") = "live_call/$peerId/$peerName"

    const val CHAT = "chat/{conversationId}"

    fun chat(conversationId: String) = "chat/$conversationId"

    const val PROFILE_DETAILS = "profileDetails/{nodeId}"

    fun profileDetails(nodeId: String) = "profileDetails/$nodeId"

    const val GROUP_DETAILS = "groupDetails/{groupId}"

    fun groupDetails(groupId: String) = "groupDetails/$groupId"

    const val MESSAGE_DETAILS = "messageDetails/{messageId}"

    // Message ids are FrameId's 22-char base64url, so they need no escaping to ride a route.
    fun messageDetails(messageId: String) = "messageDetails/$messageId"
}

/**
 * App root: gates on permissions, then hosts the screen graph (chat list ⇄ contacts ⇄ chat ⇄ profile)
 * with Navigation Compose. The chat route carries a `conversationId` — the "Nearby" broadcast room, a
 * peer's node id for a 1:1 DM, or a group id. Starts the mesh foreground service once past onboarding.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SwarSetuApp(startRoute: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val shareInbox = koinInject<ShareInbox>()
    val pendingShare by shareInbox.pending.collectAsStateWithLifecycle()
    val routeInbox = koinInject<RouteInbox>()
    val pendingRoute by routeInbox.pending.collectAsStateWithLifecycle()
    val reviewPrompter = koinInject<ReviewPrompter>()
    // Past onboarding once mesh permissions are granted (demo builds skip the gate).
    val onboarded = BuildConfig.SEED_DEMO || hasAllMeshPermissions(context)
    val settingsStore = koinInject<app.swarsetu.data.settings.SettingsStore>()
    val isLanguageInitComplete by settingsStore.isLanguageInitComplete.collectAsStateWithLifecycle(initialValue = false)
    val isProfileSetupComplete by settingsStore.isProfileSetupComplete.collectAsStateWithLifecycle(initialValue = false)
    val sttLanguageCode by settingsStore.sttLanguageCode.collectAsStateWithLifecycle(initialValue = "en")
    val currentLanguage = remember(sttLanguageCode) { SttLanguage.fromCode(sttLanguageCode) ?: SttLanguage.ENGLISH }

    // Demo-screenshot mode skips the permission gate (and an optional [startRoute] jumps straight to a
    // screen for deterministic capture); otherwise gate on permissions as usual.
    val start =
        startRoute
            ?: if (onboarded && isLanguageInitComplete && isProfileSetupComplete) {
                Routes.CHAT_LIST
            } else if (onboarded && isLanguageInitComplete) {
                Routes.PROFILE_SETUP
            } else if (onboarded) {
                Routes.LANGUAGE_INIT
            } else {
                Routes.ONBOARDING
            }

    // Start the mesh service whenever the user is past onboarding (guard kept broad on purpose). Demo
    // builds never start it — there is no real mesh and the seeded data needs no transport.
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry?.destination?.route) {
        val route = backStackEntry?.destination?.route
        if (!BuildConfig.SEED_DEMO && route != null && route != Routes.ONBOARDING) MeshService.start(context)
    }

    // Nudge the mesh to rescan / re-advertise whenever the app returns to the foreground, so it
    // recovers quickly after another app (e.g. Quick Share) briefly seized the Nearby radios. heal()
    // no-ops when the mesh isn't running, so this is safe before onboarding; demo builds skip it.
    if (!BuildConfig.SEED_DEMO) {
        val meshManager = koinInject<MeshController>()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) meshManager.heal()
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    val callManager = koinInject<CallManager>()
    val callState by callManager.callState.collectAsStateWithLifecycle()
    val currentSession by callManager.currentSession.collectAsStateWithLifecycle()

    LaunchedEffect(callState, currentSession) {
        if (callState == CallState.INCOMING && currentSession != null) {
            val s = currentSession!!
            navController.navigate(Routes.liveCall(s.peerId, s.peerName)) {
                launchSingleTop = true
            }
        }
    }

    // A share arrived (cold start: pending at first composition; warm start: onNewIntent flips it).
    // Open the target picker over the chat list — so Back/abandon returns there. A share that lands
    // before onboarding is dropped rather than left to leak into a later chat. launchSingleTop keeps
    // the cold-start navigate from stacking a second picker.
    LaunchedEffect(pendingShare != null) {
        if (pendingShare == null) return@LaunchedEffect
        if (!onboarded) {
            shareInbox.clear()
            return@LaunchedEffect
        }
        navController.navigate(Routes.SHARE) { launchSingleTop = true }
    }

    // A notification tap deep-links to a thread (cold start: pending at first composition; warm start:
    // onNewIntent flips it). The chat opens over the chat list (the start destination), so Back returns
    // there; launchSingleTop avoids stacking a duplicate when the thread is already on top. A deep-link
    // that lands before onboarding is dropped. Re-fires even while the app is foregrounded in another chat.
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (!onboarded) {
            routeInbox.clear()
            return@LaunchedEffect
        }
        navController.navigate(route) { launchSingleTop = true }
        routeInbox.consume()
    }

    CompositionLocalProvider(LocalAppLanguage provides currentLanguage) {
        NavHost(
            navController = navController,
            startDestination = start,
            // Surface Compose testTags as uiautomator resource-ids across the whole screen graph, so an
            // automation agent can locate elements (send button, message input, conversation rows) by a
            // stable id instead of pixel bounds. Set once at the root; the whole subtree inherits it.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onReady = {
                    navController.navigate(Routes.LANGUAGE_INIT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LANGUAGE_INIT) {
            LanguageInitScreen(
                onInitializationComplete = {
                    if (isProfileSetupComplete) {
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.CHAT_LIST) { popUpTo(0) }
                        }
                    } else {
                        navController.navigate(Routes.PROFILE_SETUP) {
                            popUpTo(Routes.LANGUAGE_INIT) { inclusive = true }
                        }
                    }
                },
                onBack = if (isProfileSetupComplete) { { navController.popBackStack() } } else null,
            )
        }

        composable(Routes.PROFILE_SETUP) {
            ProfileSetupScreen(
                onSetupComplete = {
                    navController.navigate(Routes.CHAT_LIST) { popUpTo(0) }
                },
            )
        }
        composable(Routes.CHAT_LIST) {
            ChatListScreen(
                onOpenConversation = { id -> navController.navigate(Routes.chat(id)) },
                onNewMessage = { navController.navigate(Routes.CONTACTS) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenBlockedUsers = { navController.navigate(Routes.BLOCKED_USERS) },
                onOpenMessageRequests = { navController.navigate(Routes.MESSAGE_REQUESTS) },
                onOpenDonate = { navController.navigate(Routes.DONATE) },
                onOpenVerify = { navController.navigate(Routes.VERIFY) },
            )
        }
        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                // Open the chosen conversation (a peer's node id for a DM, or a freshly created group's
                // id) and drop the picker from the back stack, so Back from the chat returns to the list.
                onPick = { conversationId ->
                    navController.navigate(Routes.chat(conversationId)) {
                        popUpTo(Routes.CONTACTS) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SHARE) {
            ShareTargetScreen(
                // Abandoning the share clears the inbox so it can't prefill a later chat; the picker
                // always sits over the chat list, so popping returns there.
                onBack = {
                    shareInbox.clear()
                    navController.popBackStack()
                },
                // Open the chosen conversation and drop the picker; ChatScreen drains the inbox into
                // its draft on arrival.
                onPick = { conversationId ->
                    navController.navigate(Routes.chat(conversationId)) {
                        popUpTo(Routes.SHARE) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { backStackEntry ->
            // conversationId is the Nearby room, a peer's node id (a 1:1 DM), or a group id.
            val conversationId =
                backStackEntry.arguments?.getString("conversationId") ?: Conversations.NEARBY
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onOpenProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                onOpenGroupDetails = { id -> navController.navigate(Routes.groupDetails(id)) },
                onOpenMessageDetails = { id -> navController.navigate(Routes.messageDetails(id)) },
                onStartCall = { peerId, peerName -> navController.navigate(Routes.liveCall(peerId, peerName)) },
            )
        }
        composable(
            route = Routes.MESSAGE_DETAILS,
            arguments = listOf(navArgument("messageId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val messageId = backStackEntry.arguments?.getString("messageId") ?: return@composable
            MessageDetailsScreen(
                messageId = messageId,
                onBack = { navController.popBackStack() },
                onOpenProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
            )
        }
        composable(
            route = Routes.PROFILE_DETAILS,
            arguments = listOf(navArgument("nodeId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
            ProfileDetailsScreen(
                nodeId = nodeId,
                onBack = { navController.popBackStack() },
                onMessage = { id ->
                    // Nearby, groups, and DMs all share the chat/{conversationId} destination, so
                    // launchSingleTop here would reuse whatever chat sits under this profile — its
                    // retained ChatViewModel is still bound to that conversation — instead of opening
                    // the peer's DM (the reported "Message just returns to Nearby" bug). If we arrived
                    // straight from this peer's own DM, just return to it so we don't stack a duplicate;
                    // otherwise open it, replacing the profile so Back lands on the chat we came from.
                    val parent = navController.previousBackStackEntry
                    val fromSameDm =
                        parent?.destination?.route == Routes.CHAT &&
                            parent.arguments?.getString("conversationId") == id
                    if (fromSameDm) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.chat(id)) {
                            popUpTo(Routes.PROFILE_DETAILS) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(
            route = Routes.GROUP_DETAILS,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            GroupDetailsScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() },
                onOpenMemberProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                // Leaving deletes the thread, so pop past this screen AND the chat, back to the list.
                onLeft = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) },
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                onBack = { navController.popBackStack() },
                onOpenCrashLog = { navController.navigate(Routes.CRASH_LOG) },
                onOpenTtsTest = { navController.navigate(Routes.TTS_TEST) },
                onOpenTranslationTest = { navController.navigate(Routes.TRANSLATION_TEST) },
                onOpenLanguageInit = { navController.navigate(Routes.LANGUAGE_INIT) },
            )
        }
        composable(Routes.CRASH_LOG) {
            CrashLogScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TTS_TEST) {
            TtsTestScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TRANSLATION_TEST) {
            TranslationTestScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BLOCKED_USERS) {
            BlockedUsersScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.MESSAGE_REQUESTS) {
            MessageRequestsScreen(
                onBack = { navController.popBackStack() },
                // Tapping a request's avatar opens the sender's profile; its Message action accepts the
                // request and opens the DM (see ProfileDetailsScreen.onMessage).
                onOpenProfile = { navController.navigate(Routes.profileDetails(it)) },
                // Accepting drops the user straight into the thread they just accepted — the row is gone
                // from the inbox anyway, so the inbox leaves the back stack and Back lands on the chat
                // list (reachable again via its badge for any remaining requests).
                onOpenConversation = { id ->
                    navController.navigate(Routes.chat(id)) {
                        popUpTo(Routes.MESSAGE_REQUESTS) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.DONATE) {
            DonateScreen(
                onBack = { navController.popBackStack() },
                rateUrl = remember { reviewPrompter.rateUrl() },
            )
        }
        composable(Routes.VERIFY) {
            VerifyContactScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.LIVE_CALL,
            arguments = listOf(
                navArgument("peerId") { type = NavType.StringType },
                navArgument("peerName") { type = NavType.StringType; defaultValue = "Peer" },
            ),
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            val peerName = backStackEntry.arguments?.getString("peerName") ?: "Peer"
            LiveCallScreen(
                peerId = peerId,
                peerName = peerName,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
}


