package dev.typenil.vpnclient.core.vpn

/** User-facing reason an automatic start failed. Kind only: never a host, node or config value. */
internal enum class AutoStartNotice { NoServer, VpnPermission, Unencrypted, InvalidConfig, StartFailed }

/**
 * Whether a failed automatic start deserves a notice, and which one. Stop means nothing
 * was requested (desire off, stray start), so it is silent by design. Exhaustive over
 * [VpnError]: a new error type must choose its notice at compile time.
 */
internal fun autoStartNotice(branch: AutomaticStartBranch, error: VpnError): AutoStartNotice? =
    if (branch == AutomaticStartBranch.Stop) null else when (error) {
        VpnError.NoNodeSelected -> AutoStartNotice.NoServer
        VpnError.PermissionDenied -> AutoStartNotice.VpnPermission
        VpnError.UnencryptedTransport -> AutoStartNotice.Unencrypted
        is VpnError.ConfigInvalid -> AutoStartNotice.InvalidConfig
        VpnError.PermissionRevoked,
        is VpnError.EngineFailed,
        is VpnError.TunnelFailed,
        is VpnError.Unexpected,
        -> AutoStartNotice.StartFailed
    }
