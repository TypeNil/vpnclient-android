package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.summary
import io.nekohasekai.libbox.Libbox
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Compiles parsed nodes into a sing-box configuration.
 *
 * Layout: every node becomes a named outbound; a `selector` group "proxy" points at the
 * selected node (switchable at runtime via CommandClient.selectOutbound) and an `urltest`
 * group "auto" measures latency across all nodes.
 */
@Singleton
class ConfigCompiler @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    /** Build + validate (native `checkConfig`) — used on the connect path. */
    suspend fun compile(
        nodes: List<ProxyNode>,
        selectedNodeId: String?,
        ipv6Enabled: Boolean,
    ): EngineConfig = withContext(Dispatchers.IO) {
        val compiled = build(nodes, selectedNodeId, ipv6Enabled)
        try {
            Libbox.checkConfig(compiled.configJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineError.InvalidConfig(e.message ?: "invalid config")
        }
        compiled
    }

    /** Pure JSON construction — JVM-testable (no native calls). */
    fun build(
        nodes: List<ProxyNode>,
        selectedNodeId: String?,
        ipv6Enabled: Boolean,
    ): EngineConfig {
        require(nodes.isNotEmpty()) { "no nodes to compile" }
        // A stale selection (node removed by a refresh) must fail loudly —
        // silently connecting to a different server surprises the user.
        val selected = when {
            selectedNodeId == null -> nodes.first()
            else -> nodes.firstOrNull { it.id == selectedNodeId }
                ?: throw EngineError.InvalidConfig("selected node no longer exists")
        }
        val nodeTags = nodes.map { it.id }

        val outbounds = buildJsonArray {
            addJsonObject {
                put("type", "selector")
                put("tag", SELECTOR_TAG)
                putJsonArray("outbounds") {
                    add(AUTO_TAG)
                    nodeTags.forEach { add(it) }
                }
                put("default", selected.id)
                put("interrupt_exist_connections", false)
            }
            addJsonObject {
                put("type", "urltest")
                put("tag", AUTO_TAG)
                putJsonArray("outbounds") { nodeTags.forEach { add(it) } }
                put("url", "https://www.gstatic.com/generate_204")
                put("interval", "3m")
                put("tolerance", 50)
                put("interrupt_exist_connections", false)
            }
            nodes.forEach { node ->
                add(json.parseToJsonElement(node.outboundJson))
            }
            addJsonObject {
                put("type", "direct")
                put("tag", "direct")
            }
        }

        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", true)
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    addJsonObject {
                        put("type", "local")
                        put("tag", "local")
                    }
                    addJsonObject {
                        put("type", "https")
                        put("tag", "remote")
                        put("server", "1.1.1.1")
                        // DoH dials like an outbound: without a detour it goes
                        // direct, leaking DNS past the selected proxy.
                        put("detour", SELECTOR_TAG)
                    }
                }
                put("final", "remote")
                put("strategy", if (ipv6Enabled) "prefer_ipv4" else "ipv4_only")
            }
            putJsonArray("inbounds") {
                addJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("mtu", 9000)
                    putJsonArray("address") {
                        add("172.18.0.1/30")
                        if (ipv6Enabled) add("fdfe:dcba:9877::1/126")
                    }
                    put("auto_route", true)
                    put("strict_route", false)
                    // gvisor is required on pinned libbox 1.14.1 (system stack
                    // fails inbound TCP there); revisit on a 1.15+ upgrade.
                    put("stack", "gvisor")
                }
            }
            put("outbounds", outbounds)
            putJsonObject("route") {
                putJsonArray("rules") {
                    addJsonObject { put("action", "sniff") }
                    addJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    }
                    addJsonObject {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    }
                }
                put("final", SELECTOR_TAG)
                putJsonObject("default_domain_resolver") {
                    // Must stay "local": "remote" detours through the proxy,
                    // and the proxy's server names are resolved by this
                    // resolver — pointing it at "remote" would deadlock
                    // bootstrap (remote → proxy → resolve via remote → …).
                    put("server", "local")
                }
            }
        }

        return EngineConfig(config.toString(), selected.summary())
    }

    companion object {
        const val SELECTOR_TAG = "proxy"
        const val AUTO_TAG = "auto"
    }
}
