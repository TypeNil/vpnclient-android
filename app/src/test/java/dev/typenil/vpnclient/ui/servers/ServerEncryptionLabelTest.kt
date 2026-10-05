package dev.typenil.vpnclient.ui.servers

import dev.typenil.vpnclient.data.db.NodeEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEncryptionLabelTest {
    private fun row(host: String, tls: Boolean) = ServerNode(
        entity = NodeEntity("synthetic", 1, "synthetic", "HTTP", "127.0.0.1", 443,
            """{"type":"http","server":"$host","tls":{"enabled":$tls}}""", null, 0),
        favorite = false,
    )

    @Test fun `remote plaintext is marked and blocked despite spoofed display host`() {
        val server = row("192.0.2.1", false)
        assertFalse(server.encrypted)
        assertFalse(server.tunnelAllowed)
    }

    @Test fun `sidecar is allowed but still marked unencrypted`() {
        val server = row("127.0.0.1", false)
        assertFalse(server.encrypted)
        assertTrue(server.tunnelAllowed)
    }

    @Test fun `encrypted server has no plaintext label`() {
        val server = row("192.0.2.1", true)
        assertTrue(server.encrypted)
        assertTrue(server.tunnelAllowed)
    }
}
