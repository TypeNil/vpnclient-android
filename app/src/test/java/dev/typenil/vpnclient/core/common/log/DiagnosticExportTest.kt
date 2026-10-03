package dev.typenil.vpnclient.core.common.log

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
