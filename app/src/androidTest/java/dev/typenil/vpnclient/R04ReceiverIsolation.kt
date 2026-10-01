package dev.typenil.vpnclient

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import java.io.File

/**
 * Early PackageManager isolation of the manifest BootReceiver, applied
 * unconditionally by [VpnTestRunner].newApplication (the earliest point an
 * instrumentation process can act).
 *
 * Why: `dev.typenil.vpnclient.core.vpn.BootReceiver` is `@AndroidEntryPoint`.
 * A queued BOOT_COMPLETED / MY_PACKAGE_REPLACED broadcast dispatched into the
 * instrumentation process before the first `HiltAndroidRule` reaches
 * `HiltTestApplication.generatedComponent()`, which throws — killing the
 * process before any test runs. The app may change its own component's
 * enabled state (instrumentation runs as the app UID, unlike `pm disable`
 * from shell), so the runner disables the receiver as early as the process
 * can act — inside `newApplication` during process bind.
 *
 * Durability contract: the ORIGINAL numeric enabled state is committed to a
 * durable [AtomicFile] record in the app files dir BEFORE the mutation, so a
 * hard-killed run can be recovered exactly — the leftover DISABLED is never
 * re-snapshotted as "original". `AtomicFile.finishWrite` only LOGS
 * sync/close/rename failures, so [writeRecord] `fd.sync()`s (throwing) before
 * committing and then reads the committed record back; a missing or
 * mismatched commit fails the bootstrap before the PM mutation — the
 * receiver stays enabled and nothing is lost. A `<name>.new` write artifact
 * is never treated as committed and never promoted.
 *
 * Recovery contract: `AtomicFile.exists()` covers base OR legacy `.bak`;
 * `openRead()` recovers `.bak` into base (`.bak` wins), so a `.bak`-only
 * record is honored. The record is deleted (all three artifacts: base,
 * `.bak`, `.new`) only after a verified successful restore. [disable] reuses
 * an existing record (a previous run died mid-isolation) instead of writing
 * a new one, and never re-enables the component mid-bootstrap.
 *
 * Device-observed limit (OnePlus CPH2449): a broadcast record already
 * dispatched into the process still delivers after this disable — enabled
 * state was consulted at dispatch, not at delivery. That failure is loud —
 * the same pre-test crash — never silent; the DISABLED residue plus this
 * record make the immediate identical retry resolve-time safe, and the
 * record still allows exact recovery.
 *
 * Errors are fixed-label only: the record's contents are never included in
 * any exception.
 */
object R04ReceiverIsolation {

    /** `am instrument -e` key opting into exclusive R04 runs; without it the
     *  gated test classes skip so plain suite runs never touch the runner's
     *  live record (or process-global libbox state). */
    internal const val RUN_ARG = "r04"

    private const val RECEIVER = "dev.typenil.vpnclient.core.vpn.BootReceiver"
    private const val RECORD_NAME = "r04_receiver_isolation_state"
    private const val FLAGS = PackageManager.DONT_KILL_APP

    private val VALID_STATES = intArrayOf(
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
    )

    private fun file(context: Context): File = File(context.filesDir, RECORD_NAME)

    private fun component(context: Context): ComponentName =
        ComponentName(context.packageName, RECEIVER)

    /** Visible to the sibling restore test for direct record assertions. */
    internal fun recordFile(context: Context): File = file(context)

    /** Legacy AtomicFile backup (`<name>.bak`) — openRead recovers it into base. */
    internal fun recordBackupFile(context: Context): File =
        File(context.filesDir, "$RECORD_NAME.bak")

    /** In-flight write artifact (`<name>.new`) — never a committed record. */
    internal fun recordNewFile(context: Context): File =
        File(context.filesDir, "$RECORD_NAME.new")

