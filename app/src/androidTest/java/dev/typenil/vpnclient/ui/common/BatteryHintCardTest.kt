package dev.typenil.vpnclient.ui.common

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.data.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BatteryHintCardTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun closeAndUnavailableGuidanceAreAccessible() {
        var dismissals = 0
        compose.setContent { BatteryHintCard(onDismiss = { dismissals++ }, openSettings = { false }) }
        compose.onNodeWithText(context.getString(R.string.battery_hint_open)).performClick()
        compose.onNodeWithText(context.getString(R.string.battery_hint_unavailable)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.battery_hint_dismiss)).performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun launchRaceAndSecurityFailureDoNotEscape() {
        for (error in listOf(ActivityNotFoundException(), SecurityException())) {
            var attempted = false
            val unavailable = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) {
                    attempted = true
                    assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", intent.action)
                    assertNotNull(intent.component)
                    assertNull(intent.data)
                    throw error
                }
            }
            assertFalse(openBatterySettings(unavailable))
            assertTrue("Resolved system screen should reach guarded launch", attempted)
        }
    }

    @Test fun dismissalSurvivesRealDataStoreReopen() = runBlocking {
        val file = File(context.cacheDir, "battery-hint-test.preferences_pb")
        val key = booleanPreferencesKey("battery_hint_dismissed")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            assertNull(store.data.first()[key])
            store.edit { SettingsRepository.persistBatteryHintDismissal(it) }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = PreferenceDataStoreFactory.create(scope = scope) { file }
            assertEquals(true, reopened.data.first()[key])
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            file.delete()
        }
    }
}
