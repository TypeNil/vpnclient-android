# Slice 4 spike — connected-mode latency via libbox `urlTest`

**Date:** 2026-10-02 · **Scope:** connected mode only · **Prod code:** unchanged

R-0.4 (`docs/R04-RESEARCH-GATE.md`) proved exactly one thing:
`CommandClient.urlTest(groupTag)` on pinned libbox 1.14.1 drives a
credential-free proxy-path URL measurement with no TUN, on loopback
fixtures, in an exclusive instrumentation process. It says nothing about a
live `ClientVpnService` session — that is what this spike scopes.

## a) Confirmed API surface (javap, pinned `core-native/libbox-1.14.1.aar`)

Evidence: `javap -cp core-native/extracted/classes.jar` on the listed
classes (AAR SHA256 verified earlier this session).

**`CommandClient`** — present, and exactly this:
- `urlTest(String groupTag)` → void — **there is NO `urlTestAll`** or any
  batch variant; "test all nodes" = `urlTest` per `urltest` group (the
  group measures all its members; one call per group is sufficient).
- `selectOutbound(String groupTag, String outboundTag)` → void/throws.
- `connect()`, `disconnect()`, `serviceClose()`, `serviceReload()`.

**Result delivery** — push, not return value:
- `CommandClientHandler.writeGroups(OutboundGroupIterator)` →
  `OutboundGroup{tag, type, selectable, selected, items}` →
  `OutboundGroupItem{tag, type, URLTestTime:long, URLTestDelay:int}`.
- `CommandClientHandler.writeOutbounds(OutboundGroupItemIterator)` exists
  too (currently unmapped in `SingBoxEngine`).
- Subscription opted via `CommandClientOptions.addCommand(int)`:
  `CommandGroup=2`, `CommandStatus=1`, `CommandOutbounds=5`,
  `CommandConnections=4`, `ClashMode=3`, `Log=0`.
- `Libbox.newCommandClient(handler, options)` — in-process factory.

So the reviewer's question is settled: **no `urlTestAll`; use
`urlTest` on each group whose `type == "urltest"` only** — a ping run
covers exactly the members of `urltest` groups; nodes outside them
keep `—` (filter required, see gap 1).

**Measured on device** (b420945a, instrumented run 2026-10-02,
`call=p` lines in `OfflineUrlTestResearchTest`/`R04Research` tag):
`urlTest` returns in **7–8 ms** across p2/p3/p4 calls — it is async
dispatch, not a blocking probe (compare `checkConfig` 127 ms and
`startOrReloadService` 124 ms, which do round-trip native work).
Results arrive on the next `writeGroups` push — on the loopback fixture
the push landed ~200 ms after the call; real nodes take real HTTP probe
time.

## b) Fit onto existing contract — mostly already built

Already present and wired (read, not assumed):

- `VpnEngine.urlTest(groupTag)`, `selectOutbound(group,tag):Boolean`,
  `groups: StateFlow<List<OutboundGroupInfo>>` where
  `OutboundGroupInfo{tag,type,selectable,selected,items}` and
  `OutboundItemInfo{tag,type,urlTestDelayMs}` — `type` is exposed, so
  filtering `type == "urltest"` is contract-ready.
- `SingBoxEngine`: serialized command-client lifecycle (`clientMutex` +
  `clientEpoch`, bounded reconnect, screen-off suppression), `writeGroups`
  → `urlTestDelayMs.takeIf{>0}` mapping, `selectOutbound`/`urlTest`
  delegates (call sites ~367/~375).
- `ConnectionManager.urlTest` pass-through (`:806`) and
  `applyDesiredSelection` → `resolveSelectionTarget` → `selectOutbound`
  with reconnect fallback (`:660-720`) — **live node switching already
  implemented**, not a roadmap gap.
- `ServersViewModel.testLatency()` (`:483+`): connected →
  `groups.forEach { connectionManager.urlTest(it.tag) }` + `urlTested`
  coverage tracking; disconnected → `LatencyProbe` TCP connect.
  Ping button wired (`ServersScreen:165`).

**Gaps found:**
1. `testLatency` calls `urlTest` on every group including `selector`s.
   Whether libbox accepts that is still unverified, but the
   `type == "urltest"` filter is **required** regardless: run coverage
   is defined as the members of `urltest` groups only, and nodes
   outside them are untouched and keep `—`.
2. No completion signal: `urlTest` is fire-and-forget (measured ~8 ms,
   see a); results arrive on the next `writeGroups` push. UI "testing"
   spinner end-state is inferred, not signaled — push cadence verified
   prompt on device (delays on the push right after each probe batch).
   **Completion spec for the follow-up (corrected 2026-10-02 — the
   earlier `urlTestTime >= runStart` rule was wrong: libbox reports
   whole seconds, so app-clock comparison is a granularity trap).**
   "Covered" = members of `type == "urltest"` groups only — nodes
   outside them keep `—`. Capture a per-tag baseline `urlTestTime` for
   every covered member *before* dispatching `urlTest`. A member is
   then terminal when:
   - `urlTestDelay > 0 && urlTestTime > baseline[tag]` — fresh success,
     strictly newer than the pre-run value (equality is ambiguous at
     second granularity, so `>` not `>=`); or
   - `baseline[tag] > 0 && urlTestTime == 0` — the core cleared a
     previously recorded result = probe failed (clearing proven by
     phase-3).
   `baseline == 0 && urlTestTime == 0` is ambiguous — never-measured and
   failed-without-mark look identical — so it waits the full bound
   (~15 s, the native probe horizon), including the `127.0.0.1:19080`
   dead fixture. Never compare `urlTestTime` to `runStart` or any app
   clock. Members still non-terminal at the bound are marked `timeout`;
   terminal results collected along the way are retained.
   **Cancellation is terminal-cancel, not terminal-timeout**: any state
   other than `Connected` (incl. `Reconnecting`/`Error`/`Disconnected`),
   ViewModel clear, or status-updates disabled (screen off) ends the run
   immediately. Covered tags that received no new result revert to
   untested (`—`); results that did arrive stay. Observed on the smoke:
   ping → disconnect → every node rendered "timeout" — a cancelled probe
   presented as a measured failure (violates no-fake-state).
   `testLatency` adds covered tags to both `tested` and `urlTested`, so
   the disconnected surface inherits the marks (`ServersViewModel:495-501`).
