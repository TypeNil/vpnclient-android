# R-0.4b — Can a second libbox instance coexist with a live VPN session? (C-01 status)

**Date:** 2026-10-04 · **Type:** source research, no production code changed, nothing run on a device.
**Evidence labels:** *[src]* read in source (path:line), *[javap]* our AAR, *[hyp]* hypothesis, *[unv]* not verified.

Builds on `docs/R04-RESEARCH-GATE.md` (offline urlTest works in an *exclusive* test process on
loopback fixtures) and `docs/R04-SLICE4-SPIKE.md`. Open question here: a second instance next to
the production engine in the main process, with real nodes.

## Sources
- Upstream `SagerNet/sing-box` tag `v1.14.1`, paths below are relative to it (line numbers of
  the raw tag files, read 2026-10-04).
- Our AAR `core-native/libbox-1.14.1.aar` (`classes.jar` via `javap`): the Java surface matches
  upstream `experimental/libbox` — `Libbox.setup(SetupOptions)`, `Libbox.newCommandServer(...)`,
  `CommandServer(handler, platform)`, `PlatformInterface` (27 methods), `newStandaloneCommandClient()`.
  Native bytecode was not diffed against that source *[unv]*; the AAR is built by a third party
  (`singbox-android/libbox`), so "same source" is an *[hyp]* backed by identical API.
- Roadmap stop rule (`E:\Reverse\VPNCLIENT_IMPLEMENTATION_ROADMAP.md:415`): if an ephemeral runtime
  conflicts with a live VPN, **stop C-01**, record a PoC/alternative, never rename TCP latency.

## 1. Inventory at HEAD (C-01 … C-03)

| ID | Status | Evidence |
|---|---|---|
| C-01 engine-assisted *offline* latency | **Not implemented** | The only production `CommandServer(...)` is the live engine's (`SingBoxEngine.kt:178`). The offline path is a direct TCP connect (`LatencyProbe.kt:34-98`, 8 permits `:102`, 3 s `:101`; `ServersViewModel.kt:572-601`). R-0.4 code exists only in `androidTest` (`OfflineUrlTestResearchTest.kt`). |
| C-02 batch test + cancel | **Partial** | "Test all" = `testLatency()` (`ServersViewModel.kt:563-607`) from the button `ServersScreen.kt:164-169`. Progress: one spinner flag `_testing` (`:802-805`) and per-node `tested` set — no counter. Cancel: none in the UI (button is only disabled while running); runs stop when the VM scope dies; connected mode has latched cancel semantics (`:698-767`, `6c1ae2e`). Persist: **none** — results live in `probeSurface` (`:175`), lost with the process, no `lastLatencyMs`/`lastCheckedAt` column (Room v7). Sort by latency: yes (`ServerSortMode.Latency`, `:378-381`). Concurrency: 8 (roadmap says cap 3, only meaningful for an engine probe). |
| C-03 Auto / fastest | **Partial** | Compiler emits `urltest` group `auto`: interval 3m, tolerance 50, idle_timeout 20m (`ConfigCompiler.kt:134-145`); selector default `auto` (`:131`). UI: Home shows the resolved leaf only when Connected and the node label contains `→` (`AfterglowHome.kt:499-507`), otherwise the static "Auto" subtitle (`:506`); manager updates the label from the group's `selected` (`ConnectionManager.kt:831-834`). Hysteresis is only libbox's tolerance (50 ms, upstream `protocol/group/urltest.go:322`); the app adds none. Stale data: `HomeViewModel.kt:195` takes the minimum positive `urlTestDelayMs` **without** checking `urlTestTime`, so a stale history entry can name the "best" node; the freshness gate exists only in the Servers run (`ServersViewModel.kt:259-262`). No timestamp or methodology text in the Home UI. |

## 2. R-0.4b answers

### a) What does `Libbox.setup` set; can it be called again?
- **[src]** It writes ~17 package-level variables (`experimental/libbox/setup.go:22-39`): `sBasePath`,
  `sWorkingPath`, `sTempPath`, `sCommandServerListenPort`, `sCommandServerSecret`, `sLogMaxLines`,
  `sDebug`, `sCrashReportSource`, OOM/power flags… `Setup` (`:107-111`) then `MkdirAll`s the dirs and
  calls `redirectStderr` (`log.go:66-92`), which archives the previous crash log and installs a new
  **process-wide** crash output with `debug.SetCrashOutput` (`:84`).
