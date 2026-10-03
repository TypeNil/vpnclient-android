# Architecture

## Layers

```
UI (Compose)            ← VpnConnectionState, node/subscription lists
  │
ConnectionManager       ← state machine + VpnService.prepare/launch + engine wiring
  │
ClientVpnService        ← VpnService, foreground, TUN fd, protect(), network callbacks
  │ implements EnginePlatform
VpnEngine (interface)   ← validate/start/stop/stats/events/groups/urlTest
  │
SingBoxEngine           ← libbox CommandServer + CommandClient (in-process gRPC)
  │
libbox.aar              ← sing-box core, pinned + SHA-256-verified
```

Key boundary: **no `libbox.*` type escapes `core.engine.singbox`**. The rest of
the app sees `VpnEngine`/`EngineConfig`/`TrafficStats`/`OutboundGroupInfo`.
Swapping cores means writing another `VpnEngine` + `EnginePlatform` consumer.

## Subscription pipeline

```
SubscriptionRepository.refresh(id)
  → SubscriptionFetcher      OkHttp; UA header; x-hwid; 8 MiB cap; metadata headers
  → SubscriptionClassifier   UriList | Base64UriList | SingBoxJson | ClashYaml | XrayJson(unsupported)
  → SubscriptionParser*      → ParseResult(nodes, skipped)  (each node carries sing-box outboundJson)
  → Room: delete+insert nodes, markSuccess — only after full parse succeeds
```

A failed refresh never touches stored nodes (last-known-good).

The stored `outboundJson` stays opaque outside `core.engine.singbox`, but
the Servers UI surfaces a read-only TLS posture via
`NodeTlsSummary.fromOutboundJson` (same package): the TLS authentication
category (none / certificate-based / Reality / unrecognized — a category,
not a claim that the CA chain was verified), the `insecure` flag, and
sanitized SNI/ALPN — never keys, short ids, ECH config, or raw JSON, and
never a claim about the live handshake. Wrong-typed or malformed `tls`
shapes report `UNKNOWN` rather than a false verified/insecure claim.

### Auto-refresh (WorkManager)

- One unique `PeriodicWorkRequest` per subscription (`subscription-refresh-<id>`),
  `ExistingPeriodicWorkPolicy.UPDATE`, `NetworkType.CONNECTED`, exponential
  backoff; `Result.retry` only on transient errors (`Network`/`Timeout`),
  bounded at 3 attempts — permanent failures are already recorded by the
  repository's `lastError` metadata.
- Per-subscription policy (Room v7 columns `refreshPolicy` +
  `refreshFixedMinutes`, decoded via `RefreshPolicy.fromStorage`): `inherit`
  follows the app-wide `autoRefreshMinutes` — the legacy semantics (`-1` =
  off, `0` = provider, `>0` = fixed override); `provider` follows only
  `profile-update-interval` (hours → minutes) — no usable hint means
  manual-only, never a global fallback; `disabled` suppresses the periodic
  job AND the `update-always` launch refresh (manual refresh still works);
  `fixed` uses its own minutes. Invalid stored values decode to `disabled`.
  Explicit `provider`/`fixed` are unaffected by the global off switch;
  resolved intervals floor at the 15-min platform minimum, and the UI
  rejects fixed input below it rather than clamping. A disabled
  subscription (`enabled = false`) and the manual sentinel never schedule.
- `SubscriptionRefreshWorker` calls `refreshPeriodic(id)`, not the
  unconditional `refresh(id)`: the run re-resolves the same interval
  function on the CURRENT row under the per-subscription lock before
  fetching — a job that outlived a policy flip to `disabled`/`enabled=false`
  or a global off reports `Superseded` (skipped, job kept) and a missing
  row reports `NotFound` (the worker cancels its own job). The gate re-runs
  inside the commit lock too, so a candidate fetched before a mid-flight
  `disabled` write is dropped instead of committed. Manual refresh stays
  unconditional end to end. The interval resolver
  (`resolveRefreshIntervalMinutes`) lives next to the
  `SubscriptionRefreshScheduler` contract in `core.subscription`, shared by
  the scheduler, the gate, and the UI preview — one semantics source.
