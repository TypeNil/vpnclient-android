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
`urlTest` on each group whose `type == "urltest"`** — calling it on
`selector` groups is at best a no-op/warning (unverified, see open list).

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
1. `testLatency` calls `urlTest` on every group including `selector`s —
   unverified whether libbox accepts that; cheap fix available
   (`type == "urltest"` filter) if it errors.
2. No completion signal: `urlTest` is fire-and-forget; results arrive on
   the next `writeGroups` push. UI "testing" spinner end-state is inferred,
   not signaled — acceptable if the push cadence is prompt (unverified).
3. `writeOutbounds` callback unmapped — not needed for Slice 4 scope.

## c) What R-0.4 did NOT cover — must be verified with live TUN

- **protect/underlay**: probe packets ride each node's own outbound
  sockets; whether those go through `protect()` correctly alongside a
  running TUN, and never loop back into it — unverified.
- **urlTest during an active session** on the real config (real nodes,
  real test URL from the compiled `urltest` group) — unverified.
- **User-traffic impact**: stats contamination, latency jitter on the live
  tunnel while probes run — unverified.
- **Cancellation**: disconnect/reconnect mid-probe — whether the command
  client is invalidated cleanly (engine has `invalidateClientLocked`), no
  zombie probes, no crash — unverified.
- **`selectOutbound` on a live session** — implemented with reconnect
  fallback, never device-smoked in this session.
- **`urlTest` on `selector` group** — throws vs no-op — unverified.
- **`writeGroups` latency after `urlTest`** — how fast delays reach UI —
  unverified.

## d) Minimal device smoke plan (b420945a, real subscription)

Manual, on the existing UI — no new code needed for the smoke itself:

1. Connect VPN with a real profile → Servers screen → ping button.
   - **GO**: every enabled node shows a delay (or timeout) within ~10s;
     tunnel stays `Connected`; user traffic still flows (open a page).
   - **NO-GO**: `urlTest` throws/hangs, `urlTestDelayMs` never arrives,
     or the tunnel drops/stalls during the probe.
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

Watching: `adb logcat -s SingBoxEngine ConnectionManager` for
`urlTest failed`/`selectOutbound failed` warnings during each step.

## Open questions for the follow-up implementation task

- Whether `urlTest` on selector groups errors — decide the `type` filter.
- Whether `setStatusUpdatesEnabled(false)` (screen off) racing a probe
  mid-flight can leave the VM's `_testing` stuck — read + device check.
- `urlTestDelay` vs disconnected `LatencyProbe` numbers are different
  metrics — UI must not show them interchangeably (already flagged in the
  gate doc).
