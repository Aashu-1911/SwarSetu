package app.swarsetu.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.swarsetu.data.blob.BlobDao
import app.swarsetu.data.blob.BlobEntity
import app.swarsetu.data.blob.BlobVerdictDao
import app.swarsetu.data.blob.BlobVerdictEntity
import app.swarsetu.data.forward.ForwardDao
import app.swarsetu.data.forward.ForwardEntity
import app.swarsetu.data.group.GroupDao
import app.swarsetu.data.group.GroupEntity
import app.swarsetu.data.message.MessageDao
import app.swarsetu.data.message.MessageEntity
import app.swarsetu.data.peer.PeerDao
import app.swarsetu.data.peer.PeerEntity
import app.swarsetu.data.ratchet.GroupKeySendEntity
import app.swarsetu.data.ratchet.GroupRatchetDao
import app.swarsetu.data.ratchet.GroupRecvChainEntity
import app.swarsetu.data.ratchet.GroupRootDao
import app.swarsetu.data.ratchet.GroupRootEntity
import app.swarsetu.data.ratchet.GroupSendChainEntity
import app.swarsetu.data.ratchet.GroupSkippedKeyEntity
import app.swarsetu.data.ratchet.RatchetDao
import app.swarsetu.data.ratchet.RatchetLocalEpochEntity
import app.swarsetu.data.ratchet.RatchetRecvEpochEntity
import app.swarsetu.data.ratchet.RatchetSessionEntity
import app.swarsetu.data.ratchet.RatchetSkippedKeyEntity
import app.swarsetu.data.reaction.ReactionDao
import app.swarsetu.data.reaction.ReactionEntity
import app.swarsetu.data.receipt.MessageReceiptDao
import app.swarsetu.data.receipt.MessageReceiptEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MessageEntity::class, PeerEntity::class, ReactionEntity::class, BlobEntity::class,
        GroupEntity::class, BlobVerdictEntity::class, ForwardEntity::class,
        RatchetSessionEntity::class, RatchetLocalEpochEntity::class,
        RatchetRecvEpochEntity::class, RatchetSkippedKeyEntity::class,
        GroupSendChainEntity::class, GroupRecvChainEntity::class,
        GroupSkippedKeyEntity::class, GroupKeySendEntity::class,
        GroupRootEntity::class, MessageReceiptEntity::class,
    ],
    // v1: frozen launch baseline. The pre-1.0 alpha schema churn (the old destructive v2…v22 bumps that
    //     rode the wire/crypto breaks) is collapsed; docs/WIRE_COMPAT.md keeps the historical break record.
    //     From v1 on, every @Database bump ships a tested MeshMigrations entry — a missing one throws at open
    //     time (caught by MeshDatabaseMigrationTest), never a silent wipe of a user's messages/custody/pins.
    // v2: the ratchet schemes, one never-released bump — DM epoch-ratchet state (4 ratchet_* tables),
    //     group sender-key state (4 group_* tables: send/recv chains, skipped keys, the seed outbox),
    //     and the peers prekey columns (docs/FORWARD_SECRECY_RATCHET.md +
    //     docs/GROUP_FORWARD_SECRECY.md); migrated by MeshMigrations.MIGRATION_1_2.
    // v3: the spool plane's group scopes — one `group_roots` table holding the shared group root the group
    //     scope id and seal keys derive from (docs/SPOOL_PROTOCOL.md §3.2); no wire break, local state only,
    //     migrated by MeshMigrations.MIGRATION_2_3.
    // v4: one `messages.receivedVia` column — the DeliveryPlane code of the receipt that flipped the tick, so
    //     the ✓✓ can say the message got there over the Internet; migrated by MeshMigrations.MIGRATION_3_4.
    // v5: two `messages` columns describing a voice-note attachment — `voiceDurationMs` and the Base64
    //     `voicePeaks` waveform. Purely local presentation state derived from the audio by VoiceAudio on both
    //     the sending and receiving side, so voice notes need no wire field at all; null on every existing
    //     row, which is honest (a pre-upgrade voice note simply re-derives them when next played);
    //     migrated by MeshMigrations.MIGRATION_4_5.
    // v6: one `message_receipts` table — who has acked each message (the message-details screen's
    //     "delivered to / waiting on" split for a group send). Local bookkeeping only: the acker was always
    //     on the wire as the receipt's authenticated senderId, the tick's "≥1 recipient" semantic is
    //     unchanged, and no digest folds over it; migrated by MeshMigrations.MIGRATION_5_6.
    // v7: two `messages` columns describing a voice text message — `voiceTextLanguage` and `isAlert`.
    //     Used for the offline STT -> Mesh -> TTS pipeline.
    // v8: Semantic translation message types + peer preferred language.
    //     Added messageType, sourceLanguage, targetLanguage, sourceText, translatedText to `messages`.
    //     Added preferredLanguage to `peers`.
    // v9: Location fields for Offline SOS feature.
    version = 9,
    // Export the schema JSON to app/schemas/ (location set by the androidx.room Gradle plugin's
    // room { schemaDirectory(...) } in app/build.gradle.kts). Keeps the schema diffable in review and feeds
    // the migration test's MigrationTestHelper. Room also errors at compile time if an entity changes without
    // a version bump.
    exportSchema = true,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun peerDao(): PeerDao

    abstract fun reactionDao(): ReactionDao

    abstract fun blobDao(): BlobDao

    abstract fun groupDao(): GroupDao

    abstract fun blobVerdictDao(): BlobVerdictDao

    abstract fun forwardDao(): ForwardDao

    abstract fun ratchetDao(): RatchetDao

    abstract fun groupRatchetDao(): GroupRatchetDao

    abstract fun groupRootDao(): GroupRootDao

    abstract fun messageReceiptDao(): MessageReceiptDao

    companion object {
        /**
         * Builds the encrypted database. [passphrase] is the SQLCipher key (see
         * [app.swarsetu.data.crypto.DatabaseKey]); SQLCipher zeroes it once the DB is opened.
         * The native `libsqlcipher.so` must be loaded explicitly before the factory is created.
         */
        @Suppress("SpreadOperator") // vararg Room migrations API; a one-time DB-init copy
        fun build(
            context: Context,
            passphrase: ByteArray,
        ): MeshDatabase {
            System.loadLibrary("sqlcipher")
            return Room
                .databaseBuilder(context, MeshDatabase::class.java, "knit.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // Production migration posture: v1 is the frozen launch baseline, with NO destructive fallback.
                // Every schema change from here ships a tested MeshMigrations entry; a version bump with no
                // matching migration makes Room throw at open time (caught by MeshDatabaseMigrationTest) — a loud
                // failure in CI, never a silent wipe of a user's messages/custody/pins in production.
                .addMigrations(*MeshMigrations.ALL)
                .build()
        }
    }
}
