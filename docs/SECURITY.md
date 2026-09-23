# Security

## Secrets handling

- **Never logged:** full subscription URLs, UUIDs, passwords, tokens, raw configs.
  `SecureLog` routes through `Redactor` which strips UUIDs, userinfo credentials,
  sensitive query params, bearer tokens and long opaque tokens.
- **Storage:** subscription URLs and `rawUri`/outbound JSON live in Room —
  local-only, not exported, no backup rules customized (default backup applies;
  note for future hardening). Selected node id and HWID live in DataStore.
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
