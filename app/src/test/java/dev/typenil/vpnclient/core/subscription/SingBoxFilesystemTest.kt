package dev.typenil.vpnclient.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SingBoxFilesystemTest {
    private val ordinary = """{"type":"vless","server":"server.example","server_port":443,"uuid":"00000000-0000-0000-0000-000000000001","transport":{"type":"ws","path":"/tunnel"},"tls":{"enabled":true,"certificate":["synthetic inline certificate"]}}"""

    private fun checkRejected(extra: String, field: String) {
        val bad = ordinary.dropLast(1) + "," + extra + "}"
        val parser = SingBoxJsonParser()
        val expected = parser.parse("""{"outbounds":[$ordinary]}""", 1).nodes.single()
        val result = parser.parse("""{"outbounds":[$bad,$ordinary]}""", 1)
        assertEquals(listOf(expected), result.nodes)
        assertEquals("unsupported field: $field", result.skipped.single().reason)
    }

    @Test fun certificatePathSkipped() = checkRejected(
        """"tls":{"enabled":true,"certificate_path":"/synthetic/cert.pem"}""", "certificate_path",
    )

    @Test fun echConfigPathSkippedRecursively() = checkRejected(
        """"tls":{"ech":{"config_path":"/synthetic/ech"}}""", "config_path",
    )

    @Test fun arrayAndNullPathKeysStillRejected() = checkRejected(
        """"nested":[{"client_key_path":null}]""", "client_key_path",
    )

    @Test fun multipleCertificatePathsRejected() = checkRejected(
        """"tls":{"certificate_paths":["/synthetic/cert.pem"]}""", "certificate_paths",
    )

    @Test fun ordinaryNodeJsonIsUnchangedApartFromGeneratedTag() {
        val node = SingBoxJsonParser().parse("""{"outbounds":[$ordinary]}""", 1).nodes.single()
        assertEquals(JsonObject(Json.parseToJsonElement(ordinary).jsonObject + ("tag" to JsonPrimitive(node.id))),
            Json.parseToJsonElement(node.outboundJson))
    }

    @Test fun legacyWireguardCannotDropFilesystemKeysBeforeValidation() {
        val wireguard = """{"type":"wireguard","server":"server.example","server_port":51820,"private_key":"synthetic","peer_public_key":"synthetic","local_address":["192.0.2.2/32"],"key_path":"/synthetic/key"}"""
        val result = SingBoxJsonParser().parse("""{"outbounds":[$wireguard,$ordinary]}""", 1)
        assertEquals(1, result.nodes.size)
        assertEquals("unsupported field: key_path", result.skipped.single().reason)
    }

    @Test fun endpointFilesystemKeysRejected() {
        val endpoint = """{"type":"wireguard","private_key":"synthetic","address":["192.0.2.2/32"],"peers":[{"address":"server.example","port":51820,"key_path":"/synthetic/key"}]}"""
        val result = SingBoxJsonParser().parse("""{"outbounds":[$ordinary],"endpoints":[$endpoint]}""", 1)
        assertEquals(1, result.nodes.size)
        assertEquals("unsupported field: key_path", result.skipped.single().reason)
    }
}
