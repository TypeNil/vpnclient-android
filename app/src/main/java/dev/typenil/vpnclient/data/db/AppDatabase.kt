package dev.typenil.vpnclient.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Full subscription URL — sensitive; never log or export. */
    val url: String,
    val createdAtEpochMs: Long,
    val lastUpdatedAtEpochMs: Long?,
    val lastAttemptAtEpochMs: Long?,
    val lastError: String?,
    val enabled: Boolean = true,
    val userInfoJson: String?,
    val supportUrl: String?,
    val updateIntervalMinutes: Int?,
    /**
     * Explicit per-subscription opt-in for cleartext HTTP fetches.
     * HTTPS is the default; an https→http redirect is never followed.
     */
    @ColumnInfo(defaultValue = "0") val allowInsecureHttp: Boolean = false,
    /** Provider announcement text (Remnawave `announce` header). */
    val announce: String?,
    /** Provider asks the client to refresh this subscription on every launch. */
    @ColumnInfo(defaultValue = "0") val updateAlways: Boolean = false,
    /** Alternate fetch URL tried when the primary is unreachable. */
    val fallbackUrl: String?,
)

@Entity(
    tableName = "nodes",
    indices = [Index("subscriptionId")],
)
data class NodeEntity(
    /** Stable id: parser-computed hash (protocol+server+port+credential). */
    @PrimaryKey val id: String,
    val subscriptionId: Long,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    /** Engine-native outbound object as JSON — opaque to the app. */
    val outboundJson: String,
    /** Original share link when parsed from a URI list — sensitive. */
    val rawUri: String?,
    val position: Int,
)

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY createdAtEpochMs")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>

    @Insert
    suspend fun insert(entity: SubscriptionEntity): Long

    @Update
    suspend fun update(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """UPDATE subscriptions SET
            lastAttemptAtEpochMs = :attemptAt,
            lastError = :error
        WHERE id = :id""",
    )
    suspend fun markAttempt(id: Long, attemptAt: Long, error: String?)

    @Query(
        """UPDATE subscriptions SET
            lastUpdatedAtEpochMs = :updatedAt,
            lastAttemptAtEpochMs = :attemptAt,
            lastError = NULL,
            userInfoJson = :userInfoJson,
            supportUrl = :supportUrl,
            updateIntervalMinutes = :updateIntervalMinutes,
            announce = :announce,
            updateAlways = :updateAlways,
            fallbackUrl = :fallbackUrl
        WHERE id = :id""",
    )
    suspend fun markSuccess(
        id: Long,
        updatedAt: Long,
        attemptAt: Long,
        userInfoJson: String?,
        supportUrl: String?,
        updateIntervalMinutes: Int?,
        announce: String?,
        updateAlways: Boolean,
        fallbackUrl: String?,
    )
}

@Dao
abstract class NodeDao {
    @Query("SELECT * FROM nodes WHERE subscriptionId = :subscriptionId ORDER BY position")
    abstract suspend fun forSubscription(subscriptionId: Long): List<NodeEntity>

    @Query(
        """SELECT * FROM nodes WHERE subscriptionId IN
            (SELECT id FROM subscriptions WHERE enabled = 1)
            ORDER BY subscriptionId, position""",
    )
    abstract fun observeEnabled(): Flow<List<NodeEntity>>

    @Query(
        """SELECT * FROM nodes WHERE subscriptionId IN
            (SELECT id FROM subscriptions WHERE enabled = 1)""",
    )
    abstract suspend fun getEnabled(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE id = :id")
    abstract suspend fun get(id: String): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(nodes: List<NodeEntity>)

    @Query("DELETE FROM nodes WHERE subscriptionId = :subscriptionId")
    abstract suspend fun deleteForSubscription(subscriptionId: Long)

    @Query("SELECT COUNT(*) FROM nodes WHERE subscriptionId = :subscriptionId")
    abstract suspend fun countForSubscription(subscriptionId: Long): Int

    /** Delete + insert as one transaction — a mid-write failure can't
     *  leave a subscription with a partial node list. */
    @Transaction
    open suspend fun replaceForSubscription(subscriptionId: Long, nodes: List<NodeEntity>) {
        deleteForSubscription(subscriptionId)
        upsertAll(nodes)
    }
}

@Database(
    entities = [SubscriptionEntity::class, NodeEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun nodeDao(): NodeDao

    companion object {
        /** v2: per-subscription cleartext opt-in. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions " +
                        "ADD COLUMN allowInsecureHttp INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v3: `updateIntervalMinutes` was reinterpreted hours→minutes — rows
         * written before the change hold raw header hours. NULL them out;
         * the next successful refresh repopulates the column. Also preserve
         * cleartext for existing http:// subscriptions — they were added
         * under a cleartext-allowed regime and would otherwise be stuck with
         * no way to re-enable the opt-in.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE subscriptions SET updateIntervalMinutes = NULL")
                db.execSQL(
                    "UPDATE subscriptions SET allowInsecureHttp = 1 " +
                        "WHERE url LIKE 'http://%'",
                )
            }
        }

        /** v4: provider metadata — announcement, refresh-on-launch, fallback URL. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN announce TEXT")
                db.execSQL(
                    "ALTER TABLE subscriptions " +
                        "ADD COLUMN updateAlways INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN fallbackUrl TEXT")
            }
        }
    }
}
