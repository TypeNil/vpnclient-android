# Subscriptions

Pipeline: `fetch → classify → decode → parse → persist → (re)compile on connect`.

## Supported inputs

| Format            | Detection                                   | Parser             |
|-------------------|---------------------------------------------|--------------------|
| Base64 URI list   | whole-body Base64 → decoded lines are URIs  | `UriListParser`    |
| Plain URI list    | lines start with known schemes              | `UriListParser`    |
| sing-box JSON     | has `inbounds`+`outbounds` with `type`/`tag`| `SingBoxJsonParser`|
| Clash/Mihomo YAML | `proxies:`/`proxy-providers:` keys or MIME  | `ClashYamlParser`  |
| Xray JSON         | `outbounds[].protocol`                      | rejected (typed error) |

Share-link schemes: `vless://`, `vmess://`, `trojan://`, `ss://` (all three SIP002
variants), `hysteria2://`/`hy2://`, `tuic://`. Unknown/malformed lines are skipped;
only a fully empty result fails the refresh. `xhttp` transport nodes are skipped
(sing-box has no XHTTP).

## Parsed node identity

`ProxyNode.id` = SHA-256 of `subscriptionId|scheme|server:port|credential`
(uuid or password). Stable across refreshes → selection survives updates, and
identical nodes from different subscriptions don't collide.

`outboundJson` is a complete sing-box outbound object — the app treats it as
opaque and embeds it verbatim into the compiled config.

## Failure model

Typed errors only (`SubscriptionError` sealed class): `Http(code)`, `Network`,
`Timeout`, `TooLarge`, `UnsupportedFormat`, `ParseFailed`, `EmptyResult`,
`ConfigRejected`, `DeviceLimitReached`, `RemnawaveError`. Failed refresh →
`lastError` recorded on the profile, stored nodes untouched (last-known-good).

## Commit rule

A refresh commits only after fetch → classify → parse → **engine validation**
(`Libbox.checkConfig` on the full compiled config via
`SubscriptionCandidateValidator`) all succeed. Node swap and success metadata
land in one Room transaction. If the refresh removed the currently selected
node, the selection is cleared so the next connect picks a valid default.

## Limits

- Response body cap: 8 MiB (declared + streamed).
- Timeouts: 15 s connect / 30 s read.
- All imported content treated as untrusted; parse fully succeeds before DB writes.