- **[src]** The vars are read lazily by running code, not captured per instance:
  the command **client** re-reads `sBasePath` on every dial (`command_client.go:128-135`), the server
  reads it in `Start` (`command_server.go:146-178`), config snapshots use it
  (`log.go:56-64`), `baseContext` uses `sWorkingPath/sTempPath` (`config.go:38`).
- **[src]** `ReloadSetupOptions` (`setup.go:87`) only re-applies OOM/memory options — it cannot move paths.
- Production calls `Setup` once: `basePath=files/sing-box`, `listenPort=0` (unix socket),
  `LibboxRuntime.kt:25-41`.
- **Verdict (unambiguous):** a second `Setup` with another base path re-points the *live* engine's
  client reconnects (`SingBoxEngine.connectClientLocked`, `:261+`; screen-on reconnect) and its
  snapshot path, and replaces the crash-output file. Calling it again with the same options is
  harmless to paths but still rewrites crash output. A "set up, probe, set up back" sequence
  has a race window with live reconnects. **Do not call `Setup` while a session is live.**

### b) Can a second `CommandServer`/engine run next to the live one in one process?
- **[src]** `Start()` with `listenPort==0` does `os.Remove(sBasePath/command.sock)` and binds a new unix
  socket on the same path (`command_server.go:146-153`). With one `Setup` (shared paths) the second
  server **steals the socket path** from the live one; live clients that reconnect then reach the
  ephemeral server (wrong groups/logs/selectOutbound target). Go's `UnixListener` unlinks its path on
  `Close` by default *[Go stdlib]*, so closing the ephemeral server leaves the live server with **no
  socket path**. Established streams survive, new connects fail.
- **[src]** `StartOrReloadService` overwrites `sBasePath/configuration.json` (`command_server.go:215`,
  `log.go:60-64`) — the crash-report config snapshot of the live session.
- **[src]** Per instance (no conflict): the `StartedService`, box context/registries, URL-test
  history (taken from the instance context, `protocol/group/urltest.go:236-239`), log ring.
- **[src]** Our compiled config sets no `cache_file`/clash API (`ConfigCompiler.kt:160-164` only `log`),
  so there is no bbolt file lock to fight over; a synthetic probe config would not either.
- **[javap]** There is no public "box without CommandServer" entry point in our AAR: `Libbox` exposes
  `newCommandServer`, `checkConfig`, `newStandaloneCommandClient` only. `newStandaloneCommandClient`
  (`command_client.go:82-84`) is a gRPC **client** to an existing server; it starts no core
  (resolves R-0.4 question "standalone client suitability": **not usable for offline probing**).
- **Verdict (unambiguous):** in the main process, with the live engine running, an ephemeral
  `CommandServer` conflicts (socket path, config snapshot, global setup). The roadmap stop rule applies:
  **C-01 as "ephemeral libbox while connected" is stopped.**
- Still open *[hyp]*: the same code *while disconnected* (no live server). Collisions then reduce to
  serialising with `SingBoxEngine.start/stop` (`lifecycleMutex`, `:98,173,364`) so Connect never races a
  probe teardown.

### c) Where would the ephemeral instance's probes go while a VPN is live?
- **[src]** The app's own package rides the tunnel in every per-app mode (`PerAppPolicy.kt:46-50`,
  `VpnSocketProtector.kt:7-13`). Go sockets created by the ephemeral outbounds are therefore sent
  **into the live TUN** (self-loop: probe = tunnel + node chain) unless the platform hook protects them.
