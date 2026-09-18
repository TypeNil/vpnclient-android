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
- Subscription fetch: HTTPS expected (OkHttp; cleartext not enabled), 8 MiB cap,
  15/30 s timeouts, redirects followed.

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
