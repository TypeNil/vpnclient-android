package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.common.log.Redactor
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import org.junit.Assert.*
import org.junit.Test

class CoreLogSecretsTest {
    @Test fun `transport context is redacted inside words regardless of case and nesting`() {
        val values = listOf("/syn-path-long", "syn-service-long", "syn-host-long",
            "syn-header-one", "syn-header-two", "syn-header-three")
        val config = EngineConfig(
            """{"transport":{"path":"/syn-path-long","service_name":"syn-service-long",
                "host":"syn-host-long","headers":{"arbitrary":"syn-header-one",
                "nested":{"array":["syn-header-two",{"any":"syn-header-three"}]}}}}""",
            NodeSummary("synthetic-id", "Synthetic node", ProtocolType.VLESS, "example.invalid"),
        )
        val secrets = coreLogSecrets(config)
        assertTrue(secrets.containsAll(values))
        val line = values.joinToString(" ") { "prefix" + it.uppercase() + "suffix" } + " handshake failed"
        val safe = Redactor.redactCore(line, secrets)
        values.forEach { assertFalse(safe, safe.contains(it, ignoreCase = true)) }
        assertEquals("prefix<redacted>suffix ".repeat(values.size).trimEnd() + " handshake failed", safe)
        val buffer = dev.typenil.vpnclient.core.engine.CoreLogBuffer()
        buffer.start(secrets)
        buffer.add(buffer.subscribe(), 3, line)
        buffer.stop()
        assertEquals(listOf("WARN $safe"), buffer.snapshot())
    }

    @Test fun `short or blank transport context cannot corrupt ordinary prose`() {
        val config = EngineConfig(
            """{"path":"/","service_name":"a","host":"   ",
                "headers":{"blank":"","nested":["a","ab","abc","  x  ","  abcd  ",123,true]}}""",
            NodeSummary("synthetic-id", "Synthetic node", ProtocolType.VLESS, "example.invalid"),
        )
        val secrets = coreLogSecrets(config)
        listOf("/", "a", "ab", "abc", "", "   ", "  x  ").forEach { assertFalse(it in secrets) }
        assertTrue("  abcd  " in secrets)
        val line = "a path / abc refused a handshake"
        assertEquals(line, Redactor.redactCore(line, secrets))
    }

    @Test fun `short unlabelled secrets from all compiled outbounds are hidden`() {
        val config = EngineConfig(
            """{"outbounds":[{"server":"proxy.example.net","password":"tiny-pass",
            "tls":{"reality":{"public_key":"tiny-key","short_id":["abc123"]}}}],
            "endpoints":[{"peers":[{"public_key":"peer-key","pre_shared_key":"psk"}]}]}""",
            NodeSummary("synthetic-id", "Summer EU", ProtocolType.VLESS, "selected.example.net"),
        )
        val secrets = coreLogSecrets(config)
        val line = "Summer EU tiny-pass tiny-key abc123 peer-key psk: handshake timeout"
        val safe = Redactor.redactCore(line, secrets)
        for (secret in listOf("Summer EU", "tiny-pass", "tiny-key", "abc123", "peer-key", "psk")) {
            assertFalse(safe, safe.contains(secret))
        }
        assertTrue(safe.endsWith("handshake timeout"))
        assertTrue("selected.example.net" in secrets)
        assertTrue("proxy.example.net" in secrets)
    }
}