- **[src]** The hook is `PlatformInterface.usePlatformAutoDetectInterfaceControl()/autoDetectInterfaceControl(fd)`
  (`libbox/service.go:47-53`). A minimal platform for the ephemeral instance can return `true` and
  call `VpnService.protect(fd)`. The app has `ClientVpnService.protectSocket(fd)` (`:1474`) and
  `VpnSocketProtector`, but the latter takes a `java.net.Socket` (`:32`), not a raw fd — a small new
  fd-level entry would be needed *[src]*. No "second PlatformInterface" is needed *with* the live service,
  but it must implement all 27 methods (R-0.4's `ResearchPlatform` did, with stubs).
- **[src]** The platform also owns the default-interface monitor and local DNS
  (`service.go:109-117`, `config.go:28-33`); our DNS bridge depends on `NetworkMonitor.defaultNetwork`
  (`LocalDnsResolver.kt:41,88`). The ephemeral instance needs its own feed or must share the live
  monitor *[hyp]* (not tried).
- **Reliability consequence:** with protect the result is the direct device→node path, comparable to the
  live engine's own urltest (same protect mechanism); without it the number is wrong by the whole
  tunnel RTT. `protect` failure must invalidate the sample (as `LatencyProbe.kt:68` does for TCP).
- *[hyp, unv]* A **separate Android process** for probing would avoid (a)/(b), but a second process has
  no `VpnService` instance to call `protect` on; whether `protect(fd)` works from there is untested.

### d) What does an engine probe add over `LatencyProbe`?
- **[src]** `urltest.URLTest` dials the node through the real outbound, resets the timer after a lazy
  handshake, sends `HEAD https://www.gstatic.com/generate_204` over it and reports the full
  elapsed time (`common/urltest/urltest.go:111-145`). Batches run 10 in parallel
  (`group/urltest.go:401`); one `URLTest` call per group dispatches asynchronously
  (`daemon/started_service.go:706-740`, ~8 ms per R-0.4).
- It answers what TCP connect cannot: "does the node accept **my credentials/REALITY/SNI/transport** and
  proxy HTTPS to the internet right now", not just "is the port open". Typical TCP-OK/proxy-fail cases:
  wrong UUID/password, REALITY mismatch, SNI blocked, dead upstream behind a live front.
- The number is **not comparable** to TCP RTT (proxied TLS to a third host: several RTTs). Must be
  labelled "proxy HEAD time", never "ping/TCP latency".
- Costs: instance start (~124 ms `startOrReloadService` in R-0.4), memory, and a native crash would take
  down the whole app process, VPN included (single process, roadmap P-05).

## 3. Minimal PoC (not run) — only if variant B is wanted
Emulator `Medium_Phone_API_36.1` only (never the owner's device while connected; no `connected*` on CPH2449):
1. Gated instrumented test `-e r04b 1` (same runner rules as R-0.4, `docs/TESTING.md`).
2. Fake production engine up (synthetic loopback node + `ResearchPlatform`), then an ephemeral
   `CommandServer` on the same `Setup`: assert `command.sock` identity changes, live client reconnect
   dials the wrong server, `configuration.json` overwritten. Expected: **conflict reproduced**.
3. Disconnected case: probe a synthetic node, close, then start the live engine; assert Connect
   unaffected and `command.sock` unlinked/recreated once.
4. Protect case (needs a prepared VPN on the emulator): ephemeral outbound socket with and without
   `protect(fd)`; check with the loopback fixture whether the source goes via `tun0`.
Risk to a device VPN session: high if run live (steps 2/4 break reconnects) — hence emulator only.

## 4. Recommendation

| Variant | Size | Risks | User sees |
|---|---|---|---|
| **A (recommended)** Keep TCP `LatencyProbe` when disconnected + via-proxy `urltest` when connected (already built); finish honesty/persistence. **No engine-assisted offline C-01.** | ~150–250 lines + tests | Low. TCP-OK/proxy-dead stays undetected while disconnected; say so. | Two clearly named measurements: "port reachable (TCP)" vs "via node (HEAD)", each with age. |
| B Engine probe only while VPN is off (in-process, serialised with engine lifecycle) | ~600–900 lines (probe config compile ~120, platform ~150, runner ~250, tests/PoC ~300) | Connect vs probe teardown race; native crash kills app; new cancel/timeout paths; needs steps 3 above first | Real node health before connecting; extra seconds before Connect may be blocked. |
| C Separate probe process | 800+ lines, manifest process, IPC | `protect` unproven *[hyp]*, memory ×2, P-05 scope creep | Same as B. |

Do A now; revisit B only after the PoC in §3 passes on the emulator; do not do C.

## 5. Follow-up tasks needed in any variant
- **C-02:** persist `lastLatencyMs`, `lastCheckedAt`, `method` (tcp/proxy) per node (Room 7→8 migration + test);
  "Test all" with a visible cancel button and `done/total` progress; stop in-flight probes on screen exit
  (disconnected path); keep the 8 permits (the "cap 3" rule only applies to an engine probe).
- **C-03:** ignore history older than a TTL in Home best-candidate (`HomeViewModel.kt:195`), show resolved
  leaf + age + method ("measured through the node, tolerance 50 ms"), state that libbox tolerance is the only
  hysteresis; tests for stale and for tolerance no-switch.
- **Docs:** name the two measurement kinds in Servers captions (already partly: `ServersViewModel.kt:94-96`).
