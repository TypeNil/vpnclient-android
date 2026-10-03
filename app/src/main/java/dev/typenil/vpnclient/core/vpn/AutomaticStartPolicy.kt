package dev.typenil.vpnclient.core.vpn

internal enum class AutomaticStartRequest { AlwaysOn, Restore, Stray }
internal enum class AutomaticStartBranch { AlwaysOn, Restore, Stop, MissingPrerequisites }

/** Explicit system requests are independent of our durable user intent.
 * Null/boot/update restores retain their existing desire/guard semantics. */
internal fun automaticStartBranch(
    request: AutomaticStartRequest,
    desired: Boolean,
    prepared: Boolean,
    selected: Boolean,
): AutomaticStartBranch = when (request) {
    AutomaticStartRequest.AlwaysOn -> if (prepared && selected) AutomaticStartBranch.AlwaysOn
        else AutomaticStartBranch.MissingPrerequisites
    AutomaticStartRequest.Restore -> if (desired) AutomaticStartBranch.Restore else AutomaticStartBranch.Stop
    AutomaticStartRequest.Stray -> AutomaticStartBranch.Stop
}
