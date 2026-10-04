package dev.typenil.vpnclient.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppModule registers [AppDatabase.ALL_MIGRATIONS] next to
 * `fallbackToDestructiveMigration()`: any gap in the chain, or a schema
 * version without a step into it, silently wipes the user's subscriptions on
 * upgrade. KSP exports one `<version>.json` per build of the declared
 * `@Database(version)`, so the newest JSON is the version the app ships.
 */
class AppDatabaseMigrationsTest {
    @Test
    fun `migration chain is contiguous from the first schema to the shipped one`() {
        val steps = AppDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }
        steps.zipWithNext { a, b -> assertEquals("gap after ${a.endVersion}", a.endVersion, b.startVersion) }
        steps.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
        assertEquals(1, steps.first().startVersion)

        val dir = File("schemas/dev.typenil.vpnclient.data.db.AppDatabase")
        assertTrue("run from the app module dir", dir.isDirectory)
        val shipped = dir.listFiles { f -> f.extension == "json" }!!.maxOf { it.nameWithoutExtension.toInt() }
        assertEquals("no migration into the shipped schema version", shipped, steps.last().endVersion)
    }
}
