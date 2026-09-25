package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPolicyTest {
    @Test
    fun `round-trip keys for presets and custom`() {
        DnsUpstream.presets.forEach { preset ->
            assertEquals(preset, DnsUpstream.fromKey(preset.key))
        }
        val custom = DnsUpstream.Custom("https://dns.example.com/dns-query")
        assertEquals(custom, DnsUpstream.fromKey(custom.key))
        assertEquals(DnsUpstream.Cloudflare, DnsUpstream.fromKey(null))
        assertEquals(DnsUpstream.Cloudflare, DnsUpstream.fromKey("bogus"))
        assertEquals(DnsUpstream.Cloudflare, DnsUpstream.fromKey("custom: not a spec !!"))
    }

    @Test
    fun `parseCustom accepts supported schemes`() {
        val cases =
            mapOf(
                // input -> canonical stored spec
                "https://1.1.1.1/dns-query" to "https://1.1.1.1/dns-query",
                "tls://one.one.one.one" to "tls://one.one.one.one",
                "quic://dns.adguard-dns.com" to "quic://dns.adguard-dns.com",
                "udp://9.9.9.9" to "udp://9.9.9.9",
                // bare IP normalizes to udp:// — the spec must carry a scheme
                // so the compiler can pick the server type from it
                "1.1.1.1" to "udp://1.1.1.1",
            )
        cases.forEach { (input, canonical) ->
            assertEquals(canonical, DnsUpstream.parseCustom(input)?.spec)
        }
    }

    @Test
    fun `parseCustom rejects bad specs`() {
        listOf(
            "",
            "  ",
            "ftp://1.1.1.1",
            "https://",
            "https://a b.com/x",
            "plain text",
            "tcp://1.1.1.1",
            // a port suffix isn't an IP literal — udp://9.9.9.9:9953 is the
            // accepted spelled-out form, bare "9.9.9.9:9953" is rejected
            "9.9.9.9:9953",
        ).forEach { assertNull("must reject: $it", DnsUpstream.parseCustom(it)) }
    }

    @Test
    fun `hostname upstreams flag for bootstrap`() {
        assertTrue(DnsUpstream.parseCustom("tls://one.one.one.one")!!.hostname)
        assertTrue(DnsUpstream.parseCustom("https://dns.example.com/dns-query")!!.hostname)
        assertFalse(DnsUpstream.parseCustom("tls://1.1.1.1")!!.hostname)
        assertFalse(DnsUpstream.Cloudflare.hostname)
        assertFalse(DnsUpstream.parseCustom("udp://9.9.9.9")!!.hostname)
    }

    @Test
    fun `summary redacts the custom path but keeps the host`() {
        assertEquals(
            "policy:cloudflare",
            DnsProfile(DnsMode.POLICY, DnsUpstream.Cloudflare).summary,
        )
        assertEquals(
            "custom:https://x.com/…",
            DnsProfile(DnsMode.PROXY_ONLY, DnsUpstream.Custom("https://x.com/dns-query")).summary,
        )
    }

    @Test
    fun `dnsMode fromKey is lenient`() {
        assertEquals(DnsMode.POLICY, DnsMode.fromKey(null))
        assertEquals(DnsMode.POLICY, DnsMode.fromKey("bogus"))
        assertEquals(DnsMode.PROXY_ONLY, DnsMode.fromKey("proxy_only"))
    }
}
