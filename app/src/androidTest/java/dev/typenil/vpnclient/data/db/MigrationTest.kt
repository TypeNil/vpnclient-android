package dev.typenil.vpnclient.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
