# ADR-0001: VPN core selection — sing-box via libbox

Status: accepted
Date: 2026-03-19 (recorded during initial architecture phase)

## Context

The app needs a real proxy engine behind Android `VpnService`. Primary requirements from
`ANDROID_VPN_CLIENT_PROJECT_BRIEF.md`:

- Android `VpnService`/TUN integration with correct DNS/routing
- VLESS (+REALITY/Vision), Trojan, VMess, Shadowsocks, Hysteria2, TUIC protocol coverage
- Remnawave panel compatibility (sing-box/Xray/Clash subscription outputs)
- Generic subscription (share-link URI lists) compatibility
- Runtime control: state/stats/groups/latency, config validation
- Deterministic lifecycle and cleanup; survive network changes
- Reproducible, pinned, verifiable binary dependency
- Reasonable implementation and long-term maintenance cost

Candidates evaluated: **sing-box/libbox**, **Xray-core/libXray**, **Mihomo**
(plus a sanity check on OpenVPN/WireGuard-only stacks — rejected: no VLESS/REALITY,
doesn't meet the protocol requirements).

## Decision

Use **sing-box** embedded via the **libbox** Android bindings, run in-process, controlled
through libbox `CommandServer` + `CommandClient` (gRPC over a private Unix socket — no
loopback HTTP API exposed).

Pinned artifact:

- `libbox` **1.14.1**, AAR fetched at build time from the
  `singbox-android/libbox` GitHub release and verified against a pinned SHA-256
  (`93b2596c…f2a9`, see `app/build.gradle.kts`). Version in `gradle/libs.versions.toml`
  (`vpnCore`). The AAR is intentionally not committed (`core-native/` is gitignored).

## Why sing-box

Evidence gathered from upstream sources (SagerNet/sing-box, singbox-android/libbox,
singbox-android/sing-box-for-android — the official reference client):

1. **Proven Android integration.** sing-box-for-android is a shipping client using the
   exact same stack: `VpnService.Builder.establish()` inside a `PlatformInterface.openTun`
   callback, `autoDetectInterfaceControl` → `VpnService.protect`, a `DefaultNetworkMonitor`
   feeding `InterfaceUpdateListener`, and a `LocalDNSTransport` bridging to Android
   `DnsResolver`. We mirrored that architecture directly.
2. **Consumable artifact.** A prebuilt `libbox.aar` (all 4 ABIs incl. x86_64 — required
   for our emulator) is published per upstream release; we pin + checksum it. libXray also
   publishes AARs, Mihomo does not (needs a custom gomobile toolchain; Go is unavailable
   in this environment anyway).
3. **In-process control, no exposed API.** `CommandServer` exposes
   status/stats/groups/urltest/config-reload over an app-private socket — no localhost
   port, nothing to protect from other apps. libXray's recent `Invoke` JSON-RPC model is
   newer and explicitly unstable; Clash-style external controllers need a listening socket.
4. **Observability built in.** `Libbox.checkConfig` validates configs before service
   start; `CommandClient` gives traffic stats, outbound groups (selector/urltest), runtime
   group switching (`selectOutbound` — node switching without reconnect) and `urlTest`
   latency. libXray removed/changed its stats APIs in the rewrite.
5. **Protocol coverage.** VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS;
   REALITY client; `flow: xtls-rprx-vision` is supported (verified in sing-box docs), which
   covers the dominant Remnawave VLESS+REALITY+Vision profile.
6. **Remnawave native output.** Remnawave emits sing-box config subscriptions natively
   (`/singbox` route and UA-based rules), so its full feature set works without a
   translation layer.

## Why not the alternatives

**Xray-core / libXray** — strongest protocol set (VLESS/REALITY/**XHTTP**), and AARs exist.
Not chosen because: the binding API was rewritten recently (`Invoke` JSON-RPC) with no
stability guarantee; TUN inbound support is ~8 months old; query/stats APIs were removed
or reshaped; geo asset files must be shipped separately; a Go panic kills the host process
(sing-box shares this risk but mitigations are documented); small maintainer pool.
XHTTP remains the one feature we knowingly give up — acceptable: Remnawave still serves
non-XHTTP transport variants, and the engine boundary lets us add an Xray adapter later if
XHTTP deployments become unavoidable.

**Mihomo** — excellent routing/rule engine and Clash subscription ecosystem.
Not chosen because: no official Android AAR exists; embedding means maintaining a custom
gomobile/JNI bridge (ClashMetaForAndroid proves it's possible but it's a large ongoing
burden); canonical-branch layout is confusing; the provider/group model is powerful but
heavier than needed here. Would revisit if Clash-format subscriptions became the dominant
requirement and sing-box couldn't cover it.

## Consequences

- **License:** libbox is GPL-3.0. Fine for this open-source portfolio project; would block
  closed-source distribution. Documented in `docs/LICENSING.md`.
- **Pinned release:** upgrades are deliberate (bump `vpnCore` + SHA-256,
  re-run `checkConfig` against our compiled configs in tests).
- **XHTTP unsupported** — see above.
- **Isolation:** all `libbox.*` types live inside `core.engine.singbox`; the rest of the
  app speaks `VpnEngine`/`EngineConfig` only, so swapping cores later is bounded work.
- **Go runtime risk:** a native panic can kill the process; we call
  `Libbox.prepareCrashSignalHandlers()` at startup and keep engine code inside
  `ClientVpnService`'s lifecycle.
