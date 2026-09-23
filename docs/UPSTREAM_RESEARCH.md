# Upstream research — verified findings, defects, and adoption plan

Research-only document. **No code was changed.** Every claim below is either
(a) read from upstream source at a named path, (b) quoted from official
documentation, or (c) verified against the pinned `libbox 1.14.1` AAR with
`javap`. Anything unverified is marked `[UNVERIFIED]`.

Date: 2026-09-19. Sources studied at source level: sing-box-for-android (SFA),
ClashMetaForAndroid (CMFA), FlClash, FlClashX, v2rayNG, husi, NekoBox,
Exclave, Tailscale Android, WireGuard Android, OpenVPN for Android, plus
sing-box / Android platform documentation.

---

## 0. Method

| Source | How it was read |
|---|---|
| Upstream repos | `raw.githubusercontent.com` file reads + GitHub code search |
| sing-box schema | `sing-box.sagernet.org/configuration/*` and `/deprecated/` |
| Android platform | `developer.android.com` reference + guides |
| Our pinned core | `javap` over `core-native/extracted/classes.jar` (libbox 1.14.1) |
| Our code | full read of `core/**`, `data/**`, `ui/**`, manifest, build files |

---

## 1. Verdict on our current state

The **platform plumbing is at upstream parity or better**:

- `openTun` / `autoDetectInterfaceControl` / `NetworkMonitor` / `LocalDnsResolver`
  mirror SFA's `PlatformInterfaceWrapper` / `DefaultNetworkMonitor` / `LocalResolver`.
- The single-process `StateFlow` state machine is strictly cleaner than v2rayNG's
  broadcast IPC (`MessageUtil` `MSG_*` ints) or CMFA/NekoBox's AIDL layer.
- The in-process `CommandServer` over a unix socket is a stronger security posture
  than NekoBox's `127.0.0.1:9090` + random secret or CMFA's loopback
  `external-controller`.
- Room + DataStore beats v2rayNG's MMKV (plaintext credentials, stringly keys).
- `checkConfig` before `VpnService.prepare()` is better than what most clients do.

The gaps are **lifecycle correctness, config-schema currency, and missing
features** — not core integration. Details below.

---

## 2. Corrections to earlier research notes

These were in the previous revision of this document and are wrong:

1. **FlClashX has no `HealthCheckModule`.** Neither FlClash nor FlClashX runs a
   client-side watchdog that pings the core and restarts it. Health checks are
   delegated to the Mihomo core's own `url-test` groups; a core crash emits
   `onCrash` (`lib/manager/core_manager.dart`) and FlClash deliberately goes to
   `CoreStatus.disconnected` **without** auto-restarting. Flapping prevention is
   a coalesced revision worker (`_requestedRestartRevision` in
   `lib/providers/actions/core.dart`), not a health probe.
2. **CMFA has no `QuickTileStartActivity`.** `app/src/main/java/com/github/kr328/clash/TileService.kt`
   calls `startClashService()` and *discards* the `VpnService.prepare()` consent
   intent — consent from the tile silently fails unless it was granted earlier
   from the main UI. The trampoline pattern exists in **WireGuard**
   (`ui/src/main/java/com/wireguard/android/activity/TunnelToggleActivity.kt`)
   and **Tailscale** (`QuickToggleService.java`), not CMFA.
3. **`AppListCacheModule` / `TimeZoneModule` are Clash-for-Android (`kr328`)
   modules, not FlClash modules.** FlClash resolves packages on demand in
   `android/app/src/main/kotlin/com/follow/clash/packages/PackageResolver.kt`.
4. **FlClash and FlClashX are Flutter, not Compose.** Any "Compose UI pattern"
   attributed to them is a translation, not a copy.
5. **SFA does not use `START_STICKY`.** `BoxService.onStartCommand()` returns
   `START_NOT_STICKY`; recovery relies on always-on VPN plus
   `Settings.startedByUser`. v2rayNG *does* use `START_STICKY`.

