# R-0.4 — Offline libbox proxy-probe gate: GO (bounded)

**Date:** 2026-10-02 · **Device:** OnePlus CPH2449 (b420945a), user0 · **Core:** pinned libbox 1.14.1 AAR (SHA256-verified)

## Question

Can pinned libbox `CommandClient.urlTest(tag)` drive a credential-free
proxy-path URL measurement with **no TUN interface**, inside an exclusive
instrumentation process?

## Verdict: GO

Observed on-device (`OfflineUrlTestResearchTest`, `OK (4 tests)`, 6.7s):

| Phase | Evidence |
|-------|----------|
| 1 — startup | `tunCalls=0`, SOCKS relay live, `startupDelayMs=138` — libbox up with no TUN |
| 2 — measurement | `urlTestTime` advanced past baseline; SOCKS+HTTP+response counters advanced — a real URL test traversed the proxy path |
| 3 — containment | `rejected=1 headsDelta=0` — non-allowlisted destinations rejected at SOCKS, no bypass |
| 4 — cancel | EOF +5ms / relayEnd +7ms after close initiation; `serviceStopCalls=0` — probe cancellation attributable to our close |
| 5 — restart | second sequential lifecycle measured and tore down cleanly |

Process-global `Libbox.setup` confined to the exclusive test process; test
dirs under `targetContext.cacheDir/r04-*`, deleted on success.

## Scope exclusions — this is NOT approval for

- production latency numbers, real-network behavior, or any live
  subscription (all probes were loopback fixtures)
- underlay/`protect` socket behavior — no VpnService was running
- coexistence with a live VPN session (exclusive process only; concurrent
  `Libbox.setup` states are process-global)
- publisher-AAR Go-runtime provenance — binary pin only, not rebuilt source
- C-01 or any production feature decision

## Instrumentation-path findings (fixed / documented)

These affect how such tests must run and are captured in `VpnTestRunner`
KDoc and `.pi/notes/r04-device-evidence.md`:

1. A test APK cannot register a second `<instrumentation>` — the manifest
   merger collapses the elements. Receiver isolation therefore lives
   unconditionally in `VpnTestRunner.newApplication` (strictly protective:
   a manifest `BootReceiver` dispatch into `HiltTestApplication` can only
   crash; no instrumented test can consume it).
2. `-e` instrumentation args cannot gate `newApplication` —
   `Instrumentation.onCreate` runs *after* `makeApplication`.
3. A broadcast record already dispatched into the starting process
   (observed: stale `BOOT_COMPLETED`) delivers regardless of in-process
   `pm` disable — enabled state is resolved at dispatch time.
   Procedure: the runner's `R04ReceiverIsolation` still disables+restores
   every run; if a stale-record crash occurs, re-run immediately — the
   DISABLED residue it leaves makes the retry resolve-time safe, and
   `finish` restores the recorded original state and deletes the record
   (validated live this run).
4. Instrumentation-context (`dev.typenil.vpnclient.test`) dirs belong to a
   different UID than the instrumented app process — write tests' files via
   `targetContext`.

## Reproduce

```
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class dev.typenil.vpnclient.core.engine.singbox.OfflineUrlTestResearchTest \
  dev.typenil.vpnclient.test/dev.typenil.vpnclient.VpnTestRunner
```

If the run crashes pre-test with a `Hilt_BootReceiver` stack, re-run the
same command — the residue left by the crashed bootstrap is exactly what
makes the retry succeed.
