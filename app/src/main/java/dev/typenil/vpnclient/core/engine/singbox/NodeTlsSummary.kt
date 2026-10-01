package dev.typenil.vpnclient.core.engine.singbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Read-only, UI-safe view of a stored node outbound's `tls` block.
 *
 * The outbound JSON is engine-native sing-box config — opaque outside this
 * package, so this summary is the sanctioned way for the UI to surface TLS
 * posture. It exposes only displayable facts (mode, the insecure flag,
 * sanitized SNI/ALPN) and never secrets: no private keys, Reality public
 * keys/short ids, ECH configs, certificates, or raw JSON.
 *
 * The values describe the *configured* posture, not an observed handshake:
 * [Mode.CERTIFICATE] names the certificate-based authentication category —
 * `insecure`, custom certificates, or pinning still decide how strictly the
 * chain is checked, and no live handshake is observed here.
 *
 * Anything the engine itself would reject lands in [Mode.UNKNOWN]: a `tls`
 * value that isn't an object, or a recognized field carrying the wrong type
 * (`"enabled": "true"`, `"insecure": "1"`, non-array `alpn`). Those must
 * never be reported as verified/strict TLS.
 *
 * Malformed input maps to [Mode.UNKNOWN] without logging — no hostile
 * config text can end up in a log line through this path.
 */
data class NodeTlsSummary(
    val mode: Mode,
    /**
     * `tls.insecure === true` — the config explicitly disables certificate
     * checking. Reported under every mode including REALITY/UNKNOWN: a
     * request for insecure stays visible even when the mode itself is odd.
     */
    val insecure: Boolean,
    /** Sanitized `tls.server_name` — printable ASCII only, length-capped. */
    val serverName: String?,
    /** Sanitized `tls.alpn` entries — count- and length-capped. */
    val alpn: List<String>,
) {
    enum class Mode {
        /** No `tls` block (or `enabled` not true) — TLS is not configured. */
        NONE,

        /**
         * `tls.enabled` — certificate-based TLS authentication. This names
         * the category only: [insecure], a custom certificate, or pinning
         * still decide how strictly the chain is actually checked.
         */
        CERTIFICATE,

        /** `tls.reality.enabled` — Reality handshake auth replaces the CA chain. */
        REALITY,

        /** A `tls` key exists but isn't a TLS options object the engine accepts. */
        UNKNOWN,
    }

    companion object {
        // Caps keep hostile subscription input from breaking the UI: an SNI
        // is a hostname (253 max), ALPN tokens are short protocol ids.
        private const val MAX_SERVER_NAME = 253
        private const val MAX_ALPN_ENTRIES = 8
        private const val MAX_ALPN_TOKEN = 64

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Read the `tls` block of a stored sing-box outbound object.
         * Pure and total — malformed input yields [Mode.UNKNOWN], never a throw.
         */
        fun fromOutboundJson(outboundJson: String): NodeTlsSummary {
            val outbound =
                try {
                    json.parseToJsonElement(outboundJson) as? JsonObject
                } catch (e: Exception) {
                    null
                } ?: return UNKNOWN_SUMMARY
            return fromTlsBlock(outbound["tls"])
        }

        private fun fromTlsBlock(tls: JsonElement?): NodeTlsSummary {
            // `tls` absent or JSON null → the outbound has no TLS options.
            if (tls == null || tls is JsonNull) return NodeTlsSummary(Mode.NONE, false, null, emptyList())
            if (tls !is JsonObject) return UNKNOWN_SUMMARY

            // `insecure` is read FIRST so a literal true survives every
            // outcome — NONE (TLS off) and UNKNOWN (invalid fields) alike.
            // Only a real boolean literal counts; a wrong-typed value is
            // engine-rejected and must not surface as "verification on"
            // OR "verification off".
            val insecure = tls["insecure"]
            val insecureOn = insecure.boolLiteral() == true
            if (insecure != null && insecure !is JsonNull && insecure.boolLiteral() == null) {
                return unknown(insecureOn)
            }

            // `enabled` absent/false/null → TLS off (sing-box default). A
            // wrong type (string "true" etc.) is engine-rejected → UNKNOWN,
            // never reported as a working TLS config.
            val enabled = tls["enabled"]
            if (enabled == null || enabled is JsonNull || enabled.boolLiteral() == false) {
                return NodeTlsSummary(Mode.NONE, insecureOn, null, emptyList())
            }
            if (enabled.boolLiteral() == null) return unknown(insecureOn)

            // Reality: enabled===true → REALITY; object with enabled
            // absent/false/null → certificate-based mode (engine
            // semantics — a disabled reality block is inert). A non-object
            // reality or a wrong-typed enabled is engine-rejected → UNKNOWN.
            val mode =
                when (val reality = tls["reality"]) {
                    null, JsonNull -> Mode.CERTIFICATE
                    is JsonObject -> {
                        val re = reality["enabled"]
                        when {
                            re == null || re is JsonNull -> Mode.CERTIFICATE
                            re.boolLiteral() == true -> Mode.REALITY
                            re.boolLiteral() == false -> Mode.CERTIFICATE
                            else -> return unknown(insecureOn)
                        }
                    }
                    else -> return unknown(insecureOn)
                }

            // server_name: string → sanitized for display; a wrong-typed
            // value is engine-rejected → UNKNOWN.
            val serverName =
                when (val sni = tls["server_name"]) {
                    null, JsonNull -> null
                    is JsonPrimitive -> {
                        if (!sni.isString) return unknown(insecureOn)
                        sanitizeDisplay(sni.contentOrNull, MAX_SERVER_NAME)
                    }
                    else -> return unknown(insecureOn)
                }

            // alpn: must be a string array; anything else is engine-rejected.
            val alpn =
                when (val alpnEl = tls["alpn"]) {
                    null, JsonNull -> emptyList()
                    is JsonArray -> {
                        alpnEl.map { element ->
                            val token = element as? JsonPrimitive
                            if (token == null || !token.isString) return unknown(insecureOn)
                            sanitizeDisplay(token.contentOrNull, MAX_ALPN_TOKEN)
                                ?: return unknown(insecureOn)
                        }.take(MAX_ALPN_ENTRIES)
                    }
                    else -> return unknown(insecureOn)
                }

            return NodeTlsSummary(
                mode = mode,
                insecure = insecureOn,
                serverName = serverName,
                alpn = alpn,
            )
        }

        /** UNKNOWN that keeps a real `insecure: true` visible — never
         *  carries unvalidated display fields (SNI/ALPN stay out). */
        private fun unknown(insecure: Boolean) =
            NodeTlsSummary(Mode.UNKNOWN, insecure, null, emptyList())

        /**
         * A real JSON boolean literal only — `booleanOrNull` alone would
         * coerce the *string* `"true"` to true; sing-box rejects that, so
         * counting it here would be a false claim about the engine.
         */
        private fun JsonElement?.boolLiteral(): Boolean? =
            (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        /** Keep printable ASCII and cap length — a hostile SNI/ALPN can't
         *  inject control characters or unbounded text into the UI. */
        private fun sanitizeDisplay(raw: String?, max: Int): String? =
            raw?.filter { it in ' '..'~' }?.take(max)?.takeIf { it.isNotEmpty() }

        private val UNKNOWN_SUMMARY = NodeTlsSummary(Mode.UNKNOWN, false, null, emptyList())
    }
}
