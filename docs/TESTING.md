# Testing

## Commands

```bash
./gradlew test            # JVM unit tests
./gradlew lint            # Android lint
./gradlew assembleDebug   # full build incl. libbox fetch+checksum
```

## Physical device safety (hard rule)

**Never run `connectedDebugAndroidTest` (or any `connected*` Gradle task) on a
physical device.** Gradle uninstalls the app after the run, which erases the
installed app's data (subscriptions, selection, settings). Run instrumented
tests there only through a plain install plus `am instrument`:

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
./gradlew assembleDebug assembleDebugAndroidTest
& $adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s <serial> shell am instrument -w -r `
  -e class <fully.qualified.TestClass> `
  dev.typenil.vpnclient.test/dev.typenil.vpnclient.VpnTestRunner
```

Never `adb uninstall`, `pm clear` or otherwise clear app data on a physical
device. Prefer the `Medium_Phone_API_36.1` emulator for instrumented runs.

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
- `ConnectionManager`/state tests — transition rules with a fake config provider,
  including cancellable `Preparing` (`ConnectionManagerTest`).
- `AutomaticStartPolicyTest` — pure branch table for always-on / restore / stray starts.
- `RuleSetStoreTest` — valid stale copy used without network, bounded and
  cancellable first download, background `refreshStale` last-known-good rules.
- `CoreLogTest`, `CoreLogSecretsTest`, `DiagnosticExportTest`, `LogRingTest` —
  bounded core log buffer, redaction, export content.
- `LatencyProbeTest`, `ServersViewModelTest` — probe permit/deadline, connected-mode latency runs.

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

## 2026-09-26 — physical device `CPH2449` (Android 16, API 36), historical

> Historical entry. This run used `connectedDebugAndroidTest` on the physical
> device, which is now forbidden (it uninstalls the app and erases its data —
> see "Physical device safety"). It also predates the always-on, rule-set and
> diagnostics changes; do not read it as a statement about HEAD.

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

## R04 research instrumentation (opt-in)

`OfflineUrlTestResearchTest` + `R04ReceiverIsolationRestoreTest` run an
exclusive-process sing-box harness and are **gated behind the `-e r04 1`
instrumentation argument** (`R04ReceiverIsolation.RUN_ARG`) — they skip
themselves in a normal `connectedDebugAndroidTest` pass and must never be
unconditional. To run them deliberately:

```bash
adb -s <device> shell am instrument -w -r \
  -e class dev.typenil.vpnclient.core.engine.singbox.OfflineUrlTestResearchTest \
  -e r04 1 \
  dev.typenil.vpnclient.test/dev.typenil.vpnclient.VpnTestRunner
