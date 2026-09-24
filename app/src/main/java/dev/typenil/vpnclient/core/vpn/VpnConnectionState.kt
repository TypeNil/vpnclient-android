package dev.typenil.vpnclient.core.vpn

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.TrafficStats
import dev.typenil.vpnclient.core.subscription.model.NodeSummary
import java.time.Instant

/**
 * Connection lifecycle as a real state machine — the UI renders this directly
 * and may not invent its own states.
 */
sealed interface VpnConnectionState {

    /** Nothing running, no error. */
    data object Idle : VpnConnectionState

    /** Resolving node + compiling config before touching the service. */
    data class Preparing(val node: NodeSummary) : VpnConnectionState

    /** `VpnService.prepare()` returned a consent intent — the UI must show it. */
    data object PermissionRequired : VpnConnectionState

    /** Service is up, engine is starting (config handed off, TUN not yet up). */
    data class Connecting(val node: NodeSummary) : VpnConnectionState

    /** TUN fd established, traffic flows. */
    data class Connected(
        val node: NodeSummary,
        val since: Instant,
        val stats: TrafficStats?,
    ) : VpnConnectionState

    /** Tunnel dropped or a recoverable change happened — reconnecting. */
    data class Reconnecting(
        val node: NodeSummary,
        val reason: Reason,
        val attempt: Int,
    ) : VpnConnectionState {
        /** Why the session is reconnecting — consumers key on this, so it's
         *  typed rather than a free-form string. */
        enum class Reason {
            /** Underlying network lost — the tunnel is still nominally up. */
            NetworkUnavailable,
            /** Engine died — teardown/restart in flight, state is stale. */
            CoreFailure,
            /** In-session rebuild (e.g. per-app policy change). */
            ApplyingChanges,
        }
    }

    /** Graceful teardown in progress. */
    data object Stopping : VpnConnectionState

    /** Terminal failure with a typed error. */
    data class Error(
        val error: VpnError,
        val node: NodeSummary?,
    ) : VpnConnectionState
}

/** Typed VPN failures for UI mapping. */
sealed class VpnError : Exception() {
    data object PermissionDenied : VpnError() {
        override val message = "VPN permission denied"
    }
    data object PermissionRevoked : VpnError() {
        override val message = "VPN permission was revoked"
    }
    data object NoNodeSelected : VpnError() {
        override val message = "no server selected"
    }
    data class ConfigInvalid(val detail: String) : VpnError()
    data class EngineFailed(val detail: String) : VpnError()
    data class TunnelFailed(val detail: String) : VpnError()
    data class Unexpected(val detail: String) : VpnError()

    companion object {
        fun fromEngine(error: EngineError): VpnError = when (error) {
            is EngineError.InvalidConfig -> ConfigInvalid(error.message)
            is EngineError.MissingVpnPermission -> PermissionRevoked
            is EngineError.TunnelFailed -> TunnelFailed(error.message)
            is EngineError.StartFailed -> EngineFailed(error.message)
            is EngineError.CoreError -> EngineFailed(error.message)
        }
    }
}
