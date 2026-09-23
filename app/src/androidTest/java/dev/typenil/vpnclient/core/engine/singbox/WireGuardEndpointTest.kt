package dev.typenil.vpnclient.core.engine.singbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.UriListParser
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import io.nekohasekai.libbox.Libbox
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pinned core (libbox 1.14.x) removed the `wireguard` outbound in 1.13 —
 * a WireGuard node must compile to an `endpoints[]` entry. This test proves
 * the emitted shape passes `Libbox.checkConfig` on the real core; a JVM test
 * asserting JSON fields cannot catch a native rejection.
 */
@RunWith(AndroidJUnit4::class)
class WireGuardEndpointTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initLibbox() {
            LibboxRuntime.init(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        }
    }

    @Test
    fun wireguardNodeCompilesToEndpointAndPassesCheckConfig() {
        // 32-byte keys, base64 — checkConfig validates key shape.
        val privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val peerKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        val uri = "wireguard://$privateKey@wg.example.com:51820" +
            "?publickey=$peerKey&address=10.0.0.2/32#WG"
        val node = UriListParser().parse(uri, 1).single()
        assertTrue(node.protocol == ProtocolType.WIREGUARD)

        val config = ConfigCompiler().build(
            nodes = listOf(node),
            selectedNodeId = node.id,
            ipv6Enabled = false,
            routeMode = RouteMode.ALL,
        )
        // Endpoint placement, not outbounds[].
        assertTrue("\"endpoints\"" in config.configJson)
        // Throws on native rejection — the assertion that matters.
        Libbox.checkConfig(config.configJson)
    }
}
