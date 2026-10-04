# A-03: DNS diagnostics while connected

Goal: make `HealthLevel.DnsReachable` a real, honest signal so users can tell
"server dead" from "DNS broken" from "site blocked". Principles (DEC-07):
bounded, generation-tagged, Connected-only; failure = `Degraded` (never
`Failed`, never a reconnect, never "no egress"); not-runnable = `Unverified`;
DNS success never stands in for egress (A-01) and vice versa.

## 1. How DNS works in our config (`ConfigCompiler.kt` ~166-265, `DnsPolicy.kt`)

- Servers: `local` (Android resolver on the underlay via `LocalDnsResolver`)
  and `remote` (preset/custom upstream, DoH `https://1.1.1.1/dns-query` by
  default, `detour` = the selector, so it rides the chosen proxy). Hostname
  upstreams bootstrap via `local`. No fakeip; `reverse_mapping` is on.
- Route rule `protocol=dns -> hijack-dns` feeds app DNS from the TUN into the
  engine DNS module. `dns.final`: `remote` (policy/proxy-only/bypass_ru),
  `local` (proxy_blocked). `bypass_ru`: geosite-ru names -> `local`;
  `proxy_blocked`: blocked-list names -> `remote`.
- So which resolver answers depends on the *name* and route mode.

## 2. Correction of a premise: our own sockets ARE in the TUN

`resolvePerAppPlan` (`PerAppPolicy.kt`) never disallows our own package: it
rides the tunnel in every mode (ALL/EXCLUDE: empty allow-list = everyone;
INCLUDE: self is added to the allow-list). `addDisallowedApplication(self)`
exists only in a dead edge case (`ClientVpnService` ~1456, allow-list present
but zero entries resolved - self always resolves). Only *engine* sockets leave
the TUN, via `protect()`. Evidence on device: the A-01 negative run (stopped
local SOCKS node) timed out at 8 s; had app traffic bypassed the TUN it would
have succeeded directly.

Consequences: `getByName` in-process does exercise the tunnel's DNS path,
BUT it uses Android's per-network cache and gives no cancel/rcode. A-01
(`IpProbe`, OkHttp `Dns.SYSTEM`, fresh pool, 8 s) uses the same path, so DNS
is implicitly part of A-01 yet its failures are flattened to
`timeout/network`, and its success is deliberately `Unverified`
(`AppHttpRouteUnverified`: it cannot say whether the request went through the
proxy or a direct rule).

## 3. Mechanisms

| | Mechanism | Verdict |
|---|---|---|
| a | libbox DNS exchange via CommandClient | **No API.** Pinned `libbox-1.14.1.aar` (sha256 93b2596c...): `CommandClient` public methods = clearLogs, closeConnection(s), connect(WithFD), disconnect, getAPIVersion, getDeprecatedNotes, getStartedAt, getSystemProxyStatus, selectOutbound, serviceClose/Reload, setClashMode, setGroupExpand, setSystemProxyEnabled, start{NetworkQuality,STUN}Test, urlTest (+ tailscale/usb/openvpn); `javap` of `Libbox` has no DNS/query/resolve. `LocalDNSTransport` is engine->platform (engine asks Android to resolve), the opposite direction. `Libbox.newHTTPClient()` is a standalone Go HTTP client, not the engine path. No clash API in our config. Would also need libbox types outside `core.engine.*`. Rejected. |
| b | UDP/53 to a DNS IP via `protect()` socket | Rejected: protected socket skips the TUN, so it tests the underlay, not the engine path; default upstream is DoH so UDP/53 is not the real transport; sends our query to a hard-coded third party. |
| c | Reuse A-01 HTTP | Rejected as the DNS signal: cannot split DNS from connect/timeout, success is route-unverified, and coupling would let one fake the other. At most A-01 could classify `UnknownHostException` later. |
| e | **App-process `DnsResolver.query(null network, "example.com", TYPE_A, FLAG_NO_CACHE_LOOKUP or FLAG_NO_CACHE_STORE, ...)`** (API 29+, cancellable via `CancellationSignal`) | **Chosen.** `null` network = process default network, which for our app is the VPN (no `bindProcessToNetwork`, no `allowBypass`). Goes app -> TUN -> hijack-dns -> engine resolver (`remote` through the proxy, or `local`, by name/mode). Android cache bypassed (engine cache is not, and is not claimed to be). Source: developer.android.com `DnsResolver` (null = default network; FLAG_NO_CACHE_LOOKUP=4, FLAG_NO_CACHE_STORE=2, API 29). |
| d | Nothing | Fallback only for API < 29 (no cancellable/no-cache API): slot stays `Unverified`. |

## 4. Recommendation: (e)

- `DnsProbe` (`core/vpn`, android.net only, no libbox) + a writer in
  `ConnectionManager.schedulePostStartCheck`: after the existing
  Connected/engine/generation barrier, with its own `DnsReachable` token, in a
  child of `postStartJob` (cancelled with it, one per generation+engine - same
  single-flight guard as A-01). Timeout 4 s (`withTimeoutOrNull` + cancel).
  Result published only if still Connected for the same generation/engine, and
  the store rejects stale tokens/timestamps. Writes health only: no state
  change, no reconnect, no touching A-01 levels. Size est. 250-280 lines incl.
  tests; display commit ~60 more. Name: neutral, constant, no user data.
- Risks: probe name is mode-dependent (neutral name -> `remote`; in
  proxy_blocked it hits `local`) - so the claim is "the app's DNS path
  answered", not "the proxied resolver answered". Private DNS strict mode or a
  captive resolver can fail the probe while sites work from cache - hence
  `Degraded`, not `Failed`. Engine cache may serve the answer. One extra DNS
  query for a fixed neutral name per connect. Path change invalidates the slot
  (PATH_LEVELS) and, like A-01, it is not re-run (known A-01/A-03 limit).

## 5. Outcome -> text (diagnostics row; scope "App DNS query only; proxy and egress not verified")

| Outcome | Status | EN | RU |
|---|---|---|---|
| Answer with >=1 address | Ok | DNS query answered through the VPN's DNS path; server egress and answer content not verified | DNS-запрос получил ответ через DNS-путь VPN; выход через сервер и содержимое ответа не проверены |
| 4 s timeout | Degraded | No DNS answer in time; this does not prove the server is down or a site is blocked | DNS не ответил вовремя; это не значит, что сервер недоступен или сайт заблокирован |
| Error / empty / NXDOMAIN / parse | Degraded | DNS query returned no usable answer; this does not prove the server is down or a site is blocked | DNS-запрос не вернул пригодного ответа; это не значит, что сервер недоступен или сайт заблокирован |
| Not run (API < 29, stale, left Connected, setup error) | Unverified | DNS not checked | DNS не проверялся |

Never claimed: "DNS is fine"/"leak-free", which resolver answered, correct
answers, anything about egress. Never produced: Failed, auto-reconnect.