3. `writeOutbounds` callback unmapped — not needed for Slice 4 scope.

## c) What R-0.4 did NOT cover — live-TUN smoke verdicts

Smoked 2026-10-02 on b420945a, real subscription, `.pi/r04/smoke-r04.log`
(gitignored evidence path; tags `SingBoxEngine ConnectionManager LibboxRuntime`).

- **protect/underlay**: **verified working.** Zero
  `protect() failed` lines across the whole run; probe sockets log
  `dial wlan0` — bound to the underlay, never the TUN.
- **urlTest during an active session**: **verified.** Repeated probe
  batches through every `urltest` member (socks/vless/trojan outbounds
  to `www.gstatic.com:443`); real delays reported (~150–380 ms); one
  dead member correctly `unavailable`. Tunnel stayed `Connected`, user
  traffic flowed through it the whole time.
- **User-traffic impact**: **no stall observed** — tunnel never left
  `Connected` during probes; app traffic (DNS + conns) interleaved
  normally. Counter-level contamination check not readable from logcat
  (stats aren't logged); Home-stats jump check deferred to UI pass.
- **Cancellation**: **verified clean at the engine level.** Disconnect
  ~570 ms after a probe batch: all three client streams
  (groups/status/connections) end `context canceled`; reconnect in
  ~170 ms; probes work again; no crash, no zombie state.
  **UI gap observed**: the cancelled run's nodes all render "timeout"
  post-disconnect — no completion signal means cancel is
  indistinguishable from failure today. After reconnect, numbers appear
  without a re-ping: the fresh `urltest` group self-measures on start
  and pushes groups.
- **`selectOutbound` on a live session**: inconclusive — the success
  path logs nothing, so live switching is not proven by this log.
  (The follow-up adds one redacted success-path line to make it
  observable.) `Connected -> Connected` re-emits are `updateSessionNode`
  label refreshes after `writeGroups` pushes while Auto was selected.
- **`urlTest` on `selector` group**: still unverified — smoke exercised
  only the `urltest` group; `testLatency` still calls all groups.
- **`writeGroups` latency after `urlTest`**: effectively verified —
  delays land on the push right after each batch (~sub-second at
  real-node probe time); UI end-state still inferred, not signaled.

## d) Minimal device smoke plan (b420945a, real subscription)

Manual, on the existing UI — no new code needed for the smoke itself:

1. Connect VPN with a real profile → Servers screen → ping button.
   - **GO**: every enabled node shows a delay (or timeout) within ~10s;
     tunnel stays `Connected`; user traffic still flows (open a page).
   - **NO-GO**: `urlTest` throws/hangs, `urlTestDelayMs` never arrives,
     or the tunnel drops/stalls during the probe.
   - **protect() evidence** (logcat, `SingBoxEngine` tag): absence of
     `protect() failed — outbound socket not excluded from VPN`.
     Path: `route.auto_detect_interface=true` (ConfigCompiler:320) →
     `autoDetectInterfaceControl(fd)` → `platform.protectSocket` →
     `VpnService.protect`; a false return emits that line once and raises
     a fatal `EngineEvent.Failed`, so its absence = every probe socket
     was excluded from the TUN.
   - **No TUN loopback** (Home stats or `writeStatus`): during a ping on
     an otherwise idle session, `uplinkTotalBytes`/`downlinkTotalBytes`
     should not climb by probe-sized jumps, and `connectionsIn`/`Out`
     should not show the probe's loopback connections. Probe traffic
     riding protected sockets stays off the tunnel counters.
2. While connected, select a different node.
   - **GO**: session label switches without a full reconnect (logcat:
     `selectOutbound` success, no `reconnect()`).
   - **NO-GO**: falls back to reconnect every time (control channel dead).
3. Hit ping, then disconnect mid-probe; reconnect and repeat.
   - **GO**: no crash, no stuck "testing" spinner, next connect's probe
     works.
   - **NO-GO**: service crash, engine deadlock, or stale delays presented
     as fresh.
4. Select **Auto** and ping.
   - **GO**: the `urltest` group picks the lowest-delay member and routes
     via it.
   - **NO-GO**: selection ignores measurement or routes to a dead node.

Watching: collect with
`adb -s b420945a logcat -c && adb -s b420945a logcat -v time -s SingBoxEngine ConnectionManager LibboxRuntime > .pi/r04/smoke-r04.log`
— look for `urlTest failed`/`selectOutbound failed` warnings and the
protect line above during each step.

## Open questions for the follow-up implementation task

- Whether `urlTest` on selector groups errors — smoke exercised only
  the `urltest` group. The `type` filter is required regardless
  (coverage = `urltest` members only); this only decides whether
  unfiltered calls were additionally harmless.
- Whether `setStatusUpdatesEnabled(false)` (screen off) racing a probe
  mid-flight can leave the VM's `_testing` stuck — read + device check.
- `urlTestDelay` vs disconnected `LatencyProbe` numbers are different
  metrics — UI must not show them interchangeably (already flagged in the
  gate doc).
