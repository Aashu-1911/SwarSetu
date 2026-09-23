package app.swarsetu.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.swarsetu.crash.CrashReports
import app.swarsetu.crash.crashStore
import app.swarsetu.data.AttachmentStore
import app.swarsetu.data.AvatarStore
import app.swarsetu.data.BlobRepository
import app.swarsetu.data.GallerySaver
import app.swarsetu.data.GroupRepository
import app.swarsetu.data.MeshDatabase
import app.swarsetu.data.MessageReceiptRepository
import app.swarsetu.data.MessageRepository
import app.swarsetu.data.PeerRepository
import app.swarsetu.data.ReactionRepository
import app.swarsetu.data.crypto.DatabaseKey
import app.swarsetu.data.crypto.IdentityKeyStore
import app.swarsetu.data.crypto.KeystoreSecret
import app.swarsetu.data.forward.ForwardRepository
import app.swarsetu.data.ratchet.GroupRatchetRepository

import app.swarsetu.data.ratchet.RatchetRepository
import app.swarsetu.data.settings.SettingsStore
import app.swarsetu.demo.DemoComposer
import app.swarsetu.identity.AndroidDeviceIdSource
import app.swarsetu.identity.DeviceIdSource
import app.swarsetu.identity.Identity
import app.swarsetu.mesh.ForwardStore
import app.swarsetu.mesh.crypto.ratchet.GroupRatchetStore
import app.swarsetu.mesh.crypto.ratchet.RatchetStore

import app.swarsetu.notifications.MessageNotifier
import app.swarsetu.notifications.Notifier
import app.swarsetu.review.ReviewPrompter
import app.swarsetu.translation.TranslatorEngine
import app.swarsetu.translation.indictrans.IndicTransModelManager
import app.swarsetu.ui.RouteInbox
import app.swarsetu.ui.review.ReviewPromptInbox
import app.swarsetu.ui.share.ShareInbox
import app.swarsetu.ui.voice.VoicePlayer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create {
                androidContext().preferencesDataStoreFile("knit_settings")
            }
        }
        single { SettingsStore(get()) }
        // Stable per-device id (ANDROID_ID) — seeds the soft block-continuity DeviceTag, not the nodeId.
        single<DeviceIdSource> { AndroidDeviceIdSource(androidContext()) }
        // E2E identity keypair, wrapped under a hardware AndroidKeyStore key in filesDir (outside the DB).
        single { IdentityKeyStore(KeystoreSecret(androidContext(), "knit_identity_key", "identity.key")) }
        // nodeId is derived from the keypair's public bundle; the device id only feeds the block tag.
        single { Identity(get(), get()) }
        single { AvatarStore(androidContext(), get()) }
        single { AttachmentStore(androidContext(), get(), get()) }
        single { GallerySaver(androidContext()) }
        // One voice player for the whole app: any number of voice-note bubbles can be on screen, and
        // starting one note has to stop whichever was playing. Owns its own scope (see VoicePlayer).
        single { VoicePlayer(androidContext(), get()) }
        single<Notifier> { MessageNotifier(androidContext()) }
        // Single-shot handoff for content arriving via the system share sheet (ACTION_SEND).
        single { ShareInbox() }
        // Debug trailer seam driving the real Nearby composer (see DemoComposer). Inert in every build
        // unless the debug DemoDirector emits into it; R8 strips it from release.
        single { DemoComposer() }
        // Single-shot handoff for a notification-tap deep-link route (drained by SwarSetuApp).
        single { RouteInbox() }
        // Single-shot signal that the rate/review prompt should show (drained by SwarSetuApp).
        single { ReviewPromptInbox() }
        // Decides when to ask for an app rating and where to route it (installer-aware); no-op in demo builds.
        single { ReviewPrompter(androidContext(), get(), get(), get(), get()) }
        single { IndicTransModelManager(androidContext()) }
        single { TranslatorEngine(androidContext(), get()) }
        single { DatabaseKey(androidContext()) }
        single { MeshDatabase.build(androidContext(), get<DatabaseKey>().getOrCreate()) }
        single { get<MeshDatabase>().messageDao() }
        single { get<MeshDatabase>().peerDao() }
        single { get<MeshDatabase>().reactionDao() }
        single { get<MeshDatabase>().blobDao() }
        single { get<MeshDatabase>().groupDao() }
        single { get<MeshDatabase>().blobVerdictDao() }
        single { get<MeshDatabase>().forwardDao() }
        single { get<MeshDatabase>().ratchetDao() }
        single { get<MeshDatabase>().groupRatchetDao() }
        single { get<MeshDatabase>().groupRootDao() }
        single { get<MeshDatabase>().messageReceiptDao() }
        single { MessageRepository(get()) }
        single { PeerRepository(get()) }
        // Crash reports. The capture-side CrashStore is built by hand in SwarSetuApplication.onCreate BEFORE
        // startKoin, so a crash inside startup itself is still captured; this is the reader side over the
        // same fixed directory (crashStore() is the single definition of the path, so the two can't drift).
        // Two instances is deliberate and harmless — CrashStore holds no state beyond that File.
        single { crashStore(androidContext()) }
        // Applies the known-contact-name redaction pass the dying handler couldn't run (the names live in
        // the encrypted DB and DataStore) and stages the share copy under cacheDir/crash.
        single { CrashReports(androidContext(), get(), get(), get(), get()) }
        single { ReactionRepository(get(), get()) }
        // Who has acked each message — the message-details screen's per-recipient delivery split. Owns the
        // delivery write (tick + acker row in one transaction), so it wraps MessageRepository.
        single { MessageReceiptRepository(get(), get(), get()) }
        // BlobRepository: blobDao, messageDao, peerDao, settings, blobVerdictDao, groupDao, forwardDao, db.
        single { BlobRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
        single { GroupRepository(get(), get(), get(), get()) }
        // Store-and-forward custody for DMs, backed by the encrypted forward_store table. Takes the shared
        // StoreDigest (from meshModule) so every carry-store mutation keeps the cue-plane content digest in sync,
        // plus the MeshDatabase so store/remove/sweep run their DB writes in a transaction under the repo mutex.
        single<ForwardStore> { ForwardRepository(get(), get(), get()) }
        // DM epoch-ratchet session state (docs/FORWARD_SECRECY_RATCHET.md), in the encrypted DB so the
        // ratchet advance commits in the same transaction as the message row it decrypted/sealed.
        single<RatchetStore> { RatchetRepository(get()) }
        // Group sender-key ratchet state (docs/GROUP_FORWARD_SECRECY.md), same transactional posture.
        single<GroupRatchetStore> { GroupRatchetRepository(get()) }
    }
