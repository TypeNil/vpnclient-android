package dev.typenil.vpnclient

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restore-only recovery path and policy check for `VpnTestRunner`'s
 * unconditional BootReceiver isolation. Runnable under the runner's normal
 * invocation (it needs
 * only app-context `PackageManager`, never touches a Hilt component):
 *
 *   am instrument -w -e class dev.typenil.vpnclient.R04ReceiverIsolationRestoreTest \
 *     dev.typenil.vpnclient.test/dev.typenil.vpnclient.VpnTestRunner
 *
 * Parent invokes it after a hard-killed isolated run: a residue record's
 * recorded original state must be applied verbatim, and the record policy
 * (exact apply, idempotence, `.bak` recovery, `.new` non-promotion,
 * malformed-record retention) is verified.
 */
@RunWith(AndroidJUnit4::class)
class R04ReceiverIsolationRestoreTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val receiver
        get() = ComponentName(
            context.packageName,
            "dev.typenil.vpnclient.core.vpn.BootReceiver",
        )

    /** Applies any real residue verbatim, then drops remaining artifacts —
     *  restores genuine state before synthetic setup, never clobbers it. */
    private fun recoverThenClear() {
        R04ReceiverIsolation.restore(context)
        R04ReceiverIsolation.recordNewFile(context).delete()
    }

    @Test
    fun restoreAppliesRecordedStateExactlyAndIsIdempotent() {
        val pm = context.packageManager

        // Recovery semantics: residue record applied verbatim on first call.
        val recorded = R04ReceiverIsolation.readRecord(context)
        R04ReceiverIsolation.restore(context)
        if (recorded != null) {
            assertEquals(
                "recorded original state must be applied verbatim",
                recorded.toInt(), pm.getComponentEnabledSetting(receiver),
            )
        }
        assertFalse(
            "record must be deleted after a verified restore",
            R04ReceiverIsolation.recordFile(context).exists(),
        )

        // Policy: a record carrying the current value restores to that exact
        // value and cleans up.
        val settled = pm.getComponentEnabledSetting(receiver)
        R04ReceiverIsolation.writeRecord(context, settled)
        R04ReceiverIsolation.restore(context)
        assertEquals(
            "restored state must equal the recorded value",
            settled, pm.getComponentEnabledSetting(receiver),
        )
        assertFalse(R04ReceiverIsolation.recordFile(context).exists())

        // Idempotent: second restore with no record is a no-op.
        R04ReceiverIsolation.restore(context)
        assertEquals(settled, pm.getComponentEnabledSetting(receiver))

        // Malformed record: fixed-label failure, record kept — never guessed.
        val file = R04ReceiverIsolation.recordFile(context)
        try {
            file.writeBytes("malformed".toByteArray(Charsets.UTF_8))
            var thrown: Throwable? = null
            try {
                R04ReceiverIsolation.restore(context)
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("malformed record must fail", thrown)
            assertTrue(file.exists())
            assertEquals(settled, pm.getComponentEnabledSetting(receiver))
        } finally {
            file.delete()
        }
    }

    /**
     * Legacy `.bak`-only recovery: `AtomicFile.openRead` recovers `.bak` into
     * base (`.bak` wins), so a record that exists only as a backup must still
     * apply verbatim — then all artifacts are removed.
     */
    @Test
    fun backupOnlyRecordIsRecoveredAndCleaned() {
        val pm = context.packageManager
        recoverThenClear()
        val settled = pm.getComponentEnabledSetting(receiver)

        R04ReceiverIsolation.writeRecord(context, settled)
        val base = R04ReceiverIsolation.recordFile(context)
        val bak = R04ReceiverIsolation.recordBackupFile(context)
        bak.delete() // ensure rename target is free
        assertTrue("base must rename to .bak", base.renameTo(bak))
        assertFalse(base.exists())

        R04ReceiverIsolation.restore(context)

        assertEquals(
            ".bak-only record must restore the recorded value",
            settled, pm.getComponentEnabledSetting(receiver),
        )
        assertFalse("base must be removed", base.exists())
        assertFalse(".bak must be removed", bak.exists())
        assertFalse(
            ".new must be removed",
            R04ReceiverIsolation.recordNewFile(context).exists(),
        )
    }

    /**
     * Stale `.new` in-flight artifact: never promoted to a record — readRecord
     * reports no record and removes it; the receiver state is untouched.
     */
    @Test
    fun staleNewArtifactIsDroppedAndNeverPromoted() {
        val pm = context.packageManager
        recoverThenClear()
        val settled = pm.getComponentEnabledSetting(receiver)

        val newFile = R04ReceiverIsolation.recordNewFile(context)
        // Even a valid-looking state in .new must not be promoted.
        newFile.writeBytes(
            "$settled".toByteArray(Charsets.UTF_8),
        )
        assertNull(
            ".new alone must not read as a record",
            R04ReceiverIsolation.readRecord(context),
        )
        assertFalse("stale .new must be removed by readRecord", newFile.exists())
        assertEquals(settled, pm.getComponentEnabledSetting(receiver))

        // Nothing recorded → restore is a no-op.
        R04ReceiverIsolation.restore(context)
        assertEquals(settled, pm.getComponentEnabledSetting(receiver))
    }

    /**
     * Malformed `.bak`-only record: restore must fail with a fixed label,
     * keep the record (recovered into base by openRead), and leave the
     * receiver state untouched.
     */
    @Test
    fun malformedBackupOnlyFailsLabeledAndIsRetained() {
        val pm = context.packageManager
        recoverThenClear()
        val settled = pm.getComponentEnabledSetting(receiver)

        val base = R04ReceiverIsolation.recordFile(context)
        val bak = R04ReceiverIsolation.recordBackupFile(context)
        try {
            bak.writeBytes("garbage".toByteArray(Charsets.UTF_8))
            var thrown: Throwable? = null
            try {
                R04ReceiverIsolation.restore(context)
            } catch (t: Throwable) {
                thrown = t
            }
            assertNotNull("malformed .bak-only record must fail", thrown)
            assertTrue(thrown is IllegalStateException)
            // openRead already recovered .bak into base — the malformed
            // record is retained there for a later manual recovery decision.
            assertTrue("malformed record must be retained", base.exists())
            assertEquals(
                "receiver state must be untouched",
                settled, pm.getComponentEnabledSetting(receiver),
            )
        } finally {
            base.delete()
            bak.delete()
            R04ReceiverIsolation.recordNewFile(context).delete()
        }
    }
}
