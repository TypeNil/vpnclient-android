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

## Config compilation

`NodeConfigProviderImpl.compileSelected()` → `ConfigCompiler.compile(nodes, selectedId, ipv6)`:

- every node → outbound tagged by node id
- `selector` group `proxy` (default = selected node) — runtime-switchable via
  `CommandClient.selectOutbound`
- `urltest` group `auto` over all nodes — `urlTest` latency measurement
- `tun` inbound (mtu 9000, `auto_route`, `stack: system`, IPv6 optional)
- `dns`: `local` (platform, via LocalDnsResolver) + `remote` (https://1.1.1.1);
  route rule `hijack-dns` captures tunneled DNS; `default_domain_resolver: local`
  prevents the loop on outbound server names
- route: `sniff` → `hijack-dns` → private-IP bypass → `final: proxy`

Validated with `Libbox.checkConfig` in `ConnectionManager.connect()` **before**
requesting VPN permission.

## Connection lifecycle

`VpnConnectionState`: `Idle → Preparing → PermissionRequired? → Connecting →
Connected → (Reconnecting | Stopping | Error)`; `Idle` again after stop.

- `connect()`: compile config → `checkConfig` → `VpnService.prepare()` →
  (consent intent → UI launcher → `onPermissionResult`) → `startForegroundService`.
- `ClientVpnService` builds the engine, calls `start(config)`; libbox calls back
  `EnginePlatform.openTun` → `Builder.establish()` → fd. `onServiceStarted()`
  flips state to `Connected`; stats flow updates counters.
- Disconnect: notification action / UI → `disconnect` intent → `engine.stop()` →
  `closeTun` → `stopSelf` → `onServiceStopped()` → `Idle`.
- `onRevoke` (settings "disconnect"/another VPN takes over) → `onServiceRevoked()`
  → `Error(PermissionRevoked)`.
- Network change: `ConnectivityManager.NetworkCallback` → `engine.onUnderlyingNetworkChanged()`
  → `commandServer.updateNetwork()` + `DefaultNetworkMonitor` notifies libbox.

## Threading

- Engine: dedicated `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned
  by the service; cancelled on destroy.
- CommandServer calls are gRPC-blocking → wrapped in `withContext(Dispatchers.IO)`.
- Room/OkHttp: their own dispatchers. UI: `Main.immediate` flows only.

## Storage

- Room: `subscriptions` + `nodes` tables (nodes keyed by stable content hash id).
- DataStore preferences: selected node id, HWID, reconnect/IPv6 flags.
- Secrets stay in Room (`url`, `rawUri`, `outboundJson`) — local-only, never exported;
  `SecureLog` + `Redactor` scrub logs.

## Foreground service

`ClientVpnService` is `foregroundServiceType="systemExempted"` — the type Android
documents for VPN apps (mirrors sing-box-for-android); `BIND_VPN_SERVICE` +
non-exported. Persistent notification with Disconnect action.
