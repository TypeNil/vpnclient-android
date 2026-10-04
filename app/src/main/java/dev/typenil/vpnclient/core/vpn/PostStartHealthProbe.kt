package dev.typenil.vpnclient.core.vpn

import javax.inject.Inject

/** Required production wiring; tests supply explicit deferred/fake checks, never HTTP or DNS. */
class PostStartHealthProbe(
    private val dnsRequest: suspend () -> DnsCheckResult = { DnsCheckResult.NotRun },
    private val checkRequest: suspend () -> IpCheckResult,
) {
    @Inject constructor(ipProbe: IpProbe, dnsProbe: DnsProbe) : this({ dnsProbe.check() }, { ipProbe.check() })

    suspend fun check(): IpCheckResult = checkRequest()

    suspend fun checkDns(): DnsCheckResult = dnsRequest()
}
