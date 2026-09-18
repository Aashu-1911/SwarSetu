package app.swarsetu.di

import android.os.Build
import androidx.room.withTransaction
import app.swarsetu.BuildConfig
import app.swarsetu.data.MeshBlobStore
import app.swarsetu.data.MeshDatabase
import app.swarsetu.data.crypto.IdentityKeyStore

import app.swarsetu.mesh.CompositeMeshTransport
import app.swarsetu.mesh.MeshController
import app.swarsetu.mesh.MeshManager
import app.swarsetu.mesh.MeshMetrics
import app.swarsetu.mesh.MeshTransport
import app.swarsetu.mesh.StoreDigest
import app.swarsetu.mesh.bluetooth.BluetoothMeshTransport
import app.swarsetu.mesh.crypto.MessageCrypto
import app.swarsetu.mesh.crypto.ratchet.GroupRatchetSessions
import app.swarsetu.mesh.crypto.ratchet.RatchetSessions
import app.swarsetu.mesh.crypto.ratchet.SessionTransactor
import app.swarsetu.mesh.meshExceptionHandler
import app.swarsetu.mesh.power.PowerMonitor
import app.swarsetu.mesh.power.PowerStateSource

import app.swarsetu.mesh.wifiaware.WifiAwareTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/** Qualifier for the single shared ratchet [Mutex] (DM + group session services). */
private val ratchetMutex = named("ratchetMutex")

val meshModule =
    module {
        // Application-lifetime scope for the mesh engine. The shared exception handler is the process-level
        // backstop for an uncaught throw in a top-level child (e.g. a FramedLink writer coroutine).
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default + meshExceptionHandler) }
        single { MeshMetrics() }
        // Content digest of this node's syncable state; shared between the forward-store impl (maintains the
        // message set), MeshManager (folds in profile changes), and WifiAwareTransport (cues it to neighbors) —
        // a singleton so none has to construct the other (MeshManager already depends on the transport).
        single { StoreDigest() }
        // Tracks screen/charge/battery state and feeds it to the transport's discovery duty cycle.
        single { PowerStateSource() }
        single { PowerMonitor(androidContext(), get()) }
        // Bridges the mesh blob-exchange to the encrypted DB; materializes transfer temp files under cacheDir.
        single { MeshBlobStore(get(), get(), get(), File(androidContext().cacheDir, "blobtx")) }
        // Demo-screenshot builds (debug-only, `-PseedDemo=true`) swap in a no-op transport that just reports a
        // few connected neighbors (so the UI looks "connected" against the seeded data); the seam returns null
        // in release, where the demo classes don't ship (see the per-variant di/DemoWiring). Production wraps
        // every hardware-supported plane in a CompositeMeshTransport behind the single-transport seam —
        // Bluetooth LE and Wi-Fi Aware, in descending send-preference. Each plane is gated on isSupported() so
        // an unsupported one is simply absent (a device with neither yields an inert, Degraded composite).
        single<MeshTransport> {
            demoTransportOrNull() ?: run {
                val ctx = androidContext()
                val children =
                    buildList {
                        val btSupported = BluetoothMeshTransport.isSupported(ctx)
                        val awareSupported =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WifiAwareTransport.isSupported(ctx)

                        android.util.Log.i(
                            "MeshModule",
                            "Hardware probe -> Bluetooth LE: $btSupported, Wi-Fi Aware: $awareSupported (SDK: ${Build.VERSION.SDK_INT}, Model: ${Build.MANUFACTURER} ${Build.MODEL})"
                        )

                        // Descending send-preference: Bluetooth (persistent links) first, then Wi-Fi Aware (ephemeral).
                        if (btSupported) {
                            add(BluetoothMeshTransport(ctx, get(), get(), get(), get(), get()))
                        }
                        // WifiAwareTransport is @RequiresApi(31) (its NDP accept-any responder is API 31).
                        if (awareSupported) {
                            add(WifiAwareTransport(ctx, get(), get(), get(), get(), get()))
                        }
                    }
                CompositeMeshTransport(children, get(), get()) { msg ->
                    android.util.Log.d("CompositeMeshTransport", msg)
                }
            }
        }
        // The E2E message cipher, built from this device's identity private keysets.
        single {
            val keys = get<IdentityKeyStore>().keys()
            MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
        }
        // THE ratchet lock, shared by the DM and group session services: group seed adoption runs inside
        // a DM commit, so one instance makes the lock-order question vanish by construction.
        single(ratchetMutex) { Mutex() }
        // The other half of that contract: the transaction both services open BEFORE taking the lock, so
        // every critical section acquires the DB and the mutex in one global order. Room's withTransaction
        // is reentrant per coroutine, so the decrypt path (which already wraps its commit) just joins.
        single<SessionTransactor> {
            val db = get<MeshDatabase>()
            object : SessionTransactor {
                override suspend fun <T> transact(block: suspend () -> T): T = db.withTransaction { block() }
            }
        }
        // The DM epoch-ratchet session service (crypto scheme v2). Identity access is lambda-mediated so
        // the service itself stays Android-free and plain-JVM-testable; the store is the Room-backed
        // RatchetStore bound in appModule.
        single {
            val identityKeys = get<IdentityKeyStore>()
            RatchetSessions(
                store = get(),
                dhIdentityPriv = identityKeys::dhIdentityPrivate,
                spkPrivFor = identityKeys::prekeyPrivFor,
                mutex = get(ratchetMutex),
                transact = get(),
            )
        }
        // The group sender-key session service (crypto scheme v2's group form, docs/GROUP_FORWARD_SECRECY.md).
        single { GroupRatchetSessions(store = get(), mutex = get(ratchetMutex), transact = get()) }
        // The Internet (spool) plane's socket factory. Cleartext `ws://` is a debug-build affordance for a
        // LAN daemon (which terminates no TLS of its own); release and staging accept `wss://` only, so a
        // shipped build cannot be pointed at a plaintext relay. The plane itself stays dark until the user
        // opts in AND configures a spool — see SettingsStore.spoolEnabled.

        // Constructor order: transport, messages, receipts, groups, reactions, peers, identity, settings,
        // blobs, imageScreening, blobStore, forwardStore, notifier, textModeration, messageCrypto, ratchet,
        // groupRatchet, groupRoots, scope, metrics, db, spoolDialer.
        single {
            MeshManager(
                transport = get(),
                messages = get(),
                receipts = get(),
                groups = get(),
                reactions = get(),
                peers = get(),
                identity = get(),
                settings = get(),
                blobs = get(),
                imageScreening = get(),
                blobStore = get(),
                forwardStore = get(),
                notifier = get(),
                voiceMessageReceiver = getOrNull(),
                textModeration = get(),
                messageCrypto = get(),
                ratchet = get(),
                groupRatchet = get(),

                scope = get(),
                metrics = get(),
                db = get(),

            )
        }
        // UI ViewModels, MeshService, and the notification/debug entry points bind this narrow facade (not
        // the concrete orchestrator) so they can be tested against a fake; the same singleton backs both keys.
        single<MeshController> { get<MeshManager>() }

    }
