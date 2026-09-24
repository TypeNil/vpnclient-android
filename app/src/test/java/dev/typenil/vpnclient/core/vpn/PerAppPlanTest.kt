package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Builder rejects mixing addAllowed/addDisallowed calls — the plan must
 * always produce at most one populated side. The app itself always rides
 * its own tunnel: it joins any allow-list and is never disallowed (core
 * sockets bypass via VpnService.protect, not per-app rules).
 */
class PerAppPlanTest {

    private val self = "dev.typenil.vpnclient"

    @Test
    fun `all mode routes everything including self`() {
        val plan = resolvePerAppPlan(PerAppMode.ALL, emptySet(), self)
        assertTrue(plan.allowed.isEmpty())
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `all mode ignores the selection`() {
        val plan = resolvePerAppPlan(PerAppMode.ALL, setOf("com.a"), self)
        assertTrue(plan.allowed.isEmpty())
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `include mode allows selection plus self`() {
        val plan = resolvePerAppPlan(
            PerAppMode.INCLUDE, setOf("com.a", "com.b"), self,
        )
        assertEquals(listOf("com.a", "com.b", self), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `include mode dedupes self already in the selection`() {
        val plan = resolvePerAppPlan(
            PerAppMode.INCLUDE, setOf("com.a", self), self,
        )
        assertEquals(listOf("com.a", self), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `include mode with empty selection routes only self`() {
        val plan = resolvePerAppPlan(PerAppMode.INCLUDE, emptySet(), self)
        // "Only selected apps" with none selected degenerates to self alone —
        // an empty Builder allow-list would silently mean allow-all.
        assertEquals(listOf(self), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `exclude mode disallows selection but never self`() {
        val plan = resolvePerAppPlan(PerAppMode.EXCLUDE, setOf("com.a"), self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf("com.a"), plan.disallowed)
        assertFalse(self in plan.disallowed)
    }

    @Test
    fun `core include requests merge with user include plus self`() {
        val plan = resolvePerAppPlan(
            PerAppMode.INCLUDE, setOf("com.a"), self,
            coreInclude = listOf("com.core"),
        )
        assertEquals(setOf("com.a", "com.core", self), plan.allowed.toSet())
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `core include wins over user exclude — never mixes`() {
        val plan = resolvePerAppPlan(
            PerAppMode.EXCLUDE, setOf("com.a"), self,
            coreInclude = listOf("com.core"),
        )
        assertEquals(setOf("com.core", self), plan.allowed.toSet())
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `core exclude merges with user exclude`() {
        val plan = resolvePerAppPlan(
            PerAppMode.EXCLUDE, setOf("com.a"), self,
            coreExclude = listOf("com.core"),
        )
        assertEquals(setOf("com.a", "com.core"), plan.disallowed.toSet())
        assertFalse(self in plan.disallowed)
    }
}
