# Android VPN Client — Product & Engineering Bootstrap

> Status: **living project brief / architecture input**, not a frozen specification.  
> Date: **2026-09-18**  
> Purpose: give an autonomous coding orchestrator enough product, research, architecture, testing, and delivery context to build a real Android VPN/proxy client incrementally.
>
> The implementation agent is expected to **research current upstream documentation and repositories before locking core/API decisions**. This file intentionally defines outcomes and constraints more strongly than implementation details.

---

## 1. Product idea

Build a modern Android VPN/proxy client that is:

- reliable enough for daily use;
- visually clean and fast;
- easy for a non-technical user to configure;
- useful for advanced users without making the default UI complicated;
- compatible with common proxy/VPN subscriptions;
- especially well-behaved with **Remnawave** subscriptions;
- architected so the networking core can be upgraded or replaced without rewriting the entire app;
- testable and maintainable;
- a strong Android engineering portfolio project, not merely a thin skin around an existing binary.

The first milestone is **not** to implement every protocol or every power-user option. The first milestone is a complete vertical slice:

> install app → add subscription → fetch and interpret it correctly → show servers → choose server → request VPN permission → connect through Android `VpnService` → show correct connection state and traffic/health data → disconnect cleanly → survive normal Android lifecycle/network changes.

A polished, working small client is more valuable than a large settings UI with unreliable tunnelling.

---

## 2. Working title and package

Working project name:

```text
VPN Client
```

Working package/application ID:

```text
dev.typenil.vpnclient
```

These are intentionally generic and can be rebranded later without affecting architectural decisions.

Do **not** spend significant implementation time on naming/branding during the first phases.

---

## 3. Product principles

### 3.1 Reliability before feature count

The connection lifecycle is the product.

A "Connect" button that occasionally hangs, lies about connection state, leaks DNS, or breaks after Wi-Fi → LTE is worse than missing ten settings.

Prioritize:

1. deterministic state transitions;
2. clear failure reasons;
3. safe cleanup;
4. reconnect behavior;
5. network-change handling;
6. subscription correctness;
7. DNS/routing correctness;
8. UI polish;
9. advanced options.

### 3.2 Simple by default, advanced when requested

Default experience should require very little knowledge:

```text
Add subscription
      ↓
Select server / Auto
      ↓
Connect
```

Advanced screens may expose:

- routing;
- DNS;
- per-app VPN;
- protocol details;
- subscription headers;
- core logs;
- MTU;
- IPv6 policy;
- rule sets;
- custom configuration.

These should not pollute the main flow.

### 3.3 Core is an implementation dependency, not the app architecture

Do not let sing-box/Xray/Mihomo types spread across Compose screens, repositories, persistence, or general business logic.

Create an internal abstraction around the chosen engine.

Example conceptual boundary:

```kotlin
interface VpnEngine {
    suspend fun prepare(config: EngineConfig): EnginePrepareResult
    suspend fun start(config: EngineConfig): EngineStartResult
    suspend fun stop(reason: StopReason)
    fun observeState(): Flow<EngineState>
    fun observeStats(): Flow<TrafficStats>
}
```

The actual API may differ. The important requirement is architectural isolation.

### 3.4 Never silently corrupt or partially reinterpret configuration

If a subscription or node contains an unsupported option:

- detect it;
- preserve raw source where appropriate;
- report a precise warning/error;
- never silently produce a materially different tunnel.

A malformed entry should ideally not destroy an otherwise valid subscription unless the format/core requires atomic validation.

### 3.5 Security/privacy are first-class requirements

This application processes secrets:

- subscription URLs;
- node credentials;
- UUIDs;
- passwords;
- potentially authentication headers;
- user traffic metadata.

Do not write these to normal logs, crash reports, analytics, screenshots, exported diagnostic files, or clipboard without deliberate user action.

---

# 4. Core/engine research — do not skip

Before Phase 1 implementation, perform a short but real spike comparing at least:

1. **sing-box**
2. **Xray-core**
3. **Mihomo**
4. any clearly superior current alternative discovered during research

Do not compare them only by protocol count.

The decision must consider Android integration quality, subscription ecosystem, routing, update cadence, licensing, binary size, API stability, TUN integration, observability, testability, and maintenance cost.

---

## 4.1 Candidate: sing-box

Why it is a strong default candidate:

- sing-box has an official Android client (SFA).
- Android support includes an unprivileged TUN implementation through Android `VpnService`.
- `libbox`/Go mobile bindings are a known Android integration path.
- broad modern protocol support;
- strong routing/DNS model;
- Remnawave can produce a sing-box subscription/config format.

