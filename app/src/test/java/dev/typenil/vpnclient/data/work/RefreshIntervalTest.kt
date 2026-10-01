package dev.typenil.vpnclient.data.work

import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.core.subscription.resolveRefreshIntervalMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Interval resolution matrix: per-subscription policy wins; InheritGlobal
 * keeps the legacy app-wide int semantics (-1 off / 0 provider / >0 fixed).
 * Platform floor at 15 minutes.
 */
class RefreshIntervalTest {

    private fun resolve(
        policy: RefreshPolicy,
        global: Int,
        provider: Int?,
        enabled: Boolean = true,
    ) = resolveRefreshIntervalMinutes(policy, global, provider, enabled)

    // ---- inherit: the legacy global-int semantics, unchanged ----

    @Test
    fun `inherit applies the global value exactly as before`() {
        // -1 off, whatever the hints say.
        assertNull(resolve(RefreshPolicy.InheritGlobal, -1, 120))
        assertNull(resolve(RefreshPolicy.InheritGlobal, -1, null))
        // 0 follows the provider hint.
        assertEquals(120L, resolve(RefreshPolicy.InheritGlobal, 0, 120))
        // >0 is a fixed user override and beats the provider hint.
        assertEquals(60L, resolve(RefreshPolicy.InheritGlobal, 60, 120))
    }

    @Test
    fun `inherit with global provider mode and no hint is manual only`() {
        assertNull(resolve(RefreshPolicy.InheritGlobal, 0, null))
    }

    // ---- provider: explicit opt-in, never a global fallback ----

    @Test
    fun `explicit provider uses the provider hint`() {
        assertEquals(120L, resolve(RefreshPolicy.Provider, 0, 120))
    }

    @Test
    fun `explicit provider is unaffected by the global value`() {
        // Global off must not kill it; a global override must not override it.
        assertEquals(120L, resolve(RefreshPolicy.Provider, -1, 120))
        assertEquals(120L, resolve(RefreshPolicy.Provider, 45, 120))
    }

    @Test
    fun `explicit provider without a usable hint is manual only`() {
        assertNull(resolve(RefreshPolicy.Provider, 0, null))
        // Not a fallback to the global override.
        assertNull(resolve(RefreshPolicy.Provider, 60, null))
        assertNull(resolve(RefreshPolicy.Provider, 60, 0))
    }

    // ---- fixed: explicit user interval, unaffected by the global value ----

    @Test
    fun `explicit fixed ignores the global value`() {
        assertEquals(90L, resolve(RefreshPolicy.Fixed(90), 0, null))
        assertEquals(90L, resolve(RefreshPolicy.Fixed(90), -1, null))
        assertEquals(90L, resolve(RefreshPolicy.Fixed(90), 30, 120))
    }

    // ---- disabled policy / disabled subscription ----

    @Test
    fun `disabled policy never schedules`() {
        assertNull(resolve(RefreshPolicy.Disabled, 0, 120))
        assertNull(resolve(RefreshPolicy.Disabled, 60, 120))
        assertNull(resolve(RefreshPolicy.Disabled, -1, null))
    }

    @Test
    fun `disabled subscription never schedules under any policy`() {
        val policies =
            listOf(
                RefreshPolicy.InheritGlobal,
                RefreshPolicy.Provider,
                RefreshPolicy.Disabled,
                RefreshPolicy.Fixed(60),
            )
        policies.forEach { policy ->
            assertNull(resolve(policy, 60, 120, enabled = false))
            assertNull(resolve(policy, 0, 120, enabled = false))
        }
    }

    // ---- platform floor ----

    @Test
    fun `resolved intervals are floored at the platform minimum`() {
        // Inheriting a tiny global override.
        assertEquals(15L, resolve(RefreshPolicy.InheritGlobal, 5, null))
        // A short provider hint under explicit provider AND inherit.
        assertEquals(15L, resolve(RefreshPolicy.Provider, 0, 5))
        assertEquals(15L, resolve(RefreshPolicy.InheritGlobal, 0, 5))
        // The minimum legal Fixed value resolves to itself.
        assertEquals(15L, resolve(RefreshPolicy.Fixed(15), 0, null))
    }

    @Test
    fun `fixed below the platform minimum cannot be constructed`() {
        // Typed boundary: an invalid Fixed is rejected at construction —
        // nothing invalid can be persisted and silently decoded as Disabled.
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RefreshPolicy.Fixed(14)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RefreshPolicy.Fixed(0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RefreshPolicy.Fixed(-1)
        }
    }

    // ---- storage decode: safe fallbacks ----

    @Test
    fun `stored tokens decode to their policy`() {
        assertEquals(RefreshPolicy.InheritGlobal, RefreshPolicy.fromStorage("inherit", null))
        assertEquals(RefreshPolicy.Provider, RefreshPolicy.fromStorage("provider", null))
        assertEquals(RefreshPolicy.Disabled, RefreshPolicy.fromStorage("disabled", null))
        assertEquals(RefreshPolicy.Fixed(60), RefreshPolicy.fromStorage("fixed", 60))
    }

    @Test
    fun `invalid stored values fall back to disabled`() {
        assertEquals(RefreshPolicy.Disabled, RefreshPolicy.fromStorage("garbage", null))
        // NULL should be impossible (NOT NULL column) — corrupt data decodes
        // to the safe option, not to a guessed legacy behavior.
        assertEquals(RefreshPolicy.Disabled, RefreshPolicy.fromStorage(null, null))
        // A fixed token without a usable minutes value is not a fixed policy.
        assertEquals(RefreshPolicy.Disabled, RefreshPolicy.fromStorage("fixed", null))
        assertEquals(RefreshPolicy.Disabled, RefreshPolicy.fromStorage("fixed", 5))
        assertEquals(RefreshPolicy.Disabled, RefreshPolicy.fromStorage("fixed", 14))
        // Boundary: the platform minimum is accepted.
        assertEquals(RefreshPolicy.Fixed(15), RefreshPolicy.fromStorage("fixed", 15))
    }

    @Test
    fun `storage round-trip preserves the policy`() {
        listOf(
            RefreshPolicy.InheritGlobal,
            RefreshPolicy.Provider,
            RefreshPolicy.Disabled,
            RefreshPolicy.Fixed(45),
        ).forEach { policy ->
            assertEquals(
                policy,
                RefreshPolicy.fromStorage(policy.storageKey, policy.storageFixedMinutes),
            )
        }
        // Non-fixed policies don't leave a stale minutes value behind.
        assertNull(RefreshPolicy.Provider.storageFixedMinutes)
    }
}