    /**
     * Commits [state] as the original enabled state. `fd.sync()` (throwing)
     * runs BEFORE `finishWrite`, which would only log a sync/close/rename
     * failure; afterwards the committed record is read back and must equal
     * [state] — otherwise the bootstrap fails before any PM mutation and the
     * receiver is left enabled.
     */
    internal fun writeRecord(context: Context, state: Int) {
        val af = AtomicFile(file(context))
        val fos = try {
            af.startWrite()
        } catch (e: Exception) {
            throw IllegalStateException("R04 isolation: state record not writable")
        }
        try {
            fos.write(state.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
            af.finishWrite(fos)
        } catch (e: Exception) {
            af.failWrite(fos)
            throw IllegalStateException("R04 isolation: state record not committed")
        }
        // finishWrite can have silently failed — trust only a verified read-back.
        val committed = readRecord(context)
        if (committed == null || committed != state) {
            throw IllegalStateException("R04 isolation: committed record mismatch")
        }
    }

    /** The recorded original state, or null when no record exists. `af.exists()`
     *  covers base OR `.bak`; `openRead()` lets the framework recover a
     *  `.bak`-only record into base. When no record exists, only the `.new`
     *  in-flight artifact is best-effort removed (never promoted). A malformed
     *  or unreadable record is kept (never deleted, never guessed) and fails
     *  with a fixed label. */
    internal fun readRecord(context: Context): Int? {
        val af = AtomicFile(file(context))
        // AtomicFile.exists() (base || .bak) is API 34+; minSdk forces the
        // explicit equivalent here.
        if (!file(context).exists() && !recordBackupFile(context).exists()) {
            runCatching { recordNewFile(context).delete() }
            return null
        }
        val raw = try {
            af.openRead().use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException("R04 isolation: state record unreadable")
        }
        val parsed = raw.toString(Charsets.UTF_8).trim().toIntOrNull()
        if (parsed == null || parsed !in VALID_STATES) {
            throw IllegalStateException("R04 isolation: malformed state record")
        }
        return parsed
    }

    /** Removes every record artifact (base, `.bak`, `.new`) after a verified
     *  restore; fails with a fixed label if any artifact remains. */
    private fun deleteArtifacts(context: Context) {
        val artifacts = listOf(
            file(context), recordBackupFile(context), recordNewFile(context),
        )
        artifacts.forEach { it.delete() }
        check(artifacts.none { it.exists() }) {
            "R04 isolation: restored but state record artifacts remain"
        }
    }

    /**
     * Disables the receiver for the duration of the run and returns the
     * original state. An existing record (previous run hard-killed before
     * restoring) is reused verbatim — the residue DISABLED is not treated as
     * original and the component is never re-enabled here. The post-call
     * read-back proves the disable actually applied so a silent no-op cannot
     * pass. Call before the application/broadcast machinery can dispatch —
     * inside `newApplication` during process bind.
     */
    fun disable(context: Context): Int {
        val pm = context.packageManager
        val cn = component(context)
        val original = readRecord(context) ?: run {
            val live = pm.getComponentEnabledSetting(cn)
            writeRecord(context, live) // verified durable record BEFORE mutation
            live
        }
        pm.setComponentEnabledSetting(
            cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, FLAGS,
        )
        check(
            pm.getComponentEnabledSetting(cn) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) { "R04 isolation: receiver not DISABLED after set" }
        return original
    }

    /**
     * Restores the exact recorded original state. Idempotent: no record →
     * no-op (nothing was mutated). All record artifacts (base, `.bak`,
     * `.new`) are removed only after the recorded state is verified applied;
     * every failure throws a fixed-label exception and keeps the record for
     * a later recovery attempt.
     */
    fun restore(context: Context) {
        val recorded = readRecord(context) ?: return
        val pm = context.packageManager
        val cn = component(context)
        pm.setComponentEnabledSetting(cn, recorded, FLAGS)
        check(pm.getComponentEnabledSetting(cn) == recorded) {
            "R04 isolation: recorded state not applied"
        }
        deleteArtifacts(context)
    }
}