Current research starting points:

- https://sing-box.sagernet.org/
- https://github.com/SagerNet/sing-box
- https://github.com/SagerNet/sing-box-for-android
- https://github.com/singbox-android/libbox

Research questions:

- What is the **canonical supported Android embedding path as of today**?
- Should we consume a published AAR or build/pin our own `libbox.aar`?
- Which API surface is stable enough to wrap?
- How does the official Android client pass the TUN descriptor and network callbacks?
- How are runtime stats/logs exposed?
- How are outbound selectors/urltest groups controlled?
- What is the supported minimum Android API for the selected library build?
- What license obligations are triggered by distribution?
- Can we pin an upstream commit/version reproducibly?
- What happens when the Go core crashes/panics?
- How expensive is core startup?
- What is the APK/AAB size per ABI?

Important: do not blindly copy SFA architecture. Study it for platform integration and then design a smaller architecture for this project's requirements.

---

## 4.2 Candidate: Xray-core

Why it is a serious candidate:

- extremely relevant for VLESS / REALITY / XHTTP ecosystems;
- Remnawave itself is Xray-oriented and can return Xray JSON;
- current `libXray` provides a cross-platform wrapper and Android build path through Go mobile;
- existing Android projects such as v2rayNG provide useful integration/reference behavior.

Starting points:

- https://github.com/XTLS/Xray-core
- https://github.com/XTLS/libXray
- https://github.com/2dust/v2rayNG

Research questions:

- Is `libXray` suitable as an embedded Android library for our requirements?
- What does the current Android TUN integration require?
- Does Xray now provide direct TUN handling suitable for Android or is an additional tun2socks layer still preferred/required in the tested configuration?
- How are statistics and routing status exposed?
- What is the complexity of supporting generic subscriptions outside Remnawave?
- What is the real license picture of the exact build/dependency graph we would distribute?
- How stable are the mobile bindings?
- What does XHTTP support require in the exact current release?
- Can we validate configuration before activating it?

Do not choose Xray merely because Remnawave uses Xray. The client may benefit from a more general engine if compatibility is equivalent.

---

## 4.3 Candidate: Mihomo

Why it is worth evaluating:

- very strong Clash-compatible configuration/routing ecosystem;
- mature concepts around providers, groups, latency selection, rules, DNS, fake-IP, and controller APIs;
- Android clients exist and provide useful reference implementations;
- Remnawave can produce Mihomo/Clash-style output.

Starting points:

- https://wiki.metacubex.one/
- MetaCubeX Android/client repositories and the currently canonical Mihomo core repository must be verified during research.

Important research note:

As of the date of this brief, public search/indexing around the canonical `MetaCubeX/mihomo` repository is inconsistent enough that the orchestrator must **verify the current canonical upstream rather than relying on a stale URL or cached description**.

Research questions:

- What is the current canonical upstream/core?
- What is the supported Android embedding path?
- Is the Android core controlled in-process, through a local controller API, JNI/Go bindings, or another bridge?
- Can the controller remain private/in-process without exposing a localhost management surface?
- How cleanly can the application own `VpnService` lifecycle?
- How does binary size compare?
- What protocols/features differ materially from sing-box/Xray?
- What does current GPL licensing imply for this app?
- How stable is the config format across updates?

---

## 4.4 Core decision matrix

The architect should produce a short ADR before committing the implementation.

Evaluate each candidate on:

| Criterion | Weight / importance |
|---|---|
| Android `VpnService` integration maturity | Critical |
| VLESS + REALITY support | Critical |
| Remnawave compatibility | Critical |
| Generic subscription compatibility | High |
| Stable mobile embedding API | Critical |
| DNS correctness/features | High |
| Routing / per-app capabilities | High |
| IPv6 behavior | High |
| Runtime stats and observability | High |
| Reconnect / network-change behavior | Critical |
| Config validation | High |
| Update cadence / project health | High |
| Ease of pinning/reproducible builds | High |
| Testability | High |
| Binary size / memory / CPU | Medium |
| License obligations | Critical |
| Maintenance burden | Critical |
| Advanced future feature ceiling | Medium |

### Initial hypothesis, not a mandate

**sing-box/libbox is the first candidate to spike** because its official Android story and `VpnService`/TUN integration are directly relevant.

However:

- if Remnawave/XHTTP behavior is significantly more reliable with Xray;
- if sing-box mobile bindings are unstable or difficult to distribute;
- if a different core provides materially better Android lifecycle handling;

