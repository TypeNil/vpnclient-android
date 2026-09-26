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

Real-traffic run with a LAN `socks://192.168.1.96:1080` node (dependency-free
SOCKS5 on the dev machine, real forwarding to the internet):

- Deep-link `clash://install-config?url=` + `http(s)` sub URL both import;
  insecure HTTP is correctly gated behind an opt-in checkbox.
- Connect → `tun0` up at `172.18.0.1/30` + `fdfe:dcba:9877::1/126`,
  `default dev tun0`, `Libbox.checkConfig` accepted.
- Live stats tick; `curl` through shell exits the tunnel at the SOCKS
  egress (`203.17.244.189`); server log records `CONNECT <dst>:443`.
- VPN `DnsAddresses` = `172.18.0.2`/`fdfe:..:2` — the tun's own resolver,
  not ISP `192.168.1.1` (no observed leak; not packet-captured).
- Disconnect clean: `tun0` gone, no crash. Reconnect re-establishes (new
  ifindex). `run-as ... kill -9` (real process death) → new PID + fresh
  `tun0`, tunnel restored, traffic resumes — START_STICKY verified.
- Wi-Fi→LTE: the LTE underlay is **IPv6-only** on this carrier
  (`rmnet_data3` has no v4 addr); a v4-only LAN upstream can't serve it,
  so end-to-end over LTE wasn't provable with this rig. The tunnel stayed
  up and recovered on Wi-Fi return (`UnderlyingNetworks=[779]`).
  Exit-IP masking to a remote node still needs a real dual-stack upstream.