- Re-registration: after every successful refresh (provider hint may
  change), on policy/enabled edits, on `remove()` (cancel), and on app
  start — `VpnClientApp` collects `autoRefreshMinutes`
  (`distinctUntilChanged`), reconciles every row until the startup pass
  reports complete, then only inheriting rows on later emissions (explicit
  policies can't observe the global value). Per-row/pruning failures make
  the pass incomplete; the next distinct global-interval emission retries
  the full startup pass, without a timer retry. All rescheduling goes through
  the repository's per-subscription lock and re-reads the row + settings, so
  a stale entity list or in-flight refresh can't overwrite a newer override.
  Orphan pruning queries work candidates before checking each current row's
  existence, rather than using the earlier subscription-list snapshot.
  Completion describes submission/query success, not acknowledgement of
  asynchronous WorkManager enqueue/cancel operations.
- The `SubscriptionRefreshScheduler` contract lives in `core.subscription`
  (JVM-testable); `WorkManagerRefreshScheduler`/`SubscriptionRefreshWorker`
  live in `data.work` and reach the repository via a Hilt `EntryPoint`.

## Config compilation

`NodeConfigProviderImpl.compileSelected()` → `ConfigCompiler.compile(nodes, selectedId, ipv6, routeMode)`:

- every node → outbound tagged by node id
- `selector` group `proxy` (default = selected node) — runtime-switchable via
  `CommandClient.selectOutbound`
- `urltest` group `auto` over all nodes — `urlTest` latency measurement;
  `idle_timeout: 20m` stops background probing when the group is idle
- `tun` inbound (mtu 9000, `auto_route`, `stack: gvisor` — required on pinned
  libbox 1.14.1; IPv6 optional)
- `dns`: `local` (platform, via LocalDnsResolver) + `remote` (https://1.1.1.1
  with `detour: proxy` so DoH follows the selected node, not the direct path);
  route rule `hijack-dns` captures tunneled DNS; `default_domain_resolver: local`
  prevents the loop on outbound server names (kept in every mode)
- route: `sniff` → `hijack-dns` → private-IP bypass → mode rule → `final`

`RouteMode` (DataStore, string key) shapes the tail of the rule chain and DNS.
Each mode's `ruleSetTags` is the single source of truth for both the store
and the compiler:

- `ALL` (default) — no rule sets, `final: proxy`.
- `BYPASS_RU` — rule sets `geoip-ru` + `geosite-category-ru` → `direct`;
  DNS maps `geosite-category-ru` → `local` with `strategy: ipv4_only` (a
  v6 RU dial is dead on IPv4-only underlays), `final` stays `remote`.
- `PROXY_BLOCKED` — curated `geosite-*` service list → `proxy`, `final:
  direct`; DNS maps the same list → `remote` (ISP answers are spoofed),
  `final` becomes `local`.

Rule sets are **local**, not remote: `RuleSetStore` keeps the `.srs` files
app-side in `filesDir/rule_sets` and the config references them by path — the
engine's start path never fetches over the network. Connect uses any valid
copy as-is, however old (missing → bundled baseline → bounded, cancellable
download as a last resort). Copies older than a day are refreshed in the
background once a tunnel is `Connected` (`refreshStale`, single-flight,
validated before an atomic replace, picked up by the next connect). A GitHub
hiccup can't fail or delay a connect; a stale copy still works
offline. When the active underlay lacks real IPv6 (global address + `::/0`
route), non-ALL modes also emit an `ip_version: 6 → proxy` rule so v6 rides
the tunnel instead of dead-ending in `direct`.

### Per-app routing

- `PerAppMode` (`ALL` / `INCLUDE` / `EXCLUDE`, string-keyed in DataStore) +
  a package set; picked via Settings → Per-app VPN (launcher apps only,
  exposed by the manifest `<queries>` MAIN+LAUNCHER declaration — no
  `QUERY_ALL_PACKAGES`).
- Applied at `openTun` time through `resolvePerAppPlan`: `VpnService.Builder`
  rejects mixing `addAllowed`/`addDisallowed` calls, so the plan fills exactly
  one side — include-mode wins when any allowed package exists. Our own
  package always rides the tunnel (it joins any allow-list, is never
  disallowed); engine core sockets stay off the TUN via `VpnService.protect()`,
  which only fires because the compiler emits `route.auto_detect_interface` —
  without it the core never calls `autoDetectInterfaceControl` and its own
  outbound sockets loop back into the TUN, killing the tunnel.
- `excludeRoute()` exists only on API 33+: below it the Builder can't honor
  route exclusions. The compiler emits no `route_exclude_address` today, so
  the lists are empty; if a future config produces them, `openTun` logs a
  warning on API 26–32 rather than silently diverging.
- The package lists are baked into the TUN fd, so a policy change can't be
  hot-swapped: while Connected, a debounced watcher in `ClientVpnService`
  restarts the engine inside the same session (stop → start → `openTun`
  re-runs with the new plan). The UI sees a brief `Reconnecting`; changes
  landing in other states are picked up by the next `openTun`.

Validated with `Libbox.checkConfig` in `ConnectionManager.connect()` **before**
requesting VPN permission.

## Connection lifecycle

`VpnConnectionState`: `Idle → Preparing → PermissionRequired? → Connecting →
Connected → (Reconnecting | Stopping | Error)`; `Idle` again after stop.

- `connect()`: publish `Preparing` (node not yet known) → compile config
  outside the manager mutex → under the mutex, commit only if the attempt is
  still current → `VpnService.prepare()` → (consent intent → UI launcher →
  `onPermissionResult`) → `startForegroundService`. `disconnect()` during
  `Preparing` cancels the compile at once (it never waits on the mutex) and
  returns to `Idle` without starting the service; Home and the quick-settings
  tile expose it as Cancel. Each attempt gets a monotonically increasing
  session generation; every service callback and collector emission is tagged
  with it and stale generations are dropped.
- `ClientVpnService` builds the engine, calls `start(config)`; libbox calls back
  `EnginePlatform.openTun` → `Builder.establish()` → fd. `onServiceStarted(gen)`
  flips state to `Connected`; stats may only mutate a `Connected` payload —
  telemetry never creates or resurrects lifecycle state.
- Live outbound control: `ConnectionManager.groups` mirrors the engine's
  outbound groups (generation-guarded, cleared on detach). The persisted
  `selectedNodeId` is the desired outbound: the manager reconciles it into
  the live engine on every selection change and every engine attach (a
  rebuilt tunnel starts on its compiled default, so the pick is re-applied).
  A successful `selectOutbound` updates the session node shown by the UI;
  when the engine can't honor it (control channel down, tag missing) the
  manager reconnects so the pick compiles in as the selector default.
- Latency: `urlTest` runs through each node's own outbound over the real
  underlay — engine sockets bypass the TUN via `VpnService.protect()`. The
  app's own package rides the tunnel in every per-app mode, so the
  disconnected-mode `LatencyProbe` (direct TCP connect to `server:port`)
  protects its sockets explicitly through `VpnSocketProtector` and never
  measures through the tunnel itself.
  Settings baked into the config at compile time (`routeMode`,
  `ipv6Enabled`) prompt a reconnect when changed on a live tunnel; per-app
  policy rebuilds the TUN in-session instead.
- Disconnect: notification action / UI → `disconnect` intent → `engine.stop()` →
  `closeTun` → `stopSelf` → `onServiceStopped(gen)` → `Idle`.
- Unexpected engine termination (`Failed` / `StoppedUnexpectedly`) is recorded,
  teardown converges through the service, and the error is published by
  `onServiceStopped(gen)` once TUN/collectors are cleaned. `stop()` for an
  app-requested disconnect never emits a terminal engine event.
- `onRevoke` (settings "disconnect"/another VPN takes over) → `onServiceRevoked()`
  → `Error(PermissionRevoked)`.
- Durability: `desiredVpnRunning` (DataStore) records user intent. `CONNECT` sets
  it and returns `START_STICKY`; `DISCONNECT`/`onRevoke`/start-failure clear it
  and return `NOT_STICKY`. A null-intent restart (process death) or system
  start rebuilds the config from Room/DataStore via `NodeConfigProvider` and
  adopts a fresh session generation — no `pendingSession` handoff needed.
- Socket protection: `autoDetectInterfaceControl` → `protect(fd)`. A `false`
  return means the core's outbound socket loops back into the TUN — the
  engine reports it once as `EngineEvent.Failed` and the bounded
  auto-reconnect rebuilds the session (fail-closed posture, WG Tunnel style).
- Network change: one `ConnectivityManager.NetworkCallback` (service-owned) →
  `setUnderlyingNetworks` + `ConnectionManager` (`Connected`↔`Reconnecting`)
  + coalesced `engine.onUnderlyingNetworkChanged()` → `commandServer.resetNetwork()`.
- Engine failure: `Failed`/`StoppedUnexpectedly` while a session is alive →
  `Reconnecting(node, "core failure", attempt)` + bounded auto-reconnect —
  up to 5 retries at 1/2/4/8/16 s, each waiting for the teardown to settle
  (`onServiceStopped` → `Idle`) before `startConnect`. A session stable for
  60 s resets the budget; a user connect/disconnect/revoke cancels it.
  Budget exhausted → the parked error converges through teardown to `Error`.
  Network callbacks never resume a failure `Reconnecting` (`teardownRequested`
  gate) — `Connected` over a dead engine would be a fake state.
- Screen off: a service-owned `ACTION_SCREEN_ON/OFF` receiver toggles
  `engine.setStatusUpdatesEnabled` — the command client disconnects, so the
  core stops pushing 1 Hz stats/groups nobody can see (SFA pattern).
  The engine's `NetworkMonitor` additionally feeds libbox internals; both
  callbacks request `NET_CAPABILITY_NOT_VPN` so the tunnel's own network can
  never be picked as its own underlay.
- Doze: a service-owned `ACTION_DEVICE_IDLE_MODE_CHANGED` receiver forwards
  `onDeviceIdle` to the engine (`commandServer.pause()`/`wake()`), but only
  when the opt-in `dozePowerSave` setting is on — `pause()` drops open TCP
  connections.
- Diagnostics: `LibboxRuntime.init` calls `promoteOOMDraft()` +
  `promotePowerReportDraft()` so platform bugreports carry core state.
- Release: R8 optimization is on for release builds (the AAR ships consumer
  keep rules for `go.**`/`io.nekohasekai.**`); the Room schema is exported to
  `app/schemas/` for migration history.

### Entry points

- **QS tile** (`VpnTileService`): mirrors `ConnectionManager.state`; tap on an
  active session disconnects directly, otherwise calls
  `ConnectionManager.connect()` itself and opens `MainActivity` — the consent
  dialog reaches the UI via the `prepareIntent` StateFlow. The exported
  activity never honors caller-supplied connect extras.
- **Boot receiver** (`BootReceiver`): `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` →
  restarts the tunnel only when `desiredVpnRunning` is set AND
  `VpnService.prepare` reports consent is still granted.
- **Import funnel** (`MainActivity` → `ImportUrlExtractor`): `sing-box://
  import-remote-profile?url=`, `clash://install-config?url=`,
  `clashmeta://install-config?url=`, and `text/plain` shares carrying a bare
  `http(s)` URL. The URL lands prefilled in the add-subscription dialog —
  nothing is imported without user confirmation.

## Threading

- Engine: dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned
  by the service; cancelled on destroy.
- CommandServer calls are gRPC-blocking → wrapped in `withContext(Dispatchers.IO)`.
- Room/OkHttp: their own dispatchers. UI: `Main.immediate` flows only.

## Storage

- Room: `subscriptions` + `nodes` tables (nodes keyed by stable content hash id).
- DataStore preferences: selected node id, HWID, reconnect/IPv6/doze flags,
  `desiredVpnRunning`, `subscriptionRefreshMinutes`, `perAppMode`,
  `perAppPackages`, restart-guard window/count/tripped flag.
- Secrets stay in Room (`url`, `rawUri`, `outboundJson`) — local-only, never exported;
  `SecureLog` + `Redactor` scrub logs.

## Foreground service

`ClientVpnService` is `foregroundServiceType="systemExempted"` — the type Android
documents for VPN apps (mirrors sing-box-for-android); `BIND_VPN_SERVICE` +
non-exported. Persistent notification with Disconnect action.