the architect may choose another core.

The ADR must explain the evidence and trade-offs.

---

# 5. Remnawave support

Remnawave compatibility is an explicit product requirement, not an accidental side effect.

Starting points:

- https://github.com/remnawave/panel
- official Remnawave docs inside the repository/docs site
- current subscription protocol implementation
- current HWID documentation

Relevant known behavior to verify against current upstream:

- a public subscription URL is distinct from the admin panel endpoint;
- Remnawave can return multiple subscription representations depending on client/request:
  - Base64 link list;
  - Xray JSON;
  - sing-box;
  - Mihomo/Clash;
- response selection can depend on user agent / response rules;
- Remnawave supports optional device limiting via HWID;
- when HWID limits are enabled, a client may need to send an `x-hwid` header;
- additional device headers may be recognized;
- recent Remnawave versions expose response headers that explain HWID/device-limit failures.

### Remnawave client requirements

Implement a dedicated compatibility layer, not scattered special cases.

Concept:

```text
Subscription URL
      ↓
HTTP fetch
      ↓
Response metadata inspection
      ↓
Remnawave detector/metadata adapter
      ↓
Format classifier
      ↓
Parser / core config import
```

The client should:

- send a stable application User-Agent;
- support a privacy-safe stable per-install/device identifier if HWID support is enabled;
- obey current Remnawave HWID character/length rules;
- never use sensitive hardware identifiers such as IMEI;
- surface "device limit reached" separately from generic network failure;
- preserve/understand useful subscription metadata headers;
- support manual refresh and scheduled refresh;
- avoid hammering subscription endpoints;
- use caching and conditional HTTP requests where supported;
- keep the previously valid subscription if a refresh fails or returns invalid content;
- make provider-specific behavior testable.

Do **not** assume every subscription URL belongs to Remnawave.

---

# 6. Subscription pipeline

This area must be implemented as a first-class subsystem.

## 6.1 Sources

MVP:

- pasted subscription URL;
- pasted single node URI.

Near-term:

- QR code;
- clipboard suggestion;
- local config file;
- deep link import.

Potential formats:

- `vless://`
- `vmess://`
- `trojan://`
- `ss://`
- `hysteria2://`
- `tuic://`
- newline-separated URI lists;
- Base64-wrapped URI lists;
- sing-box JSON;
- Xray JSON;
- Clash/Mihomo YAML;
- provider-specific variants.

Actual MVP protocol/format set depends on selected core and research.

## 6.2 Pipeline design

Prefer an explicit pipeline:

```text
SubscriptionSource
        ↓
SubscriptionFetcher
        ↓
FetchResult(raw bytes + HTTP metadata)
        ↓
SubscriptionClassifier
        ↓
Decoder
        ↓
Parser
        ↓
Normalized model OR validated native config
        ↓
ConfigCompiler / EngineAdapter
        ↓
EngineConfig
```

Do not place parsing in ViewModels.

## 6.3 Normalized model

If feasible without losing semantics, use an internal model for things the UI needs:

```kotlin
data class ProxyNode(
    val id: NodeId,
    val displayName: String,
    val protocol: ProtocolType,
    val endpoint: Endpoint,
    val capabilities: Set<NodeCapability>,
    val sourceId: SubscriptionId,
)
```

Sensitive transport details may remain in an opaque/engine-specific structure owned by the integration layer.

Do not normalize away core-specific fields merely to make the model "clean."

## 6.4 Parser requirements

Parsers must:

- be deterministic;
- reject malformed values with typed errors;
- tolerate UTF-8 BOM/whitespace where reasonable;
- handle standard and URL-safe Base64 where applicable;
- avoid catastrophic regex/backtracking;
- have input-size limits;
- never execute arbitrary content;
- preserve node names correctly;
- correctly parse IPv6 host syntax;
- handle URL encoding consistently;
- have fixture-based regression tests.

Create a corpus of real-world sanitized subscription samples.

## 6.5 Update semantics

A refresh is transactional:

```text
fetch new
   ↓
parse
   ↓
validate
   ↓
compile/check engine config
   ↓
commit as current
```

If any required validation fails:

```text
keep last-known-good subscription
+ show refresh error
+ retain raw failure metadata for redacted diagnostics
```

Never replace a working subscription with an empty/broken update just because HTTP returned 200.

---

# 7. Android VPN lifecycle

Use the Android platform correctly.

Official starting point:

- https://developer.android.com/reference/android/net/VpnService

Important properties:

