package dev.typenil.vpnclient

import android.app.Application
import android.content.Context
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
 */
class VpnTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