```

Timings/results land in logcat under the `R04Research` tag. Evidence and
verdicts: `docs/R04-SLICE4-SPIKE.md`.

The same runner (`VpnTestRunner`) disables `BootReceiver` through
`R04ReceiverIsolation` for **every** instrumented run (not only `-e r04 1`),
because a stale `BOOT_COMPLETED` record was seen crashing the fresh test
process on CPH2449. If a run dies before the first test with a
`Hilt_BootReceiver` stack, re-run the identical command once — the crashed run
leaves the receiver disabled plus a durable record, so the retry resolves
broadcasts without it and `finish` restores the original state. Do not
hand-restore in between (`docs/R04-RESEARCH-GATE.md`).

## Manual device matrix (status at HEAD 69c0e35)

Status wording: **JVM** = covered by unit tests only; **CPH2449 <date>** =
observed on the physical OnePlus CPH2449 (Android 16) at that date, on the
named build; **not verified** = no observation. Nothing here is a claim for
other devices or OEM builds. No subscription URLs, UUIDs, hosts or tokens are
recorded; the device runs used a private test subscription.

### Always-on VPN (scenarios A–H)

Policy under test: `AutomaticStartPolicy` (`AlwaysOn` / `Restore` / `Stray`),
see `docs/ARCHITECTURE.md`. Runs used a private instrumentation harness
(not committed) driving the real production application, on the debug build
that became commit `3904776`.

| ID | What to check | Expected | Status |
|---|---|---|---|
| A | System always-on enabled, `desired_vpn_running=false`, app not opened | Service starts the selected node (or Auto) and reaches `Connected`; desired flag becomes true only after the engine is up | CPH2449 2026-10-03 (real engine, HTTP 200). JVM: `AutomaticStartPolicyTest`; fake-engine instrumented: `ClientVpnServiceLifecycleTest` |
| B | Same with "Block connections without VPN" | Same as A | CPH2449 2026-10-03 |
| C | Kill the app process (no `force-stop`) with always-on / desired tunnel | System restarts the service; guarded restore rebuilds the tunnel | **not passed**: on CPH2449 2026-10-03 a killed service was never restarted, also for an ordinary non-always-on session (`kill -9`, `am crash`, battery whitelist tried). Cause not established (OEM behavior suspected). An earlier run on 2026-09-26 (see below) did observe a restore after `kill -9`; the two observations conflict |
| D | In-app Disconnect while always-on stays configured, with and without lockdown | `Idle`, flag false, no service for 60 s (no restart loop) | CPH2449 2026-10-03 (both variants) |
| E | Always-on turned off in system settings | Nothing starts | CPH2449 2026-10-03 |
| F | Always-on with no selected node (F0) / no enabled nodes (F1) | Localized alert, service stops, no restart loop, flag stays false | CPH2449 2026-10-03 (60 s each, no service, no loop). Also instrumented with a fake engine: `alwaysOn_missingPrerequisites_stopsWithoutEngine` (the 12-test `ClientVpnServiceLifecycleTest` class passed on CPH2449 via `am instrument`, 2026-10-03) |
| G | Normal connect → reconnect → disconnect | HTTP works both times, fresh engine epoch, clean `Idle` | CPH2449 2026-10-03 |
| H | Reboot with always-on enabled (needs the user to unlock the screen) | VPN comes up after unlock; with lockdown, traffic is blocked until then | **not verified** (requires the user; no unlock bypass attempted) |

Not claimed: that system always-on behaves like a kill switch. The
fail-closed limits are in `docs/SECURITY.md`.

### Other device checks

| Area | What to check | Expected | Status |
|---|---|---|---|
| Core logs in Diagnostics | Connect with a node that produces core warnings; open the export | Bounded, redacted core section present; no UUID/password/host/key/subscription URL in the export or logcat; screen-off pauses the tail and screen-on resumes it; disconnect clears it | CPH2449 (WP-4b, debug build at `1701ab0`, private harness reading the export pipe): 91 warn/error lines exported, secret scans 0; screen-off 60 s paused, `Reconnecting` 0. The real share chooser and third-party receivers: **not verified**. Empty-section note when there are no core logs: JVM (`DiagnosticExport`) |
| Preparing cancel | Tap Connect, then Cancel while `Preparing` | `Preparing → Idle`, no service record, no TUN, UI `Idle` | CPH2449 2026-10-04 (HEAD `69c0e35`): observed once, `Preparing → Idle` in 58 ms, no `ClientVpnService` record, no `tun0`. Race window is ~0.2 s; one hit only. JVM: `ConnectionManagerTest` (3 preparation tests) |
| Rule sets, old files | Age `files/rule_sets/*.srs` mtime (content unchanged), Connect | Connect does not wait on the network and uses the local copy; files are refreshed after `Connected` | CPH2449 2026-10-04: mtime set to 2020-01-01 via `run-as touch`; `Preparing → Connected` in 421 ms; both files replaced after `Connected`. Unreachable CDN on device: **not verified** (JVM: `RuleSetStoreTest`) |
| Rule sets, no copy and no bundle | Connect with the CDN unreachable | Bounded (20 s), cancellable, `StartFailed("routing lists unavailable")` | JVM only (`RuleSetStoreTest`); not verified on device |
| Service restore after APK replace | `adb install -r` while connected with `desired_vpn_running=1` | Service restored by the system, `Connected` within seconds | CPH2449 2026-10-04: restored twice (~0.3 s after process start). Not the same as scenario C (no process kill by shell) |
| Screen-off | Screen off 60 s while connected | Status updates paused, core subscription dropped, no `Reconnecting`; resumes on screen-on | CPH2449 (WP-4b): observed, see core logs row |
| HWID acceptance | Refresh a Remnawave subscription from the app | No device-limit error (`DeviceLimitReached` is a 404 with `x-hwid*` headers) | partial, CPH2449 2026-10-04: one in-app refresh updated the row (`lastError` empty, node count unchanged). Provider-side device slot: **not verifiable** from the client |

