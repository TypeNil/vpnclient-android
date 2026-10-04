package dev.typenil.vpnclient.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Room migration coverage. 1→2 adds the cleartext opt-in column; 2→3 clears
 * the reinterpreted provider interval and preserves cleartext for legacy
 * http:// subscriptions. Fixture URLs are synthetic — never real endpoints.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun insertV1Row(db: androidx.sqlite.db.SupportSQLiteDatabase, url: String) {
        db.execSQL(
            """INSERT INTO subscriptions
                (name, url, createdAtEpochMs, lastUpdatedAtEpochMs,
                 lastAttemptAtEpochMs, lastError, enabled, userInfoJson,
                 supportUrl, updateIntervalMinutes)
               VALUES ('sub', ?, 1, NULL, NULL, NULL, 1, NULL, NULL, 12)""",
            arrayOf(url),
        )
    }

    @Test
    fun migrate1To8PreservesLegacyData() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Row(db, "http://127.0.0.1:9/synthetic")
            db.execSQL(
                """INSERT INTO nodes
                    (id, subscriptionId, name, protocol, server, port,
                     outboundJson, rawUri, position)
                   VALUES ('n1', 1, 'synthetic', 'VLESS', '192.0.2.1', 443, '{}', NULL, 0)""",
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 8, true, *AppDatabase.ALL_MIGRATIONS,
        ).use { db ->
            db.query(
                "SELECT name, createdAtEpochMs, allowInsecureHttp, updateIntervalMinutes, " +
                    "refreshPolicy, refreshFixedMinutes, updateAlways FROM subscriptions",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("sub", c.getString(0))
                assertEquals(1L, c.getLong(1))
                assertEquals(1, c.getInt(2))
                assertTrue(c.isNull(3))
                assertEquals("inherit", c.getString(4))
                assertTrue(c.isNull(5))
                assertEquals(0, c.getInt(6))
            }
            db.query("SELECT server, port FROM nodes WHERE id = 'n1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("192.0.2.1", c.getString(0))
                assertEquals(443, c.getInt(1))
            }
            for (table in listOf("node_preferences", "routing_rules", "node_latency")) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0))
                }
            }
        }
    }

    @Test
    fun migrate1To3PreservesLegacyHttpOptIn() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            insertV1Row(db, "http://127.0.0.1:9/sub")
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 3, true,
            AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
        ).use { db ->
            db.query(
                "SELECT allowInsecureHttp, updateIntervalMinutes FROM subscriptions",
            ).use { c ->
                assertTrue(c.moveToFirst())
                // Legacy http row keeps working — opt-in is restored by 2→3.
                assertEquals(1, c.getInt(0))
                // Raw provider hours must not survive as "minutes".
                assertTrue(c.isNull(1))
            }
        }
    }

    @Test
    fun migrate2To3DoesNotOptInHttps() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """INSERT INTO subscriptions
                    (name, url, createdAtEpochMs, lastUpdatedAtEpochMs,
                     lastAttemptAtEpochMs, lastError, enabled, userInfoJson,
                     supportUrl, updateIntervalMinutes, allowInsecureHttp)
                   VALUES ('sub', ?, 1, NULL, NULL, NULL, 1, NULL, NULL, 12, 0)""",
                arrayOf("https://127.0.0.1:9/sub"),
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 3, true, AppDatabase.MIGRATION_2_3,
        ).use { db ->
            db.query(
                "SELECT allowInsecureHttp, updateIntervalMinutes FROM subscriptions",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertTrue(c.isNull(1))
            }
        }
    }

    @Test
    fun migrate3To4AddsProviderMetadataColumns() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """INSERT INTO subscriptions
                    (name, url, createdAtEpochMs, lastUpdatedAtEpochMs,
                     lastAttemptAtEpochMs, lastError, enabled, userInfoJson,
                     supportUrl, updateIntervalMinutes, allowInsecureHttp)
                   VALUES ('sub', ?, 1, NULL, NULL, NULL, 1, NULL, NULL, 12, 0)""",
                arrayOf("https://127.0.0.1:9/sub"),
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 4, true, AppDatabase.MIGRATION_3_4,
        ).use { db ->
            db.query(
                "SELECT announce, updateAlways, fallbackUrl FROM subscriptions",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertEquals(0, c.getInt(1))
                assertTrue(c.isNull(2))
            }
        }
    }

    @Test
    fun migrate4To5CreatesNodePreferencesTable() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """INSERT INTO subscriptions
                    (name, url, createdAtEpochMs, lastUpdatedAtEpochMs,
                     lastAttemptAtEpochMs, lastError, enabled, userInfoJson,
                     supportUrl, updateIntervalMinutes, allowInsecureHttp,
                     announce, updateAlways, fallbackUrl)
                   VALUES ('sub', ?, 1, NULL, NULL, NULL, 1, NULL, NULL, 12, 0,
                           NULL, 0, NULL)""",
                arrayOf("https://127.0.0.1:9/sub"),
            )
            db.execSQL(
                """INSERT INTO nodes
                    (id, subscriptionId, name, protocol, server, port,
                     outboundJson, rawUri, position)
                   VALUES ('n1', 1, 'n', 'VLESS', '127.0.0.1', 443, '{}', NULL, 0)""",
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 5, true, AppDatabase.MIGRATION_4_5,
        ).use { db ->
            // The table exists with the expected defaults — a row written
            // with only the PK must read back as all-defaults.
            db.execSQL("INSERT INTO node_preferences (nodeId) VALUES ('n1')")
            db.query(
                "SELECT isFavorite, isEnabled, customName, isHidden " +
                    "FROM node_preferences WHERE nodeId = 'n1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals(1, c.getInt(1))
                assertTrue(c.isNull(2))
                assertEquals(0, c.getInt(3))
            }
            // Pre-existing node rows are untouched by the migration.
            db.query("SELECT COUNT(*) FROM nodes").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate5To6CreatesRoutingRulesTable() {
        helper.createDatabase(TEST_DB, 5)
        helper.runMigrationsAndValidate(
            TEST_DB, 6, true, AppDatabase.MIGRATION_5_6,
        ).use { db ->
            db.execSQL(
                """INSERT INTO routing_rules
                    (kind, pattern, action, orderIndex)
                   VALUES ('domain', 'example.com', 'proxy', 0)""",
            )
            db.query(
                "SELECT kind, pattern, action, orderIndex, isEnabled " +
                    "FROM routing_rules",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("domain", c.getString(0))
                assertEquals("example.com", c.getString(1))
                assertEquals("proxy", c.getString(2))
                assertEquals(0, c.getInt(3))
                assertEquals(1, c.getInt(4))
            }
        }
    }

    @Test
    fun migrate6To7AddsRefreshPolicyColumns() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """INSERT INTO subscriptions
                    (name, url, createdAtEpochMs, lastUpdatedAtEpochMs,
                     lastAttemptAtEpochMs, lastError, enabled, userInfoJson,
                     supportUrl, updateIntervalMinutes, allowInsecureHttp,
                     announce, updateAlways, fallbackUrl)
                   VALUES ('sub', ?, 1, NULL, NULL, NULL, 1, NULL, NULL, 12, 0,
                           NULL, 1, NULL)""",
                arrayOf("https://127.0.0.1:9/sub"),
            )
            // Node rows, per-node prefs, and routing rules must survive the
            // subscription-table ALTER unchanged.
            db.execSQL(
                """INSERT INTO nodes
                    (id, subscriptionId, name, protocol, server, port,
                     outboundJson, rawUri, position)
                   VALUES ('n1', 1, 'n', 'VLESS', '127.0.0.1', 443, '{}',
                           NULL, 0)""",
            )
            db.execSQL(
                """INSERT INTO node_preferences
                    (nodeId, isFavorite, isEnabled, customName, isHidden)
                   VALUES ('n1', 1, 1, 'fav', 0)""",
            )
            db.execSQL(
                """INSERT INTO routing_rules
                    (kind, pattern, action, orderIndex, isEnabled)
                   VALUES ('domain', 'example.com', 'proxy', 0, 1)""",
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 7, true, AppDatabase.MIGRATION_6_7,
        ).use { db ->
            db.query(
                "SELECT refreshPolicy, refreshFixedMinutes, updateAlways " +
                    "FROM subscriptions",
            ).use { c ->
                assertTrue(c.moveToFirst())
                // Legacy rows inherit — the same behavior they had pre-v7.
                assertEquals("inherit", c.getString(0))
                assertTrue(c.isNull(1))
                // Unrelated columns survive the migration untouched.
                assertEquals(1, c.getInt(2))
            }
            // Payload in the other tables is preserved, not just counted.
            db.query(
                "SELECT server, port FROM nodes WHERE id = 'n1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("127.0.0.1", c.getString(0))
                assertEquals(443, c.getInt(1))
            }
            db.query(
                "SELECT isFavorite, customName FROM node_preferences " +
                    "WHERE nodeId = 'n1'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals("fav", c.getString(1))
            }
            db.query(
                "SELECT pattern, action FROM routing_rules",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("example.com", c.getString(0))
                assertEquals("proxy", c.getString(1))
            }
            // The new columns are writable — a fixed policy persists.
            db.execSQL(
                "UPDATE subscriptions SET refreshPolicy = 'fixed', " +
                    "refreshFixedMinutes = 30",
            )
            db.query(
                "SELECT refreshPolicy, refreshFixedMinutes FROM subscriptions",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("fixed", c.getString(0))
                assertEquals(30, c.getInt(1))
            }
        }
    }

    @Test
    fun migrate7To8AddsNodeLatencyAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 7).use { db -> seedV7(db) }
        helper.runMigrationsAndValidate(
            TEST_DB, 8, true, AppDatabase.MIGRATION_7_8,
        ).use { db ->
            assertV7RowsIntact(db)
            db.query("SELECT COUNT(*) FROM node_latency").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            // Null latency = failed probe; both columns round-trip.
            db.execSQL(
                "INSERT INTO node_latency VALUES ('n1', NULL, 1700000000000, 'proxy')",
            )
            db.query("SELECT latencyMs, checkedAtEpochMs, method FROM node_latency").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertEquals(1700000000000L, c.getLong(1))
                assertEquals("proxy", c.getString(2))
            }
        }
    }

    /**
     * The upgrade the owner's device performs: the production builder with
     * [AppDatabase.ALL_MIGRATIONS] and NO destructive fallback (AppModule
     * adds one as a last resort — here a missing step would throw instead of
     * silently wiping). A v7 file with real-shaped rows must open at v8 with
     * every row intact.
     */
    @Test
    fun v7FileOpensThroughProductionChainWithoutDestructiveFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        helper.createDatabase(TEST_DB, 7).use { db -> seedV7(db) }
        val room =
            Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .build()
        try {
            runBlocking {
                assertEquals(1, room.subscriptionDao().getAll().size)
                assertEquals(443, room.nodeDao().get("n1")?.port)
                assertEquals("fav", room.nodePreferenceDao().get("n1")?.customName)
                assertEquals(1, room.routingRuleDao().getAll().size)
                room.nodeLatencyDao().record(
                    listOf(NodeLatencyEntity("n1", 123, 1L, "tcp"), NodeLatencyEntity("ghost", 1, 1L, "tcp")),
                )
                // The existing node's verdict is visible; the orphan was swept.
                assertEquals(
                    listOf("n1"),
                    room.nodeLatencyDao().observeLive().first().map { it.nodeId },
                )
            }
        } finally {
            room.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    private fun seedV7(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """INSERT INTO subscriptions
                (name, url, createdAtEpochMs, lastUpdatedAtEpochMs,
                 lastAttemptAtEpochMs, lastError, enabled, userInfoJson,
                 supportUrl, updateIntervalMinutes, allowInsecureHttp,
                 announce, updateAlways, fallbackUrl, refreshPolicy,
                 refreshFixedMinutes)
               VALUES ('sub', ?, 1, NULL, NULL, NULL, 1, NULL, NULL, 12, 0,
                       NULL, 0, NULL, 'fixed', 30)""",
            arrayOf("https://127.0.0.1:9/sub"),
        )
        db.execSQL(
            """INSERT INTO nodes
                (id, subscriptionId, name, protocol, server, port,
                 outboundJson, rawUri, position)
               VALUES ('n1', 1, 'n', 'VLESS', '127.0.0.1', 443, '{}', NULL, 0)""",
        )
        db.execSQL(
            """INSERT INTO node_preferences
                (nodeId, isFavorite, isEnabled, customName, isHidden)
               VALUES ('n1', 1, 1, 'fav', 0)""",
        )
        db.execSQL(
            """INSERT INTO routing_rules
                (kind, pattern, action, orderIndex, isEnabled)
               VALUES ('domain', 'example.com', 'proxy', 0, 1)""",
        )
    }

    private fun assertV7RowsIntact(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT refreshPolicy, refreshFixedMinutes FROM subscriptions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fixed", c.getString(0))
            assertEquals(30, c.getInt(1))
        }
        db.query("SELECT server, port FROM nodes WHERE id = 'n1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(443, c.getInt(1))
        }
        db.query("SELECT customName FROM node_preferences WHERE nodeId = 'n1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fav", c.getString(0))
        }
        db.query("SELECT pattern FROM routing_rules").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("example.com", c.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