- `VpnService.prepare()` is part of the permission flow;
- only one VPN can be active per user/profile at a time;
- VPN permission can be revoked;
- the VPN service must be declared using `BIND_VPN_SERVICE`;
- modern Android requires correct foreground-service behavior;
- closing the TUN descriptor tears down the interface;
- network changes must be handled intentionally.

## 7.1 Connection state machine

Do not model the entire system as a Boolean `isConnected`.

Use a state machine similar to:

```text
Idle
Preparing
PermissionRequired
Starting
Connecting
Connected
Reconnecting
Stopping
Error
```

Include enough metadata to make state transitions inspectable.

Examples:

```kotlin
sealed interface VpnConnectionState {
    data object Idle : VpnConnectionState
    data object Preparing : VpnConnectionState
    data object PermissionRequired : VpnConnectionState
    data class Connecting(val node: NodeSummary) : VpnConnectionState
    data class Connected(
        val node: NodeSummary,
        val since: Instant,
    ) : VpnConnectionState
    data class Reconnecting(val reason: ReconnectReason) : VpnConnectionState
    data class Error(val error: VpnError) : VpnConnectionState
}
```

Exact classes may change.

## 7.2 Required lifecycle cases

Explicitly test:

- first VPN permission;
- permission denied;
- permission revoked while running;
- connect → disconnect → reconnect;
- switching node while connected;
- app goes background;
- app process recreated;
- screen off/on;
- Wi-Fi → cellular;
- cellular → Wi-Fi;
- temporary loss of network;
- airplane mode;
- device reboot;
- core crash;
- malformed config;
- subscription disappears/refresh fails while active;
- another VPN takes over;
- IPv4-only network;
- IPv6-capable network;
- DNS server unavailable.

## 7.3 Foreground service

The user must always be able to understand whether the tunnel is active.

Notification should minimally show:

- connected / reconnecting / error state;
- selected server;
- optional current throughput;
- disconnect action if platform behavior permits cleanly.

Follow current Android 17 foreground-service and notification requirements rather than copying old tutorials.

---

# 8. Networking and routing requirements

MVP:

- full-device tunnel;
- IPv4;
- DNS through intended tunnel path;
- sane IPv6 policy (either correctly supported or deliberately disabled with explicit behavior);
- basic server selection.

Near-term:

- per-app include/exclude;
- split tunneling;
- bypass LAN/private networks;
- custom DNS;
- route modes;
- rule sets;
- automatic best-node group;
- fallback.

Advanced:

- chained proxies;
- rule editor;
- remote rule sets;
- load balancing;
- custom outbound groups.

No advanced routing feature is "done" without tests that show where matching traffic actually goes.

---

# 9. DNS

DNS is one of the easiest ways for a VPN app to appear connected while behaving incorrectly.

Architect must research chosen core's DNS model.

Requirements:

- no accidental system-DNS leak when configured for tunneled DNS;
- explicit IPv6 behavior;
- cancellation/timeouts;
- clear bootstrap DNS strategy when encrypted DNS hostname itself needs resolution;
- protection from routing loops;
- meaningful diagnostics;
- support DNS changes without corrupting active state.

Do not add ten DNS modes before one mode is proven correct.

---

# 10. UI / UX direction

Use **Kotlin + Jetpack Compose + Material 3** unless the chosen core imposes a compelling reason otherwise.

The app should feel deliberate, not like a generic generated settings demo.

## 10.1 Home

Primary screen concept:

```text
┌────────────────────────────────────┐
│ VPN Client                         │
│                                    │
│        Connected / Disconnected    │
│                                    │
│            [ CONNECT ]             │
│                                    │
│  🇳🇱 Netherlands #1         42 ms   │
│  VLESS · REALITY                   │
│                                    │
│  ↓ 12.4 MB           ↑ 2.1 MB      │
│  3.2 Mbps            0.7 Mbps      │
│                                    │
│  Subscription: Personal            │
└────────────────────────────────────┘
```

The UI must make state obvious without relying only on color.

## 10.2 Servers

Need:

- node name;
- protocol badge;
- latency when known;
- selected state;
- search;
- sort;
- optional country/flag only when derivable reliably;
- refresh/test action.

Do not fake ping by timing a random HTTP request unrelated to the proxy.

Research the correct latency testing mechanism for the selected core.

## 10.3 Subscriptions

Show:

- name;
- source host, with secrets/token path redacted;
- last successful update;
- last attempted update;
- node count;
- expiration/traffic metadata when available;
- update status;
- update action;
- edit/remove;
- error message.

## 10.4 Settings

Start small:

- theme: system/light/dark;
- language if localization is implemented;
- auto-connect behavior;
- reconnect behavior;
- IPv6 policy if user-facing;
- diagnostics/log level;
- advanced section.

Do not expose internal core fields directly unless there is a real use case.

## 10.5 Error UX

Use typed errors mapped to user-facing actions.

Examples:

```text
Subscription could not be parsed
[Show details] [Keep current configuration]

Device limit reached
This subscription cannot be added to another device.
[Close]

VPN permission required
Android must allow this app to create a VPN connection.
[Allow]
```

Avoid meaningless messages such as "Unknown error -1."

---

# 11. Architecture

Use pragmatic Android architecture.

Recommended starting shape:

```text
app/
  UI/navigation/application wiring

core/
  common models
  result/error types
  secure/redacted logging
  time/network abstractions

subscription/
  fetch
  classify
  parse
  persist
  refresh
  Remnawave compatibility

vpn/
  Android VpnService
  engine abstraction
  selected-core adapter
  lifecycle/state machine
  stats
```

It is acceptable to start with fewer Gradle modules and split once boundaries stabilize.

Avoid creating twenty modules before the first connection works.

## 11.1 Layering

Expected dependency direction:

```text
Compose UI
    ↓
ViewModel / use-case orchestration
    ↓
repositories / services
    ↓
subscription + vpn abstractions
    ↓
Android platform + selected networking core
```

UI must not:

- call libbox/libXray/Mihomo directly;
- parse subscription text;
- own VPN service lifecycle;
- contain secrets in saved UI state unnecessarily.

## 11.2 State

Use:

- coroutines;
- `Flow` / `StateFlow`;
- immutable UI state;
- structured concurrency.

Avoid global mutable singleton state unless it is an intentional process-scoped component with a clear lifecycle.

## 11.3 Dependency injection

Use a mainstream solution only if it reduces real complexity.

Hilt is acceptable.

Do not build a custom DI framework.

## 11.4 Persistence

Expected categories:

- app preferences → DataStore;
- subscriptions/nodes/history requiring queries → Room or another justified local database;
- secrets → Android Keystore-backed encryption strategy;
- cached raw configs → app-private storage, encrypted if threat model requires it.

Research current Android security recommendations before selecting deprecated security APIs.

---

# 12. Security

Minimum requirements:

- no cleartext HTTP by default;
- no TLS certificate bypass by default;
- subscription credentials redacted from logs;
- no UUID/password/token in analytics;
- do not expose local management/controller ports unless unavoidable;
- if a controller exists, bind privately and authenticate it;
- `VpnService` should not be unnecessarily exported;
- exported components must be deliberate;
- imported URIs/configs treated as untrusted input;
- cap subscription/config input size;
- protect against malicious YAML/JSON parser behaviors;
- no WebView required for core functionality;
- avoid arbitrary filesystem access;
- validate deep links;
- no permanent clipboard polling;
- sanitize exported diagnostics.

Threat-model at least:

1. malicious subscription content;
2. malicious app sending intents/deep links;
3. secrets appearing in logs/backups;
4. untrusted local process accessing a core controller;
5. dependency/core supply-chain compromise;
6. stale/compromised subscription refresh.

Pin important native/core artifacts and verify checksums where practical.

---

# 13. Licensing / distribution

This cannot be deferred until release.

Before selecting a core, document:

- core license;
- binding/wrapper license;
- modifications;
- transitive components that affect distribution obligations;
- whether the resulting APK must be GPL-compatible/open source;
- attribution/source-offer requirements;
- whether app-store distribution imposes additional concerns.

Current candidates include GPL-licensed components, while Xray-core is commonly distributed under MPL-2.0 but exact combined-build obligations must be checked for the selected dependency graph.

For the first portfolio/open-source version, an open-source distribution model is acceptable and may simplify the decision.

Create:

```text
docs/adr/ADR-0001-vpn-core.md
docs/LICENSING.md
```

before a public release.

---

# 14. Testing strategy

Testing is part of every phase, not cleanup at the end.

## 14.1 Unit tests

At minimum:

- URI parser fixtures;
- Base64 variants;
- subscription classifier;
- malformed inputs;
- duplicate node handling;
- stable IDs;
- Remnawave metadata/header interpretation;
- config compiler;
- state-machine transitions;
- redaction functions;
- retry/backoff rules.

## 14.2 HTTP integration tests

Use a mock HTTP server.

Cover:

- 200 valid body;
- 200 invalid body;
- redirect;
- gzip/compression;
- charset handling;
- timeout;
- DNS/network failure;
- 401/403/404;
- rate limiting;
- 5xx;
- malformed headers;
- Remnawave HWID rejection;
- device-limit response;
- changed ETag/Last-Modified if supported;
- unchanged subscription.

## 14.3 Core integration tests

Where possible:

- validate generated config using the core's own validator;
- start core with a test config;
- stop cleanly;
- assert invalid config fails before VPN activation;
- check control/status API;
- check selected outbound switching.

## 14.4 Instrumentation/device tests

A real Android device is required for confidence.

Create a manual/device verification matrix for at least:

- Android 8/9 class device or emulator if minSdk 26;
- a modern Android release;
- Android 17/API 37;
- physical device used during development.

Test:

- VPN permission;
- foreground notification;
- actual external IP changes through a test server;
- DNS;
- IPv6;
- network switching;
- process death;
- reconnect;
- long-running connection;
- battery/background behavior.

## 14.5 No fake green tests

A mocked `VpnEngine` test does not prove the VPN works.

CI green + successful real-device smoke test are separate gates.

---

# 15. Observability and diagnostics

Implement structured, redacted internal logs.

Useful events:

```text
SubscriptionFetchStarted
SubscriptionFetchSucceeded
SubscriptionParseFailed
EngineConfigValidated
VpnPermissionRequested
EngineStartStarted
EngineStarted
UnderlyingNetworkChanged
ReconnectScheduled
EngineStopped
```

Never log raw credentials.

Add a developer diagnostics screen later with:

- app version;
- core version;
- Android version;
- ABI;
- connection state;
- last state transitions;
- sanitized error chain;
- sanitized network properties.

Future option:

```text
Export diagnostics
```

must remove:

- UUIDs;
- passwords;
- tokens;
- full subscription URLs;
- private server names if configured as sensitive.

---

# 16. Performance

Measure rather than guess.

Track:

- cold app startup;
- core startup time;
- connect time;
- memory while connected;
- CPU idle while connected;
- battery impact;
- APK/AAB size;
- throughput;
- latency overhead.

Avoid waking the UI/stat collectors excessively when backgrounded.

Realtime throughput can update faster while the screen is visible and slower in background/notification.

---

# 17. CI / quality gates

Set up early:

