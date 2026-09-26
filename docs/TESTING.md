# Testing

## Commands

```bash
./gradlew test            # JVM unit tests
./gradlew lint            # Android lint
./gradlew assembleDebug   # full build incl. libbox fetch+checksum
```

## Unit test coverage (JVM)

- `UriListParserTest` — VLESS(+REALITY/WS), VMess, Trojan, SS (3 forms),
  Hysteria2, TUIC; malformed lines skipped; xhttp skipped; stable node ids;
  fragment decoding.
- `SingBoxJsonParserTest` — node outbounds extracted, `direct`/`block`/`dns`/
  `selector`/`urltest` skipped, tag→name mapping.
- `ClashYamlParserTest` — proxies mapped, unsupported types skipped.
- `SubscriptionClassifierTest` — plain/base64/JSON/YAML detection, unknown →
  typed error, empty → `EmptyResult`.
- `SubscriptionFetcherTest` (MockWebServer) — metadata header parsing
  (`subscription-userinfo`, `base64:` profile-title), HTTP error mapping,
  size cap, UA/HWID request headers.
- `RedactorTest` — UUID/secret redaction in URLs and tokens.
- `ConnectionManager`/state tests — transition rules with a fake config provider.

## On-device (emulator-5560)

```bash
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 shell am start -n dev.typenil.vpnclient/.MainActivity
```

Manual checklist: app starts → add subscription → nodes listed → select node →
VPN consent → connect → notification shows state → disconnect cleanly.

## On-device scenario matrix (research reports §7)

Beyond the smoke flow, the lifecycle contract needs these runs on the
emulator (`Medium_Phone_API_36.1`) — each maps to a report scenario:

| Scenario | Steps | Pass criteria |
|---|---|---|
| Consent revoke | Connect → system Settings → revoke VPN | `Error(PermissionRevoked)`, no fake Connected, fd closed |
| Handover | Wi-Fi → cellular → back | `Reconnecting` → `Connected`; no TUN loop; `setUnderlyingNetworks` updated |
| Network loss | Airplane mode on/off | `Reconnecting` while no physical INTERNET+NOT_VPN network exists; `Connected` only after one is positively identified |
| Process death | `adb shell run-as dev.typenil.vpnclient kill -9 <pid>` (`pidof` first; do not `force-stop`) | New PID and TUN; storage-driven restore; restart guard bounds loops |
| Engine failure | Kill/stop the remote server mid-session | Bounded auto-reconnect (≤5, 1–16 s) → `Error` on exhaustion |
| Per-app | INCLUDE/EXCLUDE while Connected | In-session TUN rebuild; selected apps only; own traffic never loops |
| DNS/IP | External IP + resolver before/after connect | Exit IP = node; DNS follows route mode; no ISP resolver leak |
| Boot | Reboot with tunnel desired | `BootReceiver` restores only when consent still granted |

`maestro/smoke.yaml` covers connect→telemetry→disconnect. Process death needs
an adb phase: `killApp`/`force-stop` is not equivalent to OS process death and
must not be used as evidence for `START_STICKY`. On `Medium_Phone_API_36.1`
the import, live node switch, process-death restore, consent revoke and
Wi-Fi→cellular handover were exercised; full network loss exposed a false
`Connected` transition and is now covered by the policy regression test and
the airplane-mode rerun. DNS leak freedom was not packet-captured.

A real connection additionally needs a working subscription/server — unit tests
cover everything up to `VpnService.prepare`; TUN and lifecycle paths need the
device scenarios above.

## 2026-09-26 — physical device `CPH2449` (Android 16, API 36)

`connectedDebugAndroidTest` — `ClientVpnServiceLifecycleTest`: 8/8 pass.

Device runs used a user-provided remote subscription (subscription URL and
node credentials intentionally excluded from this repository):

- Subscription refresh returned 3 usable nodes; the selected remote Trojan
  node connected over Wi-Fi. Native `Libbox.checkConfig` accepted the config.
- `tun0` UP at `172.18.0.1/30` + `fdfe:dcba:9877::1/126`, with
  `default dev tun0`. Live stats updated. HTTPS IP-check egress differed from
  the direct-network baseline; `example.com` DNS+TLS returned HTTP 200.
- VPN `DnsAddresses` were `172.18.0.2`/`fdfe:..:2`, the TUN resolver.
  No packet capture was done, so DNS leak freedom is not claimed.
- Disconnect removed `tun0` cleanly; reconnect created a fresh TUN. A real
  `run-as ... kill -9` process death restored the service under a new PID,
  rebuilt `tun0`, and resumed traffic (START_STICKY verified).
- Live handover with the remote node: Wi-Fi → LTE → Wi-Fi. On LTE,
  `UnderlyingNetworks` changed to the cellular network, `tun0` stayed UP,
  `example.com` returned HTTP 200, and the IP-check egress remained different
  from the direct LTE baseline. Return to Wi-Fi also kept the tunnel working.
- Correction to the earlier observation: mobile data was initially disabled.
  The previously seen IPv6-only `rmnet_data3` was IMS-only, not the active
  internet underlay. With mobile data temporarily enabled for the test, the
  active LTE interface had both a private IPv4 address and a global IPv6
  address (dual-stack). Mobile data was returned to its original disabled
  state after the test; Wi-Fi is on and the VPN remains connected.

**Still outstanding before full network-compat sign-off:** real traffic over
an IPv6-only LTE internet underlay through a dual-stack remote upstream. The
actual LTE underlay available during this run was dual-stack, and the selected
subscription node used an IPv4 address, so this specific IPv6-only case was
not established. This is not a blocker for the pushed integration work.

The temporary LAN SOCKS5/subscription servers used in the earlier run were
stopped after testing. No subscription URLs, tokens, UUIDs, or node configs
are recorded here.
