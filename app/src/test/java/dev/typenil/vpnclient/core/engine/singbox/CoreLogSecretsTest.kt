package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.common.log.Redactor
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import org.junit.Assert.*
import org.junit.Test

class CoreLogSecretsTest {
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
