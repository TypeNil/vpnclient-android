package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Builder rejects mixing addAllowed/addDisallowed calls — the plan must
 * always produce at most one populated side, and the app itself must never
 * be allowed into its own tunnel.
 */
class PerAppPlanTest {

    private val self = "dev.typenil.vpnclient"

    @Test
    fun `all mode disallows only self`() {
        val plan = resolvePerAppPlan(PerAppMode.ALL, emptySet(), self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun `all mode ignores the selection`() {
        val plan = resolvePerAppPlan(PerAppMode.ALL, setOf("com.a"), self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun `include mode allows selection and never self`() {
        val plan = resolvePerAppPlan(
            PerAppMode.INCLUDE, setOf("com.a", "com.b", self), self,
        )
        assertEquals(listOf("com.a", "com.b"), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `include mode with empty selection still excludes self by omission`() {
        val plan = resolvePerAppPlan(PerAppMode.INCLUDE, emptySet(), self)
        assertTrue(plan.allowed.isEmpty())
        // Falls back to exclude-mode with just self disallowed — never mixes.
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun `exclude mode disallows selection plus self`() {
        val plan = resolvePerAppPlan(PerAppMode.EXCLUDE, setOf("com.a"), self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(setOf("com.a", self), plan.disallowed.toSet())
    }

    @Test
    fun `core include requests merge with user include`() {
        val plan = resolvePerAppPlan(
            PerAppMode.INCLUDE, setOf("com.a"), self,
            coreInclude = listOf("com.core"),
        )
        assertEquals(setOf("com.a", "com.core"), plan.allowed.toSet())
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `core include wins over user exclude — never mixes`() {
        val plan = resolvePerAppPlan(
            PerAppMode.EXCLUDE, setOf("com.a"), self,
            coreInclude = listOf("com.core"),
        )
        assertEquals(listOf("com.core"), plan.allowed)
        assertTrue(plan.disallowed.isEmpty())
    }

    @Test
    fun `core exclude merges with user exclude`() {
        val plan = resolvePerAppPlan(
            PerAppMode.EXCLUDE, setOf("com.a"), self,
            coreExclude = listOf("com.core"),
        )
        assertEquals(setOf("com.a", "com.core", self), plan.disallowed.toSet())
    }
}
