# D-4 — encryption to the tunnel endpoint

## Source findings

The canonical input is `ProxyNode.outboundJson`, not its display protocol,
server or share URI. `SingBoxJsonParser` preserves native security fields;
`UriListParser`, `ClashYamlParser` and `parse/OutboundBuilders` generate them.
`ConfigCompiler.build` currently includes every usable node in both groups.

| Native transport | Encrypted to endpoint when | Otherwise |
| --- | --- | --- |
| SOCKS | No supported TLS layer in SOCKS outbound | Unencrypted |
| HTTP proxy | `tls.enabled` is boolean true | Unencrypted |
| VLESS | TLS enabled (including enabled Reality inside TLS) | Unencrypted |
| Trojan | TLS enabled (including Reality) | Unencrypted |
| VMess | TLS enabled, or payload cipher auto (also absent/empty)/aes-128-gcm/chacha20-poly1305 | none/zero without TLS: unencrypted |
| Shadowsocks | Recognized encrypted method | none/plain: unencrypted |
| Hysteria2, TUIC | Mandatory TLS/QUIC, explicit enabled TLS in accepted configs | Missing/disabled TLS: fail closed |
| AnyTLS | Mandatory TLS, explicit enabled TLS | Missing/disabled TLS: fail closed |
| WireGuard | Native WireGuard encrypted endpoint protocol | Native validation still checks keys/peers |
| NaïveProxy | HTTPS encrypts in principle | Not supported by current node parser |
| ShadowTLS | Not sufficient evidence of payload encryption alone | Non-node type ignored by parser |

URI/Clash Trojan builders always enable TLS, even for a share parameter
claiming otherwise; JSON Trojan can lack it. VMess builders default to auto.
Shadowsocks builders preserve methods, including none/plain. Plugins are not
accepted as proof of encryption. Unknown methods/types or malformed JSON are
not evidence of encryption. Native validation remains required for all nodes.

Deviation from the suggested Naïve/ShadowTLS assumption: neither currently
becomes a supported node. Do not treat OTHER as encrypted or infer encryption
from a ShadowTLS handshake; a future supported composition needs explicit
payload-transport modelling and tests before admission.

## P-03 is separate

`NodeTlsSummary` reports configured TLS mode and verification settings, not
payload encryption or a verified handshake. NONE can describe encrypted
Shadowsocks/WireGuard. `insecure=true` still encrypts but skips certificate
verification: retain the P-03 warning, do not claim authenticated security.
Reality only counts inside an enabled TLS layer. Neither UI label is a probe.

## Pure policy

Place `isEncrypted(node)` outside `core.engine.*`, with kotlinx JSON only.
Read native type/security/method and strict boolean TLS enabled; normalize
method/security case and surrounding whitespace for classification only.
Use known cipher sets, not “anything except none”. Do not mutate configs.
Expose `isTunnelAllowed(node)` separately: encrypted OR actual loopback host.
Loopback is case-insensitive localhost or canonical four-octet IPv4 in 127/8.
No DNS lookup, aliases, suffix matches, abbreviated/integer IPs or IPv6 bypass.
Use the actual outbound server, not separately editable/display metadata.
Pin allowed localhost to 127.0.0.1 in compiled JSON, never network DNS.
An allowed local sidecar remains labelled unencrypted; its onward connection
is outside this policy's guarantee.

## v1 enforcement

- Mark retained unencrypted nodes in server rows/details and Home picker.
- Block manual selection at compilation and before a live selector switch;
  report a typed error with localized en/ru text, never config/host details.
  A blocked live switch keeps the existing Connected session and reports a
  separate selection error; never publish Error while that tunnel is alive.
- Remove disallowed nodes from native outbounds/endpoints and selector/urltest.
  Auto can contain encrypted or loopback nodes only; no eligible nodes is a
  typed safety failure, not a fabricated connected state or “no servers”.
- No override, flavor or database migration. Existing rows use the same gates.
- Import uses **marking**, not SkippedNode: retain nodes after full native
  candidate validation. This preserves refresh/last-known-good semantics and
  lets users see the reason. Candidate validation must remain independent
  from runtime filtering; no public runtime bypass switch.
- `NodeConfigProviderImpl` covers normal connect; `compileOutcome` carries
  typed failures through ordinary/always-on service starts and rebuilds.
  `ConnectionManager.applyDesiredSelection` also gates live switches.
  `RoutingRuleSetValidator` prechecks the eligible runtime configuration.
- Auto labels follow actual engine groups; no unsafe fallback or fake egress.

## Checks

Native-invalid imports still reject the whole candidate, including missing
mandatory QUIC TLS. Runtime marking is not permission to bypass validation.
VMess default verified in sing-box v1.14.1 `protocol/vmess/outbound.go`;
SS methods: https://sing-box.sagernet.org/configuration/outbound/shadowsocks/.
Legacy supported SS ciphers still encrypt, but do not imply integrity/security.

JVM matrix covers every supported row, malformed/unknown types, TLS/Reality,
case/whitespace, metadata spoofing, strict loopback boundaries, compiler group
membership and selected-node rejection. Mutate encryption and loopback gates
and observe failures. Run full JVM tests, lint and assembleDebug. Verify VPN
on emulator/device with synthetic data; on CPH2449 preserve/restore baseline,
use exact fresh own-app UI selectors, no screenshots/connected*/data clearing.
