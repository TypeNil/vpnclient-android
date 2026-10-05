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
  refresh, rule-set downloads, including the background rule-set refresh after
  `Connected`) is routed through the VPN while connected (`PerAppPolicy`
  includes the app). A malicious exit node can therefore observe this fetch
  path; downloads are still integrity-gated (see "Rule-set payloads";
  subscription bodies are full-validated before commit), so worst case is a
  failed fetch keeping last-known-good — structural corruption is rejected,
  but a well-formed hostile rule list is not detectable. `LatencyProbe` is the
  deliberate exception: it measures the underlay, so it binds off-tunnel via
  `VpnSocketProtector`.

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
Always-on alone makes the system ask the app to start the service
(`AutomaticStartBranch.AlwaysOn`, which starts without our own desire flag
when consent and a selected node/Auto exist; missing prerequisites alert and
stop). It does not provide a blocking policy. Observed on CPH2449 only
(2026-10-03): the system start works with and without lockdown, but a killed
service was not restarted, and reboot with always-on is **not verified**. The
app does not promise a kill switch or leak-proofing.
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

### Rule-set payloads

Rule sets (`.srs`) are public data but still untrusted input. Every file —
downloaded, bundled seed, or already stored — must pass: size cap (32 MiB),
`SRS\x01` magic, a complete zlib payload within a 64 MiB decompressed bound
(decompression-bomb guard), a decompressed-header schema check (rule count,
rule/item type), and a decode by the core (`Libbox.checkConfig` on a probe
config, fail-closed on any error). A download goes to a unique temp file and
replaces the working copy atomically only after all checks pass; any failure
keeps last-known-good. The background refresh (`refreshStale`) is
single-flight and logs only the exception class. A valid but old copy is used
as-is on connect; staleness never blocks or fails a connect. Upstream
publishes no checksums, so integrity is structural, not authenticity: a
hostile or compromised exit node/CDN could still serve a *valid* but
malicious rule list.

### Imported nodes and uTLS

Hysteria2 and TUIC builders drop the `utls` block: the pinned sing-box 1.14.1
rejects uTLS for the QUIC (sing-quic) path on the first connection. Those
nodes therefore carry no client-fingerprint mimicry; TCP-based outbounds keep
uTLS (`chrome` unless the source names a fingerprint). Explicit `insecure`
TLS flags are preserved and shown read-only in the Servers UI; the app does
not set a global trust-all.

## Diagnostics export

The share action sends a redacted snapshot (app log ring + a bounded core-log
tail) through `DiagnosticProvider`: a non-exported, read-only, URI-granted
**memory pipe**. Nothing is written to a cache file or the filesystem; the
payload lives in process memory, is replaced by the next export, and
disappears with the process (an old URI then fails as expired).

Redaction coverage:
- App log: `Redactor.redact` (UUIDs, `user@host`, sensitive query params,
  bearer tokens, long opaque tokens).
- Core log: `Redactor.redactCore` on top of that — all URLs, IP addresses,
  host names, `name=`/`tag=`/credential `key=value` pairs, plus every string
  under sensitive keys of the session's compiled config (`server`,
  `server_name`, `password`, `uuid`, `token`, `auth`, `auth_str`,
  `public_key`, `private_key`, `pre_shared_key`, `short_id`, `username`) and
  the node name/server, matched case-insensitively. Transport `path`,
  `service_name`, `host` and all nested strings under `headers` are included
  when trimmed length is at least 4; existing short credentials stay protected.
  Redaction happens before insertion. Core logs keep only
  panic/fatal/error/warn levels, 512 characters per line, 500 lines.

Known limits:
- Redaction is pattern + context based, not a proof. Unlisted config keys and
  context fragments shorter than 4 characters may survive generic patterns.
  Treat the export as sensitive and review it before sharing.
- Recipients that require a seekable descriptor cannot read the pipe; we do
  not fall back to a file. Receiver behavior is not verified on a device.
- Stop drops config-sensitive keys and rejects callbacks; the bounded redacted
  tail remains in memory, labelled previous session after guarded detach.
  New engine epoch/start, explicit Diagnostics clear and process death erase it.
  Nothing is collected while disconnected or with the screen off; synchronous
  startup failures before native log subscription can still have no core lines.
- Health exports only actual active evidence: enum names and evidence times,
  with TTL projected at read. No active session is explicitly `not available`;
  no addresses, probe responses or node/config payloads enter this block.
- Verified on CPH2449 (WP-4b): secret scans over the export and logcat found 0
  matches for the session's real credentials. This is one device and one
  node set, not a general guarantee.

## Core supply chain

`libbox.aar` comes from the pinned `singbox-android/libbox` GitHub release,
verified against a SHA-256 in `app/build.gradle.kts` before every build. The
AAR is not committed; `core-native/` is gitignored.

## Permissions

INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SYSTEM_EXEMPTED (VpnService type), POST_NOTIFICATIONS,
RECEIVE_BOOT_COMPLETED (restore after boot/update, see `BootReceiver`), CAMERA
(QR subscription import only; the camera feature is optional). No location,
contacts, or storage permissions. Exported components: `MainActivity`
(launcher/import intents; never honors caller-supplied connect extras),
`VpnTileService` (system-bound via `BIND_QUICK_SETTINGS_TILE`) and
`BootReceiver` (acts only on `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`).
`ClientVpnService` and `DiagnosticProvider` are not exported.
