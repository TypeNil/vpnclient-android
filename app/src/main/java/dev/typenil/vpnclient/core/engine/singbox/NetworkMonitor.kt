package dev.typenil.vpnclient.core.engine.singbox

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.system.OsConstants
import dev.typenil.vpnclient.core.common.log.SecureLog
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tracks the default network and feeds interface changes to the core.
 * Replaces SFA's DefaultNetworkMonitor/DefaultNetworkListener pair.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val scope: CoroutineScope,
) {

    @Volatile
    var defaultNetwork: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * The default network the core should dial out on — never the VPN
     * interface itself. Once the tunnel is up, `registerDefaultNetworkCallback`
     * reports the VPN network (the app's own traffic is VPN-subject); pushing
     * it to the core makes outbounds bind to tun and loop into themselves.
     */
    private fun physicalNetwork(candidate: Network?): Network? {
        if (candidate != null && !isVpn(candidate)) return candidate
        return connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun isVpn(network: Network): Boolean =
        connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    fun start() {
        if (callback != null) return
        defaultNetwork = physicalNetwork(connectivity.activeNetwork)
        val cb = object : ConnectivityManager.NetworkCallback() {
            // NOT_VPN-scoped request: the tunnel's own network never fires
            // these callbacks, so the core can't be pushed onto itself.
            // Every event recomputes from activeNetwork — this request
            // matches any non-VPN network, not just the default.
            override fun onAvailable(network: Network) = recompute()

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) = recompute()

            override fun onLost(network: Network) = recompute()

            private fun recompute() {
                val physical = physicalNetwork(connectivity.activeNetwork)
                if (physical == defaultNetwork) return
                defaultNetwork = physical
                pushDefaultInterface(physical)
            }
        }
        callback = cb
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            cb,
        )
        pushDefaultInterface(defaultNetwork)
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        defaultNetwork = null
        listener = null
        lastPushedInterface = null
    }

    /** Core calls this to (un)register its default-interface listener. */
    fun setListener(newListener: InterfaceUpdateListener?) {
        listener = newListener
        pushDefaultInterface(defaultNetwork)
    }

    @Volatile
    private var lastPushedInterface: String? = null

    private fun pushDefaultInterface(network: Network?) {
        val target = listener ?: return
        if (network == null) {
            if (lastPushedInterface != null) {
                lastPushedInterface = null
                target.updateDefaultInterface("", -1, false, false)
            }
            return
        }
        // LinkProperties may lag behind the callback; retry briefly like SFA.
        scope.launch(Dispatchers.IO) {
            repeat(10) {
                // Re-read the listener — stop() may have detached it while we slept.
                val current = listener ?: return@launch
                val interfaceName = connectivity.getLinkProperties(network)?.interfaceName
                val index = interfaceName
                    ?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
                if (interfaceName != null && index != null) {
                    if (interfaceName != lastPushedInterface) {
                        lastPushedInterface = interfaceName
                        current.updateDefaultInterface(interfaceName, index, false, false)
                    }
                    return@launch
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    /** Enumerate interfaces for the core's routing decisions. */
    fun getInterfaces(): NetworkInterfaceIterator {
        val systemInterfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
        }.getOrNull().orEmpty()
        val result = connectivity.allNetworks.mapNotNull { network ->
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val sysIf = systemInterfaces.find { it.name == link.interfaceName }
                ?: return@mapNotNull null
            io.nekohasekai.libbox.NetworkInterface().apply {
                name = link.interfaceName
                index = sysIf.index
                dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress }.iterator())
                gateway = StringArray(
                    link.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress }
                        .filter { it.isNotEmpty() }
                        .iterator(),
                )
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                runCatching { mtu = sysIf.mtu }
                addresses = StringArray(
                    sysIf.interfaceAddresses.map { it.toPrefix() }.iterator(),
                )
                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (sysIf.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (sysIf.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (sysIf.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                this.flags = flags
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(result.iterator())
    }

    fun requireDefaultNetwork(): Network =
        defaultNetwork ?: connectivity.activeNetwork ?: error("no default network")

    private class InterfaceArray(
        private val iterator: Iterator<io.nekohasekai.libbox.NetworkInterface>,
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
    }

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private fun InterfaceAddress.toPrefix(): String =
        if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
        } else {
            "${address.hostAddress}/$networkPrefixLength"
        }
}
