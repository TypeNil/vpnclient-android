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
  → SubscriptionParser*      → List<ProxyNode>  (each carries sing-box outboundJson)
  → Room: delete+insert nodes, markSuccess — only after full parse succeeds
```

A failed refresh never touches stored nodes (last-known-good).

### Auto-refresh (WorkManager)

- One unique `PeriodicWorkRequest` per subscription (`subscription-refresh-<id>`),
  `ExistingPeriodicWorkPolicy.UPDATE`, `NetworkType.CONNECTED`, exponential
  backoff; `Result.retry` only on transient errors (`Network`/`Timeout`),
  bounded at 3 attempts — permanent failures are already recorded by the
  repository's `lastError` metadata.
- Interval precedence: user override (Settings) > `profile-update-interval`
  header (hours → minutes) > manual-only. Floored at the 15-min platform
  minimum. `autoRefreshMinutes`: `-1` = off, `0` = provider, `>0` = override.
- Re-registration: after every successful refresh (provider hint may change),
  on `remove()` (cancel), and on app start — `VpnClientApp` collects
  `autoRefreshMinutes` and reconciles all jobs, so a settings change
  re-registers everything.
- The `SubscriptionRefreshScheduler` contract lives in `core.subscription`
  (JVM-testable); `WorkManagerRefreshScheduler`/`SubscriptionRefreshWorker`
  live in `data.work` and reach the repository via a Hilt `EntryPoint`.

## Config compilation

`NodeConfigProviderImpl.compileSelected()` → `ConfigCompiler.compile(nodes, selectedId, ipv6)`:

- every node → outbound tagged by node id
- `selector` group `proxy` (default = selected node) — runtime-switchable via
  `CommandClient.selectOutbound`
- `urltest` group `auto` over all nodes — `urlTest` latency measurement
- `tun` inbound (mtu 9000, `auto_route`, `stack: gvisor` — required on pinned
  libbox 1.14.1; IPv6 optional)
- `dns`: `local` (platform, via LocalDnsResolver) + `remote` (https://1.1.1.1
  with `detour: proxy` so DoH follows the selected node, not the direct path);
  route rule `hijack-dns` captures tunneled DNS; `default_domain_resolver: local`
  prevents the loop on outbound server names
- route: `sniff` → `hijack-dns` → private-IP bypass → `final: proxy`

Validated with `Libbox.checkConfig` in `ConnectionManager.connect()` **before**
requesting VPN permission.

## Connection lifecycle

`VpnConnectionState`: `Idle → Preparing → PermissionRequired? → Connecting →
Connected → (Reconnecting | Stopping | Error)`; `Idle` again after stop.

- `connect()`: compile config → `VpnService.prepare()` → (consent intent →
  UI launcher → `onPermissionResult`) → `startForegroundService`. Each attempt
  gets a monotonically increasing session generation; every service callback
  and collector emission is tagged with it and stale generations are dropped.
- `ClientVpnService` builds the engine, calls `start(config)`; libbox calls back
  `EnginePlatform.openTun` → `Builder.establish()` → fd. `onServiceStarted(gen)`
  flips state to `Connected`; stats may only mutate a `Connected` payload —
  telemetry never creates or resurrects lifecycle state.
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
- Network change: one `ConnectivityManager.NetworkCallback` (service-owned) →
  `setUnderlyingNetworks` + `ConnectionManager` (`Connected`↔`Reconnecting`)
  + coalesced `engine.onUnderlyingNetworkChanged()` → `commandServer.resetNetwork()`.
  The engine's `NetworkMonitor` additionally feeds libbox internals.

## Threading

- Engine: dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned
  by the service; cancelled on destroy.
- CommandServer calls are gRPC-blocking → wrapped in `withContext(Dispatchers.IO)`.
- Room/OkHttp: their own dispatchers. UI: `Main.immediate` flows only.

## Storage

- Room: `subscriptions` + `nodes` tables (nodes keyed by stable content hash id).
- DataStore preferences: selected node id, HWID, reconnect/IPv6 flags,
  `desiredVpnRunning`, `subscriptionRefreshMinutes`.
- Secrets stay in Room (`url`, `rawUri`, `outboundJson`) — local-only, never exported;
  `SecureLog` + `Redactor` scrub logs.

## Foreground service

`ClientVpnService` is `foregroundServiceType="systemExempted"` — the type Android
documents for VPN apps (mirrors sing-box-for-android); `BIND_VPN_SERVICE` +
non-exported. Persistent notification with Disconnect action.
