package dev.typenil.vpnclient

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps the app-under-test's Application for
 * [HiltTestApplication].
 *
 * `@HiltAndroidTest` classes are injected by a Hilt test component that
 * requires the running Application to be Hilt's test app. The production
 * `VpnClientApp` is `@HiltAndroidApp`, so the runner overrides the
 * application name — this is the documented Hilt-android-testing setup.
 *
 * Wired via `testInstrumentationRunner` in `app/build.gradle.kts`.
 *
 * R-0.4 receiver isolation (unconditional): a test APK cannot declare a
 * second `<instrumentation>` (the manifest merger collapses the elements
 * into one), and an `am instrument -e` argument cannot gate `newApplication`
 * (Instrumentation.onCreate runs AFTER makeApplication in
 * handleBindApplication — observed on-device). Isolation therefore always
 * runs here. This is strictly protective: under instrumentation the app
 * process runs HiltTestApplication, where a manifest BootReceiver dispatch
 * can ONLY crash the process pre-test (@AndroidEntryPoint inject against a
 * component that does not exist until the first HiltAndroidRule). No
 * instrumented test can legitimately consume BootReceiver — disabling it
 * during the run removes a crash class, changes no test-visible behavior.
 *
 * `newApplication` executes inside `handleBindApplication` →
 * `LoadedApk.makeApplication`, before `Application.onCreate` and before a
 * queued broadcast can be instantiated — the earliest point the process can
 * act. The receiver is disabled via own-UID `PackageManager` with its
 * original state durably recorded first (see [R04ReceiverIsolation]).
 *
 * Device-observed limit (OnePlus CPH2449): a broadcast record already
 * dispatched into the starting process still delivers regardless of the
 * disable — on this device the enabled check happens at dispatch, not at
 * delivery, and stale BOOT_COMPLETED records were seen crashing a fresh
 * instrumentation process with the `Hilt_BootReceiver` stack. The working
 * procedure is retry: a crashed run leaves the receiver DISABLED plus the
 * durable record, so the immediate identical re-run resolves broadcasts
 * without BootReceiver at all, and `finish` then restores the recorded
 * state. Launching the production app first does NOT reliably help — the
 * stale record can still be waiting for the next process.
 *
 * Restoration: `finish` restores the exact recorded state and turns a
 * restore failure into a non-passing instrumentation result
 * (`RESULT_CANCELED` + fixed bundle label — never a raw exception message);
 * `onDestroy` repeats it idempotently and throws a labeled failure. A hard
 * kill leaves the record for `R04ReceiverIsolationRestoreTest` or the next
 * run's bootstrap recovery.
 */
class VpnTestRunner : AndroidJUnitRunner() {

    private var isolationContext: Context? = null

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        val appContext = context ?: throw IllegalStateException(
            "R04 isolation: no base context during bootstrap",
        )
        try {
            R04ReceiverIsolation.disable(appContext)
            isolationContext = appContext
            return super.newApplication(
                cl, HiltTestApplication::class.java.name, context,
            )
        } catch (t: Throwable) {
            // Bootstrap failed after (or during) isolation — undo the
            // mutation before dying; keep failure evidence labeled, never raw.
            try {
                R04ReceiverIsolation.restore(appContext)
            } catch (restoreFailure: Throwable) {
                t.addSuppressed(
                    AssertionError(
                        "R04 isolation: bootstrap recovery restore failed " +
                            "(${restoreFailure.javaClass.simpleName})",
                    ),
                )
            }
            throw t
        }
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        var code = resultCode
        val res = results ?: Bundle()
        isolationContext?.let { ctx ->
            try {
                R04ReceiverIsolation.restore(ctx)
            } catch (t: Throwable) {
                // INSTRUMENTATION_CODE 0 = non-passing run; fixed label only.
                code = Activity.RESULT_CANCELED
                res.putString("r04_restore", "restore failed: ${t.javaClass.simpleName}")
            }
        }
        super.finish(code, res)
    }

    override fun onDestroy() {
        try {
            isolationContext?.let { R04ReceiverIsolation.restore(it) }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "R04 isolation: onDestroy restore failed (${t.javaClass.simpleName})",
            )
        } finally {
            super.onDestroy()
        }
    }
}
