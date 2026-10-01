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

Explicitly unproven for Slice 4: only `urlTest(groupTag)` was exercised —
whether `urlTestAll` (or an equivalent batch call) exists in pinned
libbox 1.14.1 was NOT checked. Connected-mode latency work is a new spike,
not a continuation of this gate. Also: `urlTestDelay` and the
disconnected-mode `LatencyProbe` TCP-connect numbers are not comparable
metrics — never render them in the same list.

## Instrumentation-path findings (fixed / documented)

All findings below were observed on the OnePlus CPH2449 (b420945a) —
single-device evidence, not a platform guarantee.

1. A test APK cannot register a second `<instrumentation>` — the manifest
   merger collapses the elements. Receiver isolation therefore lives
   unconditionally in `VpnTestRunner.newApplication` (strictly protective:
   a manifest `BootReceiver` dispatch into `HiltTestApplication` can only
   crash; no instrumented test can consume it).
2. `-e` instrumentation args cannot gate `newApplication` —
   `Instrumentation.onCreate` runs *after* `makeApplication` (observed:
   an arg-latched flag was still unset when `newApplication` ran).
3. A broadcast record already dispatched into the starting process
   (observed: stale `BOOT_COMPLETED`) still delivers after the in-process
   `pm` disable — on this device the enabled check happens at dispatch
   time, not at delivery. Procedure: the runner's `R04ReceiverIsolation`
   still disables+restores every run; if a stale-record crash occurs,
   re-run the identical command — the DISABLED residue it leaves makes
   the retry resolve-time safe, and `finish` restores the recorded
   original state and deletes the record (validated live, twice).
   Launching the production app first did NOT prevent the stale-record
   dispatch.
4. Instrumentation-context (`dev.typenil.vpnclient.test`) dirs belong to a
   different UID than the instrumented app process — write tests' files via
   `targetContext` (the `.test` package's `cacheDir` fails mkdirs EACCES).

## Reproduce

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class dev.typenil.vpnclient.core.engine.singbox.OfflineUrlTestResearchTest \
  -e r04 1 \
  dev.typenil.vpnclient.test/dev.typenil.vpnclient.VpnTestRunner
```

`-e r04 1` is mandatory — without it an `Assume` gate in `setupNative`
skips the whole class so that plain suite runs (`connectedDebugAndroidTest`)
never touch process-global `Libbox.setup` state shared with other engine
tests.

If the run crashes pre-test with a `Hilt_BootReceiver` stack, re-run the
same command — the residue left by the crashed bootstrap is exactly what
makes the retry succeed.

## Raw device evidence

Gate phases (logcat `R04Research`, run of 2026-10-02 00:23):

```text
phase=1 ok: startupDelayMs=138 socksConnects=1 heads=1 tunCalls=0
phase=2 ok: urlTestTime advanced past baseline; socks+http+response counters advanced
phase=3 ok: rejected=1 headsDelta=0
phase=4 ok: cancellation attributed — EOF +5ms, relayEnd +7ms after close initiation (holdEnteredAt=2103ms before close); serviceStopCalls=0
phase=5 ok: sequential restart measured and tore down
```

Post-run state: `disabledComponents` absent (BootReceiver back to manifest
DEFAULT), `files/r04_receiver_isolation_state` deleted, no `r04-*` dirs in
`cache/`, app+test processes stopped, app data untouched.
`R04ReceiverIsolationRestoreTest` passed on a retry after its own
pre-test crash — 4/4.
