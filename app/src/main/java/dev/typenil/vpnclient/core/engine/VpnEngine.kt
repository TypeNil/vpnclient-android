package dev.typenil.vpnclient.core.engine

import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Compiled, engine-native configuration ready to hand to the core. */
data class EngineConfig(
    /** Complete engine config (sing-box JSON) including the tun inbound. */
    val configJson: String,
    val node: NodeSummary,
)

/** Periodic traffic + health snapshot from the running core. */
data class TrafficStats(
    val uplinkBytesPerSec: Long,
    val downlinkBytesPerSec: Long,
    val uplinkTotalBytes: Long,
    val downlinkTotalBytes: Long,
    val connectionsIn: Int,
    val connectionsOut: Int,
    val goroutines: Int,
    val memoryBytes: Long,
)

/** A selectable outbound group (e.g. a `selector` or `urltest` group). */
data class OutboundGroupInfo(
    val tag: String,
    val type: String,
    val selectable: Boolean,
    val selected: String?,
    val items: List<OutboundItemInfo>,
)

data class OutboundItemInfo(
    val tag: String,
    val type: String,
    val urlTestDelayMs: Int?,
)

/** Lifecycle events the engine emits upward. */
sealed interface EngineEvent {
    data object Started : EngineEvent
    data class Failed(val error: EngineError) : EngineEvent
    /** Core terminated on its own — never emitted for an app-requested stop(). */
    data object StoppedUnexpectedly : EngineEvent
    data class Log(val level: Int, val message: String) : EngineEvent
    data class GroupsUpdated(val groups: List<OutboundGroupInfo>) : EngineEvent
}

/** Engine failures mapped to typed errors. */
sealed class EngineError : Exception() {
    data class InvalidConfig(override val message: String) : EngineError()
    data class StartFailed(override val message: String) : EngineError()
    data class CoreError(override val message: String) : EngineError()
    data object MissingVpnPermission : EngineError()
    data class TunnelFailed(override val message: String) : EngineError()
}

/**
 * Isolation boundary around the networking core (currently sing-box via
 * libbox). Nothing outside `core.engine.*` may reference libbox types.
 *
 * The engine does NOT own the Android Service lifecycle — the VpnService
 * drives it through [start]/[stop]. TUN fd provisioning flows through the
 * [EnginePlatform] bridge the service implements.
 */
interface VpnEngine {
    /** Validate a config without starting anything. Throws [EngineError.InvalidConfig]. */
    suspend fun validate(config: EngineConfig)

    /** Start the core; returns once the service is up or throws [EngineError]. */
    suspend fun start(config: EngineConfig)

    /** Stop the core and release the TUN fd. Safe to call when not started. */
    suspend fun stop()

    /** Push a network-change signal (wifi↔cellular, default network loss). */
    suspend fun onUnderlyingNetworkChanged()

    /** Device idle (Doze) hint; engines may pause background work when true. */
    suspend fun onDeviceIdle(idle: Boolean) = Unit

    val stats: Flow<TrafficStats>
    val events: Flow<EngineEvent>
    val groups: StateFlow<List<OutboundGroupInfo>>

    /** Switch selected outbound inside a group (server picker). */
    suspend fun selectOutbound(groupTag: String, outboundTag: String)

    /** Ask the core to run urltest on a group (latency measurement). */
    suspend fun urlTest(groupTag: String)
}

/**
 * What the engine needs from the Android side. Implemented by the VpnService —
 * the engine never touches `android.net.VpnService` itself.
 */
interface EnginePlatform {
    /** Build the TUN interface from core-provided options; return its fd. */
    fun openTun(request: TunRequest): Int

    /** Protect a socket from being routed back into the tunnel. */
    fun protectSocket(fd: Int): Boolean

    /** Close a previously opened TUN fd (engine stop/cleanup). */
    fun closeTun()
}

/** Engine-native TUN parameters translated off libbox types. */
data class TunRequest(
    val mtu: Int,
    val inet4Addresses: List<CidrAddress>,
    val inet6Addresses: List<CidrAddress>,
    val dnsServers: List<String>,
    val inet4Routes: List<CidrAddress>,
    val inet6Routes: List<CidrAddress>,
    val inet4ExcludedRoutes: List<CidrAddress>,
    val inet6ExcludedRoutes: List<CidrAddress>,
    val autoRoute: Boolean,
    val includedPackages: List<String>,
    val excludedPackages: List<String>,
)

data class CidrAddress(val address: String, val prefix: Int)
