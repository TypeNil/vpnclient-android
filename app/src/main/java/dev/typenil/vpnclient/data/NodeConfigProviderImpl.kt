package dev.typenil.vpnclient.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.settings.SettingsRepository
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class NodeConfigProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodeDao: NodeDao,
    private val settings: SettingsRepository,
    private val compiler: ConfigCompiler,
) : NodeConfigProvider {

    override suspend fun compileSelected(): EngineConfig? {
        val nodes = nodeDao.getEnabled().map { it.toDomain() }
        if (nodes.isEmpty()) return null
        return compiler.compile(
            nodes = nodes,
            selectedNodeId = settings.selectedNodeId.first(),
            ipv6Enabled = settings.ipv6Enabled.first(),
            routeMode = settings.routeMode.first(),
            underlayIpv6 = underlayHasIpv6(),
        )
    }

    /**
     * Whether the network our `direct` dial will bind — the default/active
     * one — currently offers a global IPv6 address. A *secondary* network
     * (e.g. standby LTE with v6 while Wi-Fi is active) must not count: the
     * direct outbound never uses it. On ambiguous reads assume false — v6
     * via the proxy still works.
     */
    private fun underlayHasIpv6(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        // Our package is always disallowed from the tunnel, so activeNetwork
        // is the underlay even while Connected — never our own tun0.
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return cm.getLinkProperties(network)?.linkAddresses?.any { link ->
            val address = link.address as? Inet6Address ?: return@any false
            !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isLoopbackAddress && !address.isMulticastAddress &&
                // ULA fc00::/7 is not global reachability either.
                (address.address[0].toInt() and 0xfe) != 0xfc
        } ?: false
    }
}

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