---

## 3. Confirmed defects in our code

Ordered by severity. Each has a primary-source citation.

### 3.1 DNS leak — `remote` DoH server has no `detour` (critical)

`ConfigCompiler.build()` emits:

```json
{ "type": "https", "tag": "remote", "server": "1.1.1.1" }
```

sing-box docs, *DNS over HTTPS (DoH)*, "Difference from legacy HTTPS server":

> The old server uses default outbound by default unless detour is specified;
> **the new one uses dialer just like outbound, which is equivalent to using an
> empty direct outbound by default.**

Consequences: the DoH connection to `1.1.1.1` is dialed **directly on the
underlying network**, not through the selected proxy. The local network/ISP sees
a DoH flow to a fixed resolver; if `1.1.1.1` is blocked locally, all name
resolution fails even though the tunnel is up. This contradicts
`docs/ARCHITECTURE.md`, which claims DNS is tunneled.

Fix: `"detour": "proxy"` on the `remote` server. (`local` needs no detour —
the `local` DNS type resolves via `localDNSTransport()`, not an outbound.)

### 3.2 `addAllowedApplication` + `addDisallowedApplication` conflict (critical, latent)

`ClientVpnService.openTun()` runs the include/exclude loops and then
**unconditionally** calls `builder.addDisallowedApplication(packageName)`.

Android reference, `VpnService.Builder.addDisallowedApplication`:

> A `Builder` may have only a set of allowed applications OR a set of disallowed
> ones, but not both. Calling this method after `addAllowedApplication(String)`
> has already been called, or vice versa, will throw an
> `UnsupportedOperationException`.

So the first time a non-empty include list is supplied, `openTun` throws and the
engine fails to start. Latent today only because no UI writes those lists yet.

Correct semantics (SFA `bg/BoxService.kt`, CMFA `service/TunService.kt`,
Tailscale `IPNService.kt`):

- include mode → `addAllowedApplication` for `selected + ownPackage`
- exclude mode → `addDisallowedApplication` for `selected - ownPackage`
- never both; never disallow self in exclude mode

### 3.3 Per-app lists are invisible to the package manager on API 30+ (high)

`addAllowedApplication` requires the package to be resolvable. We declare
neither `<queries>` nor `QUERY_ALL_PACKAGES` in the manifest, so on modern
targetSdk a picker-driven list would resolve almost nothing and every entry would
hit the `NameNotFoundException` catch — silently producing an empty list.
CMFA declares `QUERY_ALL_PACKAGES`; FlClash filters by `GET_PERMISSIONS`.

### 3.4 Service cannot rebuild itself after process death (high)

`ClientVpnService.onStartCommand()` returns `START_NOT_STICKY`, and
`startTunnel()` fails with `NoNodeSelected` when `ConnectionManager.pendingConfig`
is null — which it always is in a fresh process. The desired state is not
persisted anywhere.

Upstream models:

- SFA `bg/BoxService.kt` — reads `Settings.selectedProfile` **inside** the
  service, sets `Settings.startedByUser`, and does not depend on the UI process.
- v2rayNG `service/CoreVpnService.kt` — `START_STICKY`, then
  `MmkvManager.getSelectServer()` to rebuild the core with no UI.
- Tailscale `IPNService.kt` — on a null intent reads a raw boolean from
  **unencrypted** SharedPreferences (fast, no Keystore) and calls
  `showForegroundNotification()` immediately.
- OpenVPN `core/OpenVPNService.java` — null intent → `getLastConnectedProfile()`
  then `getAlwaysOnVPN()`, always `START_STICKY`.

Android guide, *Always-on VPN → Detect always-on*:

> Android doesn't include APIs to confirm whether the system started your VPN
> service. But, when your app flags any service instances it starts, you can
> assume that the system started unflagged services for always-on VPN.

Our `ACTION_CONNECT` extra is already that flag; the missing half is the
persisted desired-state and the storage-driven rebuild.

