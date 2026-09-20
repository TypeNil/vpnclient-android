package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteModeTest {

    @Test
    fun `stored keys round-trip`() {
        RouteMode.entries.forEach { mode ->
            assertEquals(mode, RouteMode.fromKey(mode.key))
        }
    }

    @Test
    fun `unknown and absent keys fall back to ALL`() {
        assertEquals(RouteMode.ALL, RouteMode.fromKey("bogus"))
        assertEquals(RouteMode.ALL, RouteMode.fromKey(""))
        assertEquals(RouteMode.ALL, RouteMode.fromKey(null))
        // Ordinal-style leftovers must not reinterpret as a mode.
        assertEquals(RouteMode.ALL, RouteMode.fromKey("0"))
    }
}