```text
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Add static analysis if it has clear value.

Expected CI checks:

- compilation;
- unit tests;
- Android lint;
- formatting/static analysis;
- debug APK artifact;
- parser fixture regression suite.

For native/core artifacts:

- pin versions;
- record checksum/version;
- make upgrade process explicit;
- avoid silently downloading "latest" during reproducible release builds.

---

# 18. Delivery phases

The orchestrator may refine these after research but should preserve vertical progress.

## Phase 0 — repository/bootstrap + evidence-based core spike

Deliverables:

- Android project builds;
- base Compose navigation/theme;
- core candidates researched;
- small spike(s) proving real Android integration;
- ADR choosing the first core;
- license notes;
- CI baseline.

Exit criterion:

> A minimal test configuration can start/stop the chosen engine on Android, or the architect has concrete evidence that another engine should replace it.

Do not build most UI before this.

---

## Phase 1 — one-node end-to-end VPN

Deliverables:

- `VpnService`;
- permission flow;
- selected core adapter;
- one known-good test node/config;
- connection state machine;
- foreground notification;
- connect/disconnect UI;
- redacted logs.

Exit criterion:

> On a physical Android device, pressing Connect routes traffic through the test proxy/VPN and Disconnect restores normal networking.

---

## Phase 2 — subscription vertical slice

Deliverables:

- add subscription URL;
- HTTP fetch;
- robust classifier/parser for chosen MVP formats;
- persist subscription;
- show nodes;
- select node;
- generate/validate engine config;
- connect selected node;
- manual refresh;
- last-known-good behavior.

Exit criterion:

> Fresh install → paste a real supported subscription → select node → connect successfully without developer intervention.

---

## Phase 3 — Remnawave compatibility

Deliverables:

- current Remnawave request/response behavior verified;
- stable privacy-safe HWID implementation where needed;
- current required/optional headers;
- response metadata;
- traffic/expiry metadata when exposed;
- typed device-limit errors;
- fixtures/tests;
- compatibility notes.

Exit criterion:

> A current Remnawave subscription with normal and HWID-limited configurations behaves correctly and reports useful failures.

---

## Phase 4 — production-quality lifecycle

Deliverables:

- Wi-Fi/LTE changes;
- offline/online;
- reconnect policy;
- process/service recovery;
- permission revoke;
- core crash handling;
- switching nodes;
- stale subscription behavior;
- IPv6 policy;
- DNS validation.

Exit criterion:

> Normal Android lifecycle/network transitions do not require force-stop/reinstall and do not leave the UI lying about actual VPN state.

---

## Phase 5 — UI/UX pass

Deliverables:

- polished home;
- server list;
- subscription management;
- settings;
- Material 3 adaptive UI;
- loading/empty/error states;
- accessibility basics;
- dark/light/system theme.

Exit criterion:

> App is understandable without reading internal logs or developer documentation.

---

## Phase 6 — routing/features

Candidates, only after the base client is solid:

- per-app VPN;
- split tunneling;
- bypass local network;
- custom DNS;
- latency groups;
- auto-best server;
- QR import;
- quick settings tile;
- widgets;
- subscription auto-refresh;
- backup/export;
- advanced routing/rule sets.

Each feature must be its own vertical slice with tests.

---

# 19. Definition of MVP

MVP is complete only if all of the following are true:

```text
[ ] Clean install works
[ ] App requests VPN permission correctly
[ ] User can add a subscription URL
[ ] Subscription is fetched safely
[ ] Supported format is correctly identified
[ ] Nodes are parsed/imported
[ ] Invalid subscription does not destroy last-known-good state
[ ] User can select a node
[ ] Core config is validated
[ ] VPN connects on a physical Android device
[ ] External traffic actually uses the selected tunnel
[ ] DNS behavior is verified
[ ] Connection state shown by UI matches reality
[ ] Foreground notification is correct
[ ] Disconnect cleans up
[ ] Reconnect after a normal network transition works
[ ] Subscription can be refreshed
[ ] Remnawave basic compatibility works
[ ] Sensitive information is redacted from logs
[ ] Unit/integration tests pass
[ ] Debug APK builds reproducibly
```

---

# 20. Non-goals for the first version

Do not spend the initial implementation window on:

- writing a VPN/proxy protocol from scratch;
- building a VPN server/control panel;
- payment/billing;
- user accounts;
- cloud sync;
- social/community features;
- root-only modes;
- desktop/iOS versions;
- dozens of custom themes;
- an elaborate plugin system;
- AI features;
- analytics growth stack;
- automatic censorship/DPI tricks that are not required for the selected protocols;
- cloning every setting from Happ/FlClash/NekoBox.

A reliable vertical slice comes first.

---

# 21. Agent/orchestrator operating rules

The implementation orchestrator should treat this document as direction, not immutable truth.

### Research

Use current web search and primary sources before decisions involving:

- core APIs;
- Android 17 platform behavior;
- Remnawave protocol;
- protocol support;
- library versions;
- licensing.

Prefer:

1. official docs;
2. canonical repositories/source;
3. active issue/discussion threads when behavior is unclear;
4. mature reference clients.

Do not use random tutorials as architectural authority.

### Delegation

Use workers for bounded tasks such as:

- core integration spike;
- Remnawave protocol research;
- subscription parser;
- Android `VpnService` implementation;
- test suite;
- UI slice;
- code review.

The orchestrator owns:

- architecture;
- dependency boundaries;
- integration;
- resolving conflicting worker assumptions;
- final verification.

Avoid multiple workers editing the same central files at the same time.

### Implementation style

Proceed in small vertical slices.

For every slice:

```text
research / inspect
      ↓
design boundary
      ↓
implement
      ↓
build
      ↓
test
      ↓
review diff
      ↓
integrate
```

Do not accumulate a giant unbuilt branch.

### Autonomy

Do not stop for trivial choices.

Make reasonable reversible decisions and record them.

Pause only for decisions that are genuinely product-level, irreversible, involve credentials/cost, or cannot be validated safely.

### Verification

A worker saying "done" is not verification.

The orchestrator should independently:

- inspect diffs;
- compile;
- run tests;
- check architecture;
- verify real device behavior where available.

---

# 22. Suggested repository docs

As the project develops, maintain:

```text
README.md
docs/
  PRODUCT.md
  ARCHITECTURE.md
  SUBSCRIPTIONS.md
  REMNAWAVE.md
  TESTING.md
  SECURITY.md
  LICENSING.md
  adr/
    ADR-0001-vpn-core.md
