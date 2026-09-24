package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.GEOSITE_RU_TAG
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
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
        routeMode: RouteMode = RouteMode.ALL,
        underlayIpv6: Boolean = true,
        ruleSetPaths: Map<String, String> = emptyMap(),
    ): EngineConfig = withContext(Dispatchers.IO) {
        val compiled =
            build(nodes, selectedNodeId, ipv6Enabled, routeMode, underlayIpv6, ruleSetPaths)
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
        routeMode: RouteMode = RouteMode.ALL,
        underlayIpv6: Boolean = true,
        ruleSetPaths: Map<String, String> = emptyMap(),
    ): EngineConfig {
        require(nodes.isNotEmpty()) { "no nodes to compile" }
        // Local rule sets only — a missing file must fail at compile, never
        // surface as a silently-wrong route or an engine-start fetch error.
        require(ruleSetPaths.keys.containsAll(routeMode.ruleSetTags)) {
            "missing rule set files for ${routeMode.key}"
        }
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
                // Stop probing when the group carries no traffic — without
                // this the core pings every node every 3m forever.
                put("idle_timeout", "20m")
                put("interrupt_exist_connections", false)
            }
            // Outbound-shaped nodes only — WireGuard nodes compile to
            // `endpoints[]` below (the wireguard outbound was removed in
            // sing-box 1.13; endpoint tags are still selectable/urltestable).
            nodes.filter { it.protocol != ProtocolType.WIREGUARD }.forEach { node ->
                add(json.parseToJsonElement(node.outboundJson))
            }
            addJsonObject {
                put("type", "direct")
                put("tag", "direct")
            }
        }
        val endpointNodes = nodes.filter { it.protocol == ProtocolType.WIREGUARD }

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
                when (routeMode) {
                    // RU domains resolve via the ISP resolver so the direct
                    // route gets CDN-local answers; everything else keeps the
                    // proxied DoH. IPv4-only: an AAAA answer wins client-side
                    // ordering (the tun advertises v6), and a v6 RU dial is a
                    // dead end on IPv4-only underlays — the typical RU ISP.
                    RouteMode.BYPASS_RU -> putJsonArray("rules") {
                        addJsonObject {
                            putJsonArray("rule_set") { add(GEOSITE_RU_TAG) }
                            put("server", "local")
                            put("strategy", "ipv4_only")
                        }
                    }
                    // Blocked domains must not touch ISP DNS (spoofed answers)
                    // — they resolve through the proxied DoH. Everything else
                    // stays on the local resolver below.
                    RouteMode.PROXY_BLOCKED -> putJsonArray("rules") {
                        addJsonObject {
                            putJsonArray("rule_set") {
                                routeMode.ruleSetTags.forEach { add(it) }
                            }
                            put("server", "remote")
                        }
                    }
                    RouteMode.ALL -> Unit
                }
                put("final", if (routeMode == RouteMode.PROXY_BLOCKED) "local" else "remote")
                put("strategy", if (ipv6Enabled) "prefer_ipv4" else "ipv4_only")
                // IP→domain map so rule sets can match connections dialed by
                // bare IP — non-sniffable traffic (MTProto, ECH) otherwise
                // falls through to `final` and leaks past the proxy rules.
                put("reverse_mapping", true)
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
            if (endpointNodes.isNotEmpty()) {
                putJsonArray("endpoints") {
                    endpointNodes.forEach { node ->
                        add(json.parseToJsonElement(node.outboundJson))
                    }
                }
            }
            putJsonObject("route") {
                // Required for socket protection: without it the core never
                // calls autoDetectInterfaceControl, so outbound sockets stay
                // unprotected — fine only while our own package is disallowed
                // on the TUN. With self routed through the tunnel, an
                // unprotected engine socket loops back into the TUN and the
                // tunnel is dead.
                put("auto_detect_interface", true)
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
                    // Without underlay IPv6 a "direct" v6 dial is dead on
                    // arrival, so v6 goes through the proxy (which carries it
                    // fine). ALL mode already routes everything to the proxy.
                    if (routeMode != RouteMode.ALL && !underlayIpv6) {
                        addJsonObject {
                            put("ip_version", 6)
                            put("outbound", SELECTOR_TAG)
                        }
                    }
                    when (routeMode) {
                        // RU resources bypass the proxy entirely.
                        RouteMode.BYPASS_RU -> addJsonObject {
                            putJsonArray("rule_set") {
                                routeMode.ruleSetTags.forEach { add(it) }
                            }
                            put("outbound", "direct")
                        }
                        // Only the curated blocked list is worth proxy
                        // bandwidth — final below drops to "direct".
                        RouteMode.PROXY_BLOCKED -> addJsonObject {
                            putJsonArray("rule_set") {
                                routeMode.ruleSetTags.forEach { add(it) }
                            }
                            put("outbound", SELECTOR_TAG)
                        }
                        RouteMode.ALL -> Unit
                    }
                }
                put("final", if (routeMode == RouteMode.PROXY_BLOCKED) "direct" else SELECTOR_TAG)
                if (routeMode.ruleSetTags.isNotEmpty()) {
                    putJsonArray("rule_set") {
                        routeMode.ruleSetTags.forEach { tag ->
                            addJsonObject {
                                // Local files fetched app-side (RuleSetStore)
                                // — a remote fetch inside engine start would
                                // fail the whole connect on bad networks.
                                put("type", "local")
                                put("tag", tag)
                                put("format", "binary")
                                put("path", ruleSetPaths.getValue(tag))
                            }
                        }
                    }
                }
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
