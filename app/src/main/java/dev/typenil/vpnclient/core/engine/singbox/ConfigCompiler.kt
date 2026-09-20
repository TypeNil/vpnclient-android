package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.RouteMode
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
    ): EngineConfig = withContext(Dispatchers.IO) {
        val compiled = build(nodes, selectedNodeId, ipv6Enabled, routeMode, underlayIpv6)
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
                                BLOCKED_GEOSITE_TAGS.forEach { add(it) }
                            }
                            put("server", "remote")
                        }
                    }
                    RouteMode.ALL -> Unit
                }
                put("final", if (routeMode == RouteMode.PROXY_BLOCKED) "local" else "remote")
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
                                add(GEOIP_RU_TAG)
                                add(GEOSITE_RU_TAG)
                            }
                            put("outbound", "direct")
                        }
                        // Only the curated blocked list is worth proxy
                        // bandwidth — final below drops to "direct".
                        RouteMode.PROXY_BLOCKED -> addJsonObject {
                            putJsonArray("rule_set") {
                                BLOCKED_GEOSITE_TAGS.forEach { add(it) }
                            }
                            put("outbound", SELECTOR_TAG)
                        }
                        RouteMode.ALL -> Unit
                    }
                }
                put("final", if (routeMode == RouteMode.PROXY_BLOCKED) "direct" else SELECTOR_TAG)
                val ruleSets = routeRuleSets(routeMode)
                if (ruleSets.isNotEmpty()) {
                    putJsonArray("rule_set") {
                        ruleSets.forEach { (tag, url) ->
                            addJsonObject {
                                put("type", "remote")
                                put("tag", tag)
                                put("format", "binary")
                                put("url", url)
                                // Fetching through the proxy could deadlock
                                // bootstrap — the proxy route itself may
                                // depend on these rule sets being loaded.
                                // Deprecated in 1.14 (http_client is the
                                // successor); fine on pinned 1.14.1, revisit
                                // on a 1.16+ upgrade.
                                put("download_detour", "direct")
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
            if (routeRuleSets(routeMode).isNotEmpty()) {
                putJsonObject("experimental") {
                    // Remote rule sets otherwise re-download on every connect.
                    // cache_file persists them under workingPath (app cache —
                    // the system may evict it, the core refetches as needed).
                    putJsonObject("cache_file") {
                        put("enabled", true)
                        put("path", "cache.db")
                    }
                }
            }
        }

        return EngineConfig(config.toString(), selected.summary())
    }

    /** (tag, url) remote rule sets the mode needs declared under route.rule_set. */
    private fun routeRuleSets(mode: RouteMode): List<Pair<String, String>> = when (mode) {
        RouteMode.ALL -> emptyList()
        RouteMode.BYPASS_RU -> listOf(
            GEOIP_RU_TAG to "$GEOIP_RS_BASE/$GEOIP_RU_TAG.srs",
            GEOSITE_RU_TAG to "$GEOSITE_RS_BASE/$GEOSITE_RU_TAG.srs",
        )
        RouteMode.PROXY_BLOCKED ->
            BLOCKED_GEOSITE_TAGS.map { it to "$GEOSITE_RS_BASE/$it.srs" }
    }

    companion object {
        const val SELECTOR_TAG = "proxy"
        const val AUTO_TAG = "auto"

        // SagerNet rule-set branches (sing-box 1.12+ remote rule_set, binary
        // .srs format). Tag == file basename without the .srs suffix.
        private const val GEOIP_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        private const val GEOSITE_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"

        private const val GEOIP_RU_TAG = "geoip-ru"
        private const val GEOSITE_RU_TAG = "geosite-category-ru"

        /**
         * Services proxied in [RouteMode.PROXY_BLOCKED] — everything else
         * goes direct. Tags follow the sing-geosite `rule-set` branch naming
         * (`geosite-<category>`); each exists as `geosite-<category>.srs`
         * there. Curating the mode is a one-line edit per service.
         */
        private val BLOCKED_GEOSITE_TAGS = listOf(
            "geosite-youtube",
            "geosite-telegram",
            "geosite-instagram",
            "geosite-facebook",
            "geosite-twitter",
            "geosite-discord",
            "geosite-tiktok",
            "geosite-linkedin",
            "geosite-medium",
            "geosite-openai",
            "geosite-whatsapp",
        )
    }
}
