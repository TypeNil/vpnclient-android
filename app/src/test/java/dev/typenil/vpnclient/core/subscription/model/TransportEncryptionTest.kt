package dev.typenil.vpnclient.core.subscription.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportEncryptionTest {
    private fun node(type: String, fields: String = "", host: String = "192.0.2.1") = ProxyNode(
        id = "synthetic", name = "synthetic", protocol = ProtocolType.OTHER,
        server = "127.0.0.1", port = 443, subscriptionId = 1, rawUri = null,
        outboundJson = """{"type":"$type","server":"$host"$fields}""",
    )

    @Test fun `TLS transports require literal enabled true`() {
        for (type in listOf("http", "vless", "trojan", "hysteria2", "tuic", "anytls")) {
            for (tls in listOf("", ",\"tls\":{}", ",\"tls\":{\"enabled\":false}",
                ",\"tls\":{\"enabled\":\"true\"}", ",\"tls\":true")) {
                assertFalse("$type $tls", isEncrypted(node(type, tls)))
                assertFalse("metadata loopback must not bypass", isTunnelAllowed(node(type, tls)))
            }
            assertTrue(type, isEncrypted(node(type, ",\"tls\":{\"enabled\":true}")))
            assertTrue(type, isEncrypted(node(type, ",\"tls\":{\"enabled\":true,\"insecure\":true}")))
            assertTrue(type, isEncrypted(node(type, ",\"tls\":{\"enabled\":true,\"reality\":{\"enabled\":true}}")))
            assertFalse(type, isEncrypted(node(type, ",\"tls\":{\"reality\":{\"enabled\":true}}")))
        }
    }

    @Test fun `SOCKS and unknown types cannot spoof a TLS layer`() {
        for (type in listOf("socks", "naive", "shadowtls", "other", "ss", "hy2", "VLESS")) {
            assertFalse(type, isEncrypted(node(type, ",\"tls\":{\"enabled\":true}")))
        }
        assertFalse(isTunnelAllowed(node("other", host = "127.0.0.1")))
    }

    @Test fun `VMess payload cipher matrix and native default`() {
        for (cipher in listOf("auto", "aes-128-gcm", "chacha20-poly1305", " AUTO ", "", " AES-128-GCM ")) {
            assertTrue(cipher, isEncrypted(node("vmess", ",\"security\":\"$cipher\"")))
        }
        assertTrue(isEncrypted(node("vmess")))
        for (cipher in listOf("none", "zero", " NONE ", " ZeRo ", "unknown")) {
            val fields = ",\"security\":\"$cipher\""
            assertFalse(cipher, isEncrypted(node("vmess", fields)))
            assertTrue(cipher, isEncrypted(node("vmess", fields + ",\"tls\":{\"enabled\":true}")))
        }
        for (value in listOf("null", "true", "[]", "{}", "1")) {
            assertFalse(value, isEncrypted(node("vmess", ",\"security\":$value")))
        }
    }

    @Test fun `Shadowsocks supported encrypted methods matrix`() {
        for (method in listOf(
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
            "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
            "rc4-md5", "chacha20-ietf", "xchacha20", " AES-128-GCM ",
        )) assertTrue(method, isEncrypted(node("shadowsocks", ",\"method\":\"$method\"")))
        for (method in listOf("none", "plain", " NONE ", " PlAiN ", "", "unknown")) {
            assertFalse(method, isEncrypted(node("shadowsocks", ",\"method\":\"$method\",\"tls\":{\"enabled\":true}")))
        }
        assertFalse(isEncrypted(node("shadowsocks")))
    }

    @Test fun `WireGuard payload is encrypted without TLS`() {
        assertTrue(isEncrypted(node("wireguard")))
    }

    @Test fun `strict loopback allows sidecar but never labels it encrypted`() {
        val hosts = listOf("127.0.0.1", "localhost", "LOCALHOST", "127." + "0.0.0", "127." + "255.255.255")
        for (host in hosts) {
            val sidecar = node("socks", host = host)
            assertFalse(host, isEncrypted(sidecar))
            assertTrue(host, isTunnelAllowed(sidecar))
        }
        for (host in listOf("126." + "255.255.255", "128." + "0.0.0", "127." + "0.0.256",
            "127.1", "127." + "00.0.1", "127.0.0.1.evil.invalid", "localhost.evil.invalid", " localhost ",
            "2130706433", "::1", "::ffff:" + "127.0.0.1", "192.0.2.1", "example.invalid")) {
            assertFalse(host, isTunnelAllowed(node("socks", host = host)))
        }
        assertFalse(isTunnelAllowed(node("socks").copy(outboundJson = """{"type":"socks","server":true}""")))
    }

    @Test fun `malformed configs fail closed even with loopback metadata`() {
        for (raw in listOf("", "null", "[]", "not-json", "{}", "{\"type\":true}")) {
            val malformed = node("socks").copy(outboundJson = raw)
            assertFalse(raw, isEncrypted(malformed))
            assertFalse(raw, isTunnelAllowed(malformed))
        }
    }
}
