# Security

## Secrets handling

- **Never logged:** full subscription URLs, UUIDs, passwords, tokens, raw configs.
  `SecureLog` routes through `Redactor` which strips UUIDs, userinfo credentials,
  sensitive query params, bearer tokens and long opaque tokens.
- **Storage:** subscription URLs and `rawUri`/outbound JSON live in Room —
  local-only, not exported; `android:allowBackup="false"` plus wired
  `dataExtractionRules` exclude all app data from cloud backup and
  device-to-device transfer. Selected node id and HWID live in DataStore.
- **HWID:** install-scoped random value, not a hardware identifier.

## Network surface

- `ClientVpnService` is non-exported and requires `BIND_VPN_SERVICE` — only the
  system can bind it.
- libbox `CommandServer` listens on an app-private Unix socket (abstract
  namespace), **not** a TCP port — no localhost API exposed to other apps.
- No Clash external-controller API is enabled.
- Subscription fetch: HTTPS by default — cleartext `http://` URLs are rejected
  unless the user explicitly opts that subscription in
  (`SubscriptionEntity.allowInsecureHttp`, checkbox at add time). An
  `https→http` redirect is never followed even with the opt-in. Redirects are
  followed manually (max 5); Remnawave HWID headers are sent only to the exact
  origin (scheme+host+port). 8 MiB cap, 15/30 s timeouts.
  Redirect targets are also screened: a hop from a public host to a
  loopback/private/link-local/CGNAT/ULA literal (or `localhost`) is rejected
  (`SubscriptionError.ForbiddenAddress`) — SSRF pivot guard. Private origins
  stay legal (user-confirmed local subscriptions); DNS names resolving to
  private addresses are out of scope.
  `usesCleartextTraffic="true"` stays in the manifest because the opt-in needs
  the OS to permit cleartext — the fetcher enforces the policy itself.
- **Self rides the tunnel:** the app's own OkHttp traffic (subscription
  refresh, rule-set downloads) is routed through the VPN while connected
  (`PerAppPolicy` includes the app). A malicious exit node can therefore
  observe this fetch path; downloads are still integrity-gated (rule sets
  carry `SRS\x01` magic + full zlib payload decompression validation + size cap,
  subscription bodies are full-validated before commit), so worst case is a
  failed fetch keeping last-known-good — never silent corruption. `LatencyProbe` is the deliberate exception: it
  measures the underlay, so it binds off-tunnel via `VpnSocketProtector`.

## Tunnel gaps and system fail-closed protection

The app does not implement its own kill switch or blocking TUN. During
`ClientVpnService.rebuildTunnel`, the old engine is stopped (which also closes
its TUN), the service closes the TUN, and a new engine establishes a fresh
interface. There is a gap with no VPN interface. Rebuilds happen for per-app
rules, changes to enabled subscription nodes, and changes to the underlay IPv6
mode (for example Wi-Fi/mobile handover). Automatic reconnects can also leave
no TUN while restarting. New connections can use the physical network directly
during these gaps; a UI reconnecting state is not a traffic block.

Enable **both Always-on VPN and Block connections without VPN** in Android VPN
settings to have the system deny non-VPN connections while the VPN is absent.
Always-on alone restarts the service; it does not provide this blocking policy.
Lockdown is enforced by Android for the relevant user/profile, subject to system
VPN policy/exemptions; the app cannot enable it itself. Split-routing rules and
protected core/underlay sockets are not a promise that every packet uses a proxy.
The Settings row opens `Settings.ACTION_VPN_SETTINGS` and reports system flags,
not an app-owned protection state.

### Retaining the old TUN during rebuild (research only)

Android [`Builder.establish()`](https://developer.android.com/reference/android/net/VpnService.Builder#establish())
supports keeping the old interface until the replacement succeeds: success
moves outgoing packets to the new interface, leaving the old descriptor valid
for draining/closing; failure leaves the old interface untouched (permission
revocation is a separate failure). This could reduce a route gap, but is **not
implemented** here. Simply moving `closeTun()` is insufficient: engine stop and
start-error cleanup also close the platform TUN, and the service currently owns
one mutable descriptor.

A future change would need generation/engine-scoped descriptor ownership,
serialized engine replacement, an explicit retained-descriptor cleanup policy,
and native fd lifetime verification. A stopped engine with a retained TUN
stalls/drops packets; it does not keep the VPN usable. Old per-app/IPv6 routes
may still bypass traffic newly covered by the replacement policy. Successful
establishment also does not guarantee a working remote handshake. Failure,
revoke, cancellation, disconnect races and native close paths need device tests;
retention would not replace system lockdown. Expect a separate lifecycle change
(service/engine ownership plus unit and device tests), not a one-line reorder.

## Untrusted input

All subscription content is attacker-controlled: capped size, typed parse
failures, full-parse-before-commit so a poisoned refresh can't wipe working
nodes.

## Core supply chain

`libbox.aar` comes from the pinned `singbox-android/libbox` GitHub release,
verified against a SHA-256 in `app/build.gradle.kts` before every build. The
AAR is not committed; `core-native/` is gitignored.

## Permissions

INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SYSTEM_EXEMPTED (VpnService type), POST_NOTIFICATIONS.
No location, contacts, or storage permissions.