### 3.5 `Reconnecting` is unreachable (high)

`VpnConnectionState.Reconnecting` exists and is rendered by `HomeScreen` and
`ConnectionVisual`, but no code path ever produces it. The brief's Phase 4 exit
criterion ("normal lifecycle transitions do not leave the UI lying about actual
VPN state") is not met.

### 3.6 `commandClient.connect()` failure is swallowed permanently (high)

`SingBoxEngine.connectClient()` launches a coroutine, `runCatching { client.connect() }`,
logs on failure, and never retries. If the connect loses the race with
`CommandServer.start()`, the session runs with **no stats and no groups** for its
whole lifetime while the UI shows "Connected".

SFA `utils/CommandClient.kt` uses an epoch counter and re-dials on UI/screen
lifecycle events; CMFA's unbounded `while(true)` DeadObjectException loop is the
anti-pattern to avoid. Bounded exponential backoff is the right middle.

### 3.7 Deprecated / dead config fields (medium)

Verified against `sing-box.sagernet.org`:

| Field we emit | Status | Source |
|---|---|---|
| `inbounds[].endpoint_independent_nat` | "has had no effect since sing-box 1.11.0 and can be removed" | TUN inbound page |
| `dns.independent_cache` | deprecated 1.14.0, **removed in 1.16.0** | `/deprecated/` |
| `inbounds[].stack: "gvisor"` | deprecated 1.15.0, **removed in 1.17.0** | `/deprecated/` |

We pin 1.14.1, so all three work today; the next core bump breaks the last two.
Also note `docs/ARCHITECTURE.md` says `stack: system` while the code says
`gvisor` — documentation drift.

### 3.8 Engine event stream cannot distinguish failure from stop (medium)

`SingBoxEngine.stop()` emits `EngineEvent.StoppedByCore` — but that is a
*user-initiated* stop. `EngineEvent.Failed` is declared and never emitted. So a
consumer cannot tell "the core died" from "we asked it to stop".

### 3.9 Core crash/OOM/power reporting is discarded (medium)

- `Libbox.promoteOOMDraft()` and `Libbox.promotePowerReportDraft()` are never
  called. SFA calls them immediately **before** constructing the `CommandServer`
  (`bg/BoxService.kt`), which is what turns a draft left by a crashed run into a
  readable report.
- `SetupOptions.appVersion` / `appMarketingVersion` are unset in
  `LibboxRuntime.init()` — both exist in the pinned AAR and feed crash reports.
- `EngineNotificationSink.send()` only writes a log line. SFA renders core
  notifications as real notifications (`sendNotification` in `bg/BoxService.kt`).

### 3.10 Notification is static and unthrottled-by-design (medium)

`VpnNotification.build()` is called once at start; the 1 Hz `TrafficStats` flow
never reaches it. No `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`
(API 31+), which the platform uses to skip the ~10 s deferred-display delay.

### 3.11 `allowBypass()` is unconditional (medium)

SFA gates it behind `Settings.allowBypass`. `allowBypass()` lets any app escape
the tunnel via `bindProcessToNetwork` / `Network.bindSocket` — a leak vector for
a product whose value proposition is "traffic goes through the tunnel".

### 3.12 `OverrideOptions` is passed empty (medium)

`SingBoxEngine.start()` calls `startOrReloadService(config, OverrideOptions())`.
The pinned AAR's `OverrideOptions` carries `includePackage` / `excludePackage`
(and `autoRedirect`). SFA passes the per-app lists there, which:
(a) feeds the core's own `route.find_process` package rules, and
(b) gives a **reload** path (`startOrReloadService` re-invokes `openTun`) instead
of CMFA's stop → poll → start restart loop.

### 3.13 Smaller items

- `StatusMessage.trafficAvailable` is ignored — libbox tells us when counters are
  meaningful.
- `OutboundGroupItem.urlTestTime` is ignored (only `urlTestDelay` is kept).
- `OutboundGroup.isExpand` is ignored; SFA persists it via `setGroupExpand`.
- `readWIFIState()` returns null and `CommandServer.needWIFIState()` is never
  checked. Fine today; a config with SSID rules would silently get no Wi-Fi state.
- `mtu 9000` on the TUN device. SFA does the same, but WireGuard defaults to 1280
  (IPv6 minimum link MTU) specifically to avoid fragmentation across cellular.
  Worth measuring rather than assuming.
- `NetworkMonitor` uses `registerDefaultNetworkCallback` and filters VPN
  transport after the fact. Tailscale registers a `NetworkRequest` with an
  explicit `NET_CAPABILITY_NOT_VPN` requirement
  (`NetworkChangeCallback.kt`), which cannot self-select the tunnel at all.

---

## 4. Patterns worth adopting

### 4.1 Lifecycle

| Pattern | Upstream evidence | Why |
|---|---|---|
| Persisted desired-state + storage-driven service rebuild | SFA `BoxService.kt` (`startedByUser`, `selectedProfile`); v2rayNG `CoreVpnService.kt`; Tailscale `IPNService.kt` | Survives process death, always-on VPN, and sticky restart |
| `startForeground()` synchronously before any async work | OpenVPN `OpenVPNService.java`; Tailscale `IPNService.kt`; CMFA `StaticNotificationModule.notifyLoadingNotification()` | The only bulletproof defence against `ForegroundServiceDidNotStartInTimeException` |
| Bounded reconnect with backoff | FlClash `_requestedRestartRevision` coalescing; CMFA's unbounded loop is the counter-example | Retries without flapping the radio |
| Doze pause/wake serialized through a channel | SFA `BoxService.kt` (`idleModeUpdates: Channel<Boolean>` → `commandServer.pause()/wake()`) | Avoids pause/wake races on rapid idle transitions |
| Module runtime with LIFO teardown | FlClash `service/modules/ServiceModules.kt`; CMFA `service/clash/module/Module.kt` | Individually testable watchers, ordered teardown, fault-tolerant rollback |
| Boot-loop guard before auto-start | SFA `bg/BootReceiver.kt` (checks unread crash reports, clears `startedByUser`) | Prevents a crash-on-start app from boot-looping the device |

### 4.2 Notification

- **Screen-off suppression.** Two equivalent upstream designs: SFA
  `bg/ServiceNotification.kt` *disconnects the status `CommandClient`* on
  `ACTION_SCREEN_OFF` and reconnects on `ACTION_SCREEN_ON` (stops the polling at
  the source); FlClash `NotificationModule.kt` combines a 1 s ticker with a
  screen flow and `.filterNotNull().distinctUntilChanged()` on a data class.
  v2rayNG `NotificationManager.kt` additionally suppresses consecutive
  zero-speed reposts.
- **Content diffing.** FlClash's `ExtendedNotificationParams` is a `data class`
  piped through `distinctUntilChanged()` — structural equality means
  `NotificationManager` is only called when something actually changed.
- **Rate limit.** The platform drops updates posted faster than ~1/s; 1–2 s is
  the safe tick.
- **Current outbound.** SFA walks the selector chain
  (`GroupsViewModel` / `Groups.kt`): start at the root group's `selected` tag,
  and while a group with that tag exists, follow its `selected`, until a leaf
  outbound is reached. Needed because `selector → urltest → node` is normal.

### 4.3 Per-app VPN

- Own-package rule (SFA `BoxService.kt`, CMFA `TunService.kt`, NekoBox
  `VpnService.kt`): include mode **adds** self; exclude mode **removes** self.
  Never `addDisallowedApplication(self)` in include mode.
- App list: `PackageManager.getInstalledPackages(GET_PERMISSIONS)`, filter out
  self and `"android"`, require `INTERNET` permission or `uid < FIRST_APPLICATION_UID`
  (CMFA `AccessControlActivity.kt`), cache in memory, invalidate on
  `ACTION_PACKAGE_ADDED`/`REMOVED` (CMFA `AppListCacheModule.kt`, 10 s debounce).
- Icons: v2rayNG deliberately ships `icon = null` in `AppListItem` to avoid
  binder churn and bitmap OOM; FlClash loads them on demand
  (`plugins/AppPlugin.kt`). Lazy or absent is the upstream consensus.
- Sorting: selected/pinned first, then name or last-update
  (FlClash `lib/models/common.dart`).
- Applying changes while running: the OS cannot mutate allowed UIDs on an open
  TUN fd. FlClash prompts "restart VPN"; CMFA stops, polls `while (clashRunning) delay(200)`,
  restarts. **We can do better** — `startOrReloadService` with new
  `OverrideOptions` re-establishes the TUN without tearing down the service.

### 4.4 Quick Settings tile, boot, external actions

- Tile + consent: WireGuard `QuickTileService.kt` launches
  `TunnelToggleActivity` when `VpnService.prepare()` returns non-null, then calls
  `TileService.requestListeningState()`; on API 34+ use
  `startActivityAndCollapse(PendingIntent)`. Tailscale `QuickToggleService.java`
  does the same with a lock-guarded static tile reference.
- Boot: `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` (OpenVPN `OnBootReceiver.java`).
  Avoid `LOCKED_BOOT_COMPLETED` — credential storage is not available before
  first unlock.
- External actions: CMFA exposes `START_CLASH` / `STOP_CLASH` / `TOGGLE_CLASH`
  via an exported `ExternalControlActivity`, and `clash://install-config` deep
  links. Tailscale routes external intents through a WorkManager-backed
  `IPNReceiver.java`.

### 4.5 Subscriptions

- **Auto-update**: v2rayNG `handler/SubscriptionUpdater.kt` — one
  `PeriodicWorkRequest` per subscription, unique name `sub_<id>`,
  `initialDelay = max(0, lastUpdated + interval - now)` (5 s floor on replace),
  `NetworkType.CONNECTED`, and a `sync()/syncOne()/cancelOne()` facade
  re-registered after every profile mutation. FlClash's in-memory Dart
  `Timer(20 min)` is the counter-example: it dies with the process.
- **Interval precedence** (CMFA `ProfileProcessor.kt`): user-set interval wins;
  the `profile-update-interval` header is adopted only on first import when the
  user has not customized; enforce a floor (CMFA uses 15 min).
- **Atomic commit**: FlClash `lib/models/profile.dart` writes to a temp file,
  validates via the core, and only then copies over the live file. Our
  `NodeDao.replaceForSubscription` is the DB-level equivalent and is already
  correct.
- **Tolerance**: husi `group/RawUpdater.kt` catches per-line/per-outbound
  failures and only fails when zero proxies survive — matching our behaviour.

### 4.6 Share-link normalization (husi `fmt/**`)

Concrete rules worth copying:

| Rule | Upstream |
|---|---|
| `allowInsecure` / `allow_insecure` / `insecure` unified, values `1`/`true`/`yes` | `ktx/Nets.kt` `parseBoolean` |
| `sni` → `peer` → `host` fallback chain | `TrojanFmt.kt`, `V2RayFmt.kt` |
| VMess JSON: `port`/`aid` as `JsonPrimitive` (accepts int **and** quoted string) | `V2RayFmt.kt` `V2rayNVMessShare` |
| VMess: three forms — base64 JSON, DuckSoft URI, Quantumult CSV | `V2RayFmt.kt` `parseV2Ray` |
| SS: split userinfo on `lastIndexOf('@')` (passwords may contain `@`) | `ShadowsocksFmt.kt` |
| SS: SIP022 (`2022-*`) plaintext userinfo vs SIP002 base64 userinfo | `ShadowsocksFmt.kt` |
| SS method aliases: `plain`/`dummy`→`none`, `chacha20-poly1305`→`chacha20-ietf-poly1305` | `ShadowsocksFmt.kt` |
| SS plugin alias `simple-obfs` ↔ `obfs-local` | `ShadowsocksFmt.kt` |
| hy2: `mport` multi-port, `obfs` + `obfs-password`, keep `pinSHA256` for round-trip | `HysteriaFmt.kt` |
| WS: extract `?ed=`/`?eh=` from the path, **strip them from the path**, set `max_early_data` | `V2RayFmt.kt` |
| `type=h2` → `http`; `type=tcp` → unset | `StandardV2RayBean.initializeDefaultValues()` |
| ECH PEM ↔ one-line base64 round-trip | `AbstractFmt.kt` |
| IPv6 authority `[::1]:443` and missing-port defaults | `Nets.kt` |

### 4.7 Panels

- Remnawave HWID: `x-hwid` must match `^[a-zA-Z0-9=-]{10,64}$`; response headers
  `x-hwid-active`, `x-hwid-not-supported`, `x-hwid-max-devices-reached`,
  `x-hwid-limit`. **HTTP 404 is returned both for "unknown subscription" and for
  "HWID missing / device limit reached"** — disambiguation must use the headers.
  Our `SubscriptionFetcher.mapHttpError` already does this; it could be widened
  to the full header set.
- Format selection: UA containing `sing-box` → sing-box JSON; `Clash`/`Mihomo` →
  Clash YAML; otherwise base64 URI list. Path suffixes override UA
  (`/sing-box`, `/clash`, `/mihomo`, `?flag=sing-box`). Marzban and 3x-ui follow
  the same shape (`/sub/{token}/sing-box`, `/sub/json/{id}`, `/sub/clash/{id}`).

### 4.8 Features the pinned core already supports

Verified with `javap` against `libbox 1.14.1`:

- `CommandServer.pause()` / `wake()` / `resetNetwork()` / `needWIFIState()` /
  `needFindProcess()` / `setError(String)` / `writeMessage(int, String)`
- `CommandClient.urlTest(group)`, `selectOutbound(group, tag)`,
  `closeConnections()`, `closeConnection(id)`, `getStartedAt()`, `getAPIVersion()`,
  `triggerOOMReport()`
- `Libbox.promoteOOMDraft()`, `promotePowerReportDraft()`, `formatBytes`,
  `formatBitrate`, `formatMemoryBytes`, `formatDuration`,
  `generateRemoteProfileImportLink`, `parseRemoteProfileImportLink`,
  `readAndroidVPNType`, `newStandaloneCommandClient`
- `Connections` + `ConnectionEvent` + `Connection.processInfo` (`ProcessInfo`
  with `userID`, `userName`, `processPath`, `packageNames()`) → a full
  connections screen with per-app attribution
- `OverrideOptions.includePackage` / `excludePackage` / `autoRedirect`
- `SetupOptions.oomKillerEnabled` / `oomMemoryLimit` / `powerReportEnabled` /
  `appVersion` / `appMarketingVersion` / `commandServerSecret`
- `TunOptions.isHTTPProxyEnabled` / `httpProxyServer` / `httpProxyServerPort` /
  `httpProxyBypassDomain` → the `setHttpProxy` path

**Not** in 1.14.1: `Libbox.discardPowerReportDraft()` (SFA's `dev` branch uses
it; our pinned AAR has only `promotePowerReportDraft()`). Any SFA code copied
verbatim must be checked against the pinned AAR first.

---

## 5. Anti-patterns to avoid

| Anti-pattern | Where | Why not |
|---|---|---|
| Multi-process `:bg` + AIDL | CMFA, NekoBox, FlClash | Binder death recovery, `Resource<T>` suspension, sync-state bugs; crash isolation is the only real benefit |
| Broadcast-int IPC | v2rayNG `MessageUtil` | Query-after-message reconstruction; `StateFlow` is strictly better |
| Unbounded retry loop | CMFA `withClash` DeadObjectException loop | Flaps the radio, drains battery |
| `runBlocking` on the main thread | SFA `VPNService.onRevoke()` | Works only because `onRevoke` arrives off-main; fragile |
| `os.Exit(0)` teardown | husi `service_stuck_android.go` | Valid in a `:bg` process; would kill our UI |
| MMKV plaintext credentials | v2rayNG | Room + DataStore is the right call |
| Loopback Clash API / local HTTP inbound | NekoBox `127.0.0.1:9090`, CMFA `external-controller` | Our unix-socket `CommandServer` is the stronger posture |
| `FreeReflection` hidden-API bypass | SFA | Play-policy risk |
| `StrictMode.permitAll()` in service `onCreate` | v2rayNG | Hides real violations |
| `release.keystore` in VCS | husi | Obvious |
| In-memory timer for subscription updates | FlClash `Timer(20 min)` | Dies with the process |
| Stop → poll → start for per-app changes | CMFA `AccessControlActivity` | `startOrReloadService` re-establishes the TUN without a service restart |
| Naive `Uri.getQueryParameter("url")` for deep links | v2rayNG `UrlSchemeActivity` | Truncates subscription URLs containing unencoded `&`/`#` |

---

## 6. Implementation plan

Vertical slices, each settings → service → UI → tests, per `AGENTS.md`.
Ordered by value; slice 1 is the cheapest and highest-impact.

### Slice 1 — Lifecycle correctness (P0)

1. Persist desired-state (`tunnel_desired`) in DataStore; mirror it in
   `ConnectionManager`; clear on user disconnect and on revoke.
2. Make `ClientVpnService` self-sufficient: on a null intent (system restart)
   read the desired flag, post `startForeground()` **immediately**, then rebuild
   the config from storage via `NodeConfigProvider.compileSelected()`.
3. Return `START_STICKY` while a tunnel is desired.
4. Implement bounded reconnect: `ReconnectPolicy` (5 attempts, 1/2/4/8/16 s),
   triggered by core failure, `StoppedByCore`, or a stats-staleness watchdog;
   `Reconnecting(node, reason, attempt)` becomes reachable.
5. Doze: runtime receiver for `ACTION_DEVICE_IDLE_MODE_CHANGED` →
   `engine.setDeviceIdle()` → `CommandServer.pause()/wake()`; gate
   "reset connections on wake" behind a setting.
6. `commandClient.connect()`: bounded exponential backoff (~10 attempts,
   50 ms → 1 s).
7. Notification: drive from `TrafficStats`, diff on a data class, suppress while
   the screen is off, add `FOREGROUND_SERVICE_IMMEDIATE`, show the resolved
   leaf outbound.

Acceptance: kill the app process while connected → the tunnel comes back without
user action; airplane-mode toggle → `Reconnecting` → `Connected`; screen off →
no notification churn.

### Slice 2 — Config-schema correctness (P0, small)

1. `detour: "proxy"` on the `remote` DoH server (`local` resolves via
   `localDNSTransport()` — no outbound, no detour).
2. Drop `endpoint_independent_nat`, `independent_cache`, and `stack`.
3. Add `urltest.idle_timeout` (e.g. `20m`) to stop background probing.
4. Fix `docs/ARCHITECTURE.md` drift (`stack: system` vs `gvisor`).
5. Add a `ConfigCompilerTest` assertion that no deprecated key is emitted.

Acceptance: `Libbox.checkConfig` passes on 1.14.1 with no deprecation warnings;
a test fails if a deprecated key reappears.

### Slice 3 — Per-app VPN (P1)

1. Settings keys: mode (`off`/`include`/`exclude`) + package set.
2. Fix `openTun`: never mix allowed/disallowed; include mode adds self, exclude
   mode removes self; drop the unconditional self-disallow.
3. Declare `<queries>` (or `QUERY_ALL_PACKAGES` with justification) so package
   resolution works on API 30+.
4. App picker: `getInstalledPackages`, filter self/`android`/no-INTERNET, cache
   in memory, invalidate on package add/remove, selected-first sort, lazy icons.
5. Apply changes while running via `startOrReloadService` + `OverrideOptions`
   rather than a service restart.

Acceptance: include-mode tunnel routes only selected apps; the app's own
subscription fetch still works; changing the list while connected applies
without dropping the notification.

### Slice 4 — Latency + server list (P1)

1. Wire `urlTestAll()` into the Servers screen (ping button → progress →
   repaint rows whose delay changed).
2. Map `OutboundGroupItem.urlTestDelay` / `urlTestTime` onto node rows.
3. Live node switching while connected via `selectNode()` →
   `CommandClient.selectOutbound("proxy", nodeId)` — removes the current
   "Reconnect to apply the new server" limitation.

### Slice 5 — Subscription auto-update (P1)

1. `WorkManager` (add the dependency) — one `PeriodicWorkRequest` per
   subscription, unique name, `initialDelay` from `lastAttemptAtEpochMs`,
   `NetworkType.CONNECTED`.
2. `sync()/syncOne()/cancelOne()` facade re-registered after every profile
   mutation and from `MainActivity`.
3. Interval precedence: user setting > `profile-update-interval` header (first
   import only) > manual; floor at 15 min.

### Slice 6 — Tile, boot, external actions (P1)

1. `TileService` mirroring `ConnectionManager.state`, with a consent trampoline
   activity and `requestListeningState()`; API 34+ `startActivityAndCollapse(PendingIntent)`.
2. `BootReceiver` for `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` with a crash-loop
   guard.
3. Intent actions `vpnclient.action.{START,STOP,TOGGLE}` for automation.

### Slice 7 — Import funnel (P1)

1. One `importBatchConfig(text)` funnel: share links → base64 → sing-box JSON →
   subscription URL.
2. Entry points: clipboard, SAF file, share intent, deep links
   (`vpnclient://import?url=&name=`, `sing-box://import-remote-profile`,
   `clash://install-config`). Parse `url=` by manual substring extraction, not
   `getQueryParameter`.
3. QR scan (CameraX + ZXing, or ML Kit).

### Slice 8 — Hardening (P2)

1. Share-link normalization table from §4.6 + round-trip fixtures.
2. Crash/OOM/power report surfacing: `promoteOOMDraft()` /
   `promotePowerReportDraft()` before `CommandServer`, `appVersion` in
   `SetupOptions`, core notifications rendered.
3. Connections screen (`CommandConnections` + `processInfo` + `closeConnections()`).
4. `setHttpProxy` on the builder (Q+) for proxy-aware apps.
5. `allowBypass` behind a setting.
6. `NetworkMonitor`: request `NET_CAPABILITY_NOT_VPN` explicitly.
7. `StatusMessage.trafficAvailable` honoured; `isExpand` persisted.

---

## 7. Open questions

1. **`setUnderlyingNetworks`**: WireGuard and OpenVPN pass `null` and let the
   platform decide; Tailscale passes an explicitly chosen non-VPN network. Which
   is correct under VPN lockdown on Android 14+ when Wi-Fi drops to cellular?
2. **MTU**: is 9000 safe on older kernels (Android 8/9, kernel 3.18/4.4) once
   `stack` is removed in sing-box 1.15+?
3. **`LOCKED_BOOT_COMPLETED`**: could a minimal config run from
   device-protected storage before first unlock, or does libbox require
   credential-encrypted storage?
4. **Remnawave device de-registration**: is there a client-callable endpoint to
   release an old HWID without the web dashboard?
5. **ECH**: several Xray share links now emit `?ech=`; sing-box client ECH
   support is not uniform across outbound protocols. Does dropping ECH cause
   handshake failures on strict nodes?
6. **Play policy**: whether `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` requires a
   declared VPN role at review time. `[UNVERIFIED]`
