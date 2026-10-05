package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStartNoticeTest {
    // Synthetic details only: the notice kind must never depend on them.
    private val errors: Map<VpnError, AutoStartNotice> = mapOf(
        VpnError.NoNodeSelected to AutoStartNotice.NoServer,
        VpnError.PermissionDenied to AutoStartNotice.VpnPermission,
        VpnError.UnencryptedTransport to AutoStartNotice.Unencrypted,
        VpnError.ConfigInvalid("d1") to AutoStartNotice.InvalidConfig,
        VpnError.ConfigInvalid("d2") to AutoStartNotice.InvalidConfig,
        VpnError.EngineFailed("d1") to AutoStartNotice.StartFailed,
        VpnError.TunnelFailed("d1") to AutoStartNotice.StartFailed,
        VpnError.Unexpected("d1") to AutoStartNotice.StartFailed,
        VpnError.PermissionRevoked to AutoStartNotice.StartFailed,
    )

    @Test fun `every error type maps to its kind on every start branch`() {
        val starting = listOf(
            AutomaticStartBranch.AlwaysOn,
            AutomaticStartBranch.Restore,
            AutomaticStartBranch.MissingPrerequisites,
        )
        for (branch in starting) for ((error, kind) in errors) {
            assertEquals("$branch $error", kind, autoStartNotice(branch, error))
        }
    }

    @Test fun `stop branch never notifies`() {
        for (error in errors.keys) {
            assertNull(error.toString(), autoStartNotice(AutomaticStartBranch.Stop, error))
        }
    }
}
