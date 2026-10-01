package dev.typenil.vpnclient.core.vpn

import javax.inject.Inject

/** Required production wiring; tests supply an explicit deferred/fake check, never HTTP. */
class PostStartHealthProbe(private val checkRequest: suspend () -> IpCheckResult) {
    @Inject constructor(ipProbe: IpProbe) : this({ ipProbe.check() })

    suspend fun check(): IpCheckResult = checkRequest()
}