```

Do not create documentation merely to create files. Keep it short and operational.

---

# 23. First-session target

A successful first autonomous work session should ideally leave the repository with:

```text
1. buildable Android project;
2. CI;
3. core comparison/ADR;
4. chosen engine dependency pinned;
5. a real engine start/stop spike;
6. VpnService skeleton or working implementation;
7. minimal Compose home screen;
8. connection state model;
9. initial subscription-fetch subsystem;
10. tests around the implemented pieces;
11. a clear next slice.
```

If enough time/context remains, continue into the end-to-end subscription → connection demo.

The priority is **working evidence**, not line count.

---

# 24. Acceptance demo

The first meaningful demo should be extremely concrete:

1. Launch app.
2. Tap **Add subscription**.
3. Paste URL.
4. App fetches and validates it.
5. App shows imported nodes.
6. Select one.
7. Tap **Connect**.
8. Android VPN permission dialog appears if necessary.
9. UI transitions through real states.
10. Device traffic goes through selected node.
11. Server/traffic information updates.
12. Switch Wi-Fi ↔ mobile data and observe recovery.
13. Tap **Disconnect**.
14. Tunnel and foreground service stop cleanly.
15. Relaunch app and subscription remains available.

Anything less is an intermediate engineering spike, not the MVP demo.

---

# 25. Research notes gathered before implementation

These notes are only starting evidence. Re-check upstream before coding.

### Android

Android's `VpnService` creates a virtual network interface and returns a file descriptor used to exchange IP packets. VPN permission is user-controlled and revocable. A VPN service must be declared with the VPN service permission/intent contract. Android 8+ background execution requires correct foreground-service behavior.

Android 17 is API 37. This project should be tested against API 37 behavior from the start.

Sources:

- https://developer.android.com/reference/android/net/VpnService
- https://developer.android.com/about/versions/17/setup-sdk
- https://developer.android.com/about/versions/17/behavior-changes-17

### sing-box

The official sing-box Android client documents an Android `VpnService`/TUN implementation, and `libbox` is available as an Android library/binding path.

Sources:

- https://github.com/SagerNet/sing-box
- https://github.com/SagerNet/sing-box-for-android
- https://github.com/singbox-android/libbox
- https://sing-box.sagernet.org/

### Xray

Xray-core has Android builds and `libXray` provides a wrapper/build path including Android Go-mobile output. v2rayNG is a useful reference client but should not be copied wholesale.

Sources:

- https://github.com/XTLS/Xray-core
- https://github.com/XTLS/libXray
- https://github.com/2dust/v2rayNG

### Mihomo

Mihomo/Clash-style systems provide strong provider/group/routing concepts and Android clients exist. The canonical current repository/integration route must be verified before using it because public indexing/upstream naming appears to have changed over time.

Sources:

- https://wiki.metacubex.one/
- https://github.com/MetaCubeX/ClashMetaForAndroid

### Remnawave

Current Remnawave docs show subscription output support for multiple client formats including Base64, Xray JSON and sing-box, with client detection/response behavior. HWID device limiting is optional but clients supporting it must follow the current header contract; Remnawave documents `x-hwid` and response headers for device-limit conditions.

Sources:

- https://github.com/remnawave/panel
- https://github.com/remnawave/panel/blob/main/docs/features/hwid-device-limit.md
- https://github.com/remnawave/panel/blob/main/docs/learn/quick-start.md

A third-party developer guide also exists for Remnawave subscription-client behavior. Treat it as supplemental and verify important details against official panel source/docs:

- https://github.com/skvarovski/remnawave-dev-docs

---

# 26. Initial technical defaults

Unless Phase 0 research demonstrates a better choice:

```text
Language:              Kotlin
UI:                    Jetpack Compose
Design:                Material 3
Async:                 Coroutines + Flow
DI:                    Hilt (if useful)
Preferences:           DataStore
Structured data:       Room when needed
HTTP:                  OkHttp
Serialization:         kotlinx.serialization where appropriate
minSdk:                26
compileSdk:            37
targetSdk:             37
Build scripts:         Kotlin DSL
Java toolchain:        choose current Android/AGP-compatible LTS JDK
Core candidate #1:     sing-box/libbox
Core candidate #2:     Xray/libXray
Core candidate #3:     Mihomo
```

Versions must be chosen from current stable releases at implementation time, pinned, and documented.

---

# 27. Final instruction to the architect

Do not optimize for producing the most code while the owner is away.

Optimize for leaving behind a repository where:

- architectural decisions are evidence-based;
- the core is isolated;
- Android lifecycle handling is correct;
- each completed slice compiles and is tested;
- the application is measurably closer to a real usable VPN client;
- future work does not require rewriting the foundation.

When uncertain, prefer the smallest implementation that proves the next risky assumption.
