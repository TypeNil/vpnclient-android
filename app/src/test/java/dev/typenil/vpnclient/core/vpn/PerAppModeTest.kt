package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class PerAppModeTest {

    @Test
    fun `stored keys round-trip`() {
        PerAppMode.entries.forEach { mode ->
            assertEquals(mode, PerAppMode.fromKey(mode.key))
        }
    }

    @Test
    fun `unknown and absent keys fall back to ALL`() {
        assertEquals(PerAppMode.ALL, PerAppMode.fromKey("bogus"))
        assertEquals(PerAppMode.ALL, PerAppMode.fromKey(""))
        assertEquals(PerAppMode.ALL, PerAppMode.fromKey(null))
        // Ordinal-style leftovers must not reinterpret as a mode.
        assertEquals(PerAppMode.ALL, PerAppMode.fromKey("1"))
    }

    @Test
    fun `legacy ordinals map every entry and never crash`() {
        PerAppMode.entries.forEach { mode ->
            assertEquals(mode, PerAppMode.fromOrdinal(mode.ordinal))
        }
        // Out-of-range ordinals (reordered entries on an old install)
        // degrade to the safest default instead of guessing.
        assertEquals(PerAppMode.ALL, PerAppMode.fromOrdinal(-1))
        assertEquals(PerAppMode.ALL, PerAppMode.fromOrdinal(PerAppMode.entries.size))
    }

    @Test
    fun `legacy ordinal survives the v2 write-read round-trip`() {
        // The migration writes fromOrdinal(legacy).key and reads it back via
        // fromKey — the pair must be lossless for every stored ordinal.
        PerAppMode.entries.forEach { mode ->
            assertEquals(mode, PerAppMode.fromKey(PerAppMode.fromOrdinal(mode.ordinal).key))
        }
    }
}
