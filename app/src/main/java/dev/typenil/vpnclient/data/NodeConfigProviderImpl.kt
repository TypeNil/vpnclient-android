package dev.typenil.vpnclient.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.engine.singbox.RuleSetStore
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.summary
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.settings.SettingsRepository
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Singleton
class NodeConfigProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodeDao: NodeDao,
    private val settings: SettingsRepository,
    private val compiler: ConfigCompiler,
    private val ruleSetStore: RuleSetStore,
) : NodeConfigProvider {

    override val selectedNodeId: Flow<String?> = settings.selectedNodeId

    override suspend fun nodeSummary(id: String): NodeSummary? =
        nodeDao.get(id)?.toDomain()?.summary()

    override suspend fun compileSelected(): EngineConfig? {
        val nodes = nodeDao.getEnabled().map { it.toDomain() }
        if (nodes.isEmpty()) return null
        // A refresh can commit a node set that no longer contains the
        // persisted selection before its post-commit cleanup runs — clear it
        // here so a connect in that window falls back instead of failing.
        // The conditional clear can't wipe a selection the user just made.
        val persisted = settings.selectedNodeId.first()
        if (persisted != null && nodes.none { it.id == persisted }) {
            settings.clearSelectedNodeIdIf(persisted)
        }
        val routeMode = settings.routeMode.first()
        return compiler.compile(
            nodes = nodes,
            // Re-read after the conditional clear: a concurrent user pick
            // lands here, and a still-stale id keeps failing loudly.
            selectedNodeId = settings.selectedNodeId.first(),
            ipv6Enabled = settings.ipv6Enabled.first(),
            routeMode = routeMode,
            underlayIpv6 = underlayHasIpv6(),
            ruleSetPaths = ruleSetStore.ensureReady(routeMode),
        )
    }

    /**
     * Whether the network our `direct` dial will bind — the default/active
     * one — currently offers real IPv6 reachability: a global v6 address
     * *and* a v6 default route (an address without `::/0` — rogue RA, stale
     * lease — is still a dead dial). A *secondary* network (standby LTE with
     * v6 while Wi-Fi is active) must not count: the direct outbound never
     * uses it. On ambiguous reads assume false — v6 via the proxy works.
     */
    private fun underlayHasIpv6(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        // Our package is always disallowed from the tunnel, so activeNetwork
        // is the underlay even while Connected — never our own tun0.
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        val link = cm.getLinkProperties(network) ?: return false
        val hasDefaultV6Route = link.routes.any { route ->
            route.destination.address is Inet6Address && route.destination.prefixLength == 0
        }
        return hasDefaultV6Route &&
            link.linkAddresses.any { (it.address as? Inet6Address)?.let(::isGlobalIpv6) == true }
    }
}

/** Global-unicast IPv6: not link-local, site-local, ULA, loopback,
 *  multicast, or the unspecified `::` address. Pure — JVM-testable. */
internal fun isGlobalIpv6(address: Inet6Address): Boolean =
    !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress && !address.isLoopbackAddress &&
        !address.isMulticastAddress &&
        // ULA fc00::/7 is not global reachability either.
        (address.address[0].toInt() and 0xfe) != 0xfc

fun NodeEntity.toDomain(): ProxyNode = ProxyNode(
    id = id,
    subscriptionId = subscriptionId,
    name = name,
    protocol = runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.OTHER),
    server = server,
    port = port,
    outboundJson = outboundJson,
    rawUri = rawUri,
)
