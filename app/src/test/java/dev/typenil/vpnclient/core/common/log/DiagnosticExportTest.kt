package dev.typenil.vpnclient.core.common.log

import dev.typenil.vpnclient.core.vpn.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class DiagnosticExportTest {
    @Test fun `export separates app and core tail with meaningful empty note`() {
        val full = DiagnosticExport.render(listOf("app warning"), listOf("WARN dial timeout"), "Core warn-error", "No core warnings")
        assertTrue(full.contains("app warning\n\nCore warn-error\nWARN dial timeout"))
        val empty = DiagnosticExport.render(emptyList(), emptyList(), "Core warn-error", "No core warnings")
        assertTrue(empty.contains("Core warn-error\nNo core warnings"))
        assertFalse(empty.contains("null"))
    }

    @Test fun `previous session label accompanies retained lines only when supplied`() {
        val previous = DiagnosticExport.render(emptyList(), listOf("WARN retained"), "Core", "Empty", "Previous session")
        assertTrue(previous.contains("Core\nPrevious session\nWARN retained"))
        val current = DiagnosticExport.render(emptyList(), listOf("WARN current"), "Core", "Empty")
        assertFalse(current.contains("Previous session"))
    }

    @Test fun `health exports only enum names and evidence times without addresses`() {
        val health = ConnectionHealth(observations = HealthLevel.entries.map { level ->
            HealthObservation(level, HealthStatus.Degraded, HealthReason.HttpTimeout,
                HealthSource.IpEcho, HealthScope.AppHttpRouteUnverified,
                checkedAt = Instant.EPOCH, freshness = HealthFreshness.Fresh)
        })
        val text = DiagnosticExport.render(emptyList(), emptyList(), "Core", "Empty", health = health)
        val block = text.substringAfter("\nHealth\n")
        val expected = HealthLevel.entries.joinToString("") {
            "${it.name}: Degraded; reason=HttpTimeout; source=IpEcho; scope=AppHttpRouteUnverified; " +
                "freshness=Fresh; checkedAt=1970-01-01T00:00:00Z\n"
        }
        assertEquals(expected, block)
        assertFalse(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b|https?://|\.invalid|\.net|\[.*:.*\]""").containsMatchIn(block))
    }

    @Test fun `unavailable health is explicit and unobserved time is not invented`() {
        val ended = DiagnosticExport.render(emptyList(), listOf("WARN retained"), "Core", "Empty", "Previous session")
        assertEquals("Health: not available\n", ended.substringAfterLast("\n\n"))
        val unobserved = DiagnosticExport.render(emptyList(), emptyList(), "Core", "Empty", health = ConnectionHealth())
        assertEquals(HealthLevel.entries.size, Regex("checkedAt=not available").findAll(unobserved).count())
        assertTrue(unobserved.contains("OutboundReachable: Unverified; reason=NotObserved"))
    }

    @Test fun `memory share is isolated from previous grants and caller mutation`() {
        val store = DiagnosticShareStore()
        val first = store.publish("first")
        val bytes = store.read(first)!!
        bytes[0] = 0
        assertEquals("first", store.read(first)!!.toString(Charsets.UTF_8))
        val second = store.publish("second")
        assertNotEquals(first, second)
        assertNull(store.read(first))
        assertEquals("second", store.read(second)!!.toString(Charsets.UTF_8))
        assertNull(store.read("unknown"))
    }
}
