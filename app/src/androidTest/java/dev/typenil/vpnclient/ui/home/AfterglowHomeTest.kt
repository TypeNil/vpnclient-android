package dev.typenil.vpnclient.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.R
import dev.typenil.vpnclient.core.vpn.VpnConnectionState
import dev.typenil.vpnclient.core.vpn.VpnError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AfterglowHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyHomeOpensImportAndConnectsOnlyOnAction() {
        var imports = 0
        var connects = 0
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(),
                onConnect = { connects++ },
                onDisconnect = {},
                onPick = {},
                onAddServer = { imports++ },
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = null,
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.common_add_server_cta)).performClick()
        compose.runOnIdle {
            assertEquals(1, imports)
            assertEquals(0, connects)
        }
        compose.onNodeWithText(context.getString(R.string.afterglow_start)).performClick()
        compose.runOnIdle { assertEquals(1, connects) }
    }

    @Test fun failureOffersRetryWithoutClaimingConnected() {
        compose.setContent {
            AfterglowHomeContent(
                ui = HomeUiState(connection = VpnConnectionState.Error(VpnError.PermissionDenied, null)),
                onConnect = {},
                onDisconnect = {},
                onPick = {},
                onAddServer = {},
                onOpenConnections = {},
                onOpenDetails = {},
                onOpenDiagnostics = {},
                onOpenRouting = {},
                onDismissGuard = {},
                errorMessage = "VPN permission denied",
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.afterglow_retry)).assertExists()
        compose.onNodeWithText("VPN permission denied").assertExists()
    }
}
