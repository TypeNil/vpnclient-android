# AGENTS.md — working rules for coding agents

This is an Android VPN/proxy client. Read `ANDROID_VPN_CLIENT_PROJECT_BRIEF.md`
for the product brief and `docs/ARCHITECTURE.md` for the current module map
before making structural changes.

## Hard rules

- **Never log or commit secrets**: no subscription URLs, UUIDs, passwords,
  tokens, or raw proxy configs in logs, tests, fixtures, or git history.
  Use `core.common.log.SecureLog` / `Redactor` for anything derived from
  user or network data.
- **Engine isolation**: code outside `core.engine.*` must not reference
  libbox/sing-box (or whichever core is selected — see
  `docs/adr/ADR-0001-vpn-core.md`) types. The UI talks to `ConnectionManager`
  and `VpnConnectionState`, never to the core directly.
- **Treat imported subscriptions as hostile input**: size limits, typed
  parse errors, no eval, no arbitrary file access.
- **Last-known-good**: a failed refresh must never overwrite a working
  subscription. Commit parsed data only after full validation.
- **No fake state**: the UI may only display state that comes from
  `ConnectionManager`'s state machine. No decorative "Connected".

## Build & verify

```powershell
./gradlew assembleDebug     # build
./gradlew test              # unit tests
./gradlew lint              # Android lint
./gradlew connectedDebugAndroidTest  # instrumented tests (needs emulator/device)
```

Verification expectations per change:

- Code must compile and `./gradlew test` + `./gradlew lint` must stay green.
- Parser/protocol changes need fixture tests under `app/src/test/`.
- Anything touching the VPN path should be verified on the emulator
  (`Medium_Phone_API_36.1` AVD exists locally) — see `docs/TESTING.md`.
- Do not claim VPN lifecycle correctness from unit tests alone; note what
  was actually verified on-device in the commit/PR description.

## Conventions

- Kotlin, Jetpack Compose (Material 3), coroutines + `StateFlow`,
  Hilt DI, Room, DataStore, OkHttp, kotlinx.serialization.
- Kotlin DSL build files; versions live in `gradle/libs.versions.toml`.
- The VPN core version is pinned in `gradle/libs.versions.toml`
  (`vpnCore` key) and fetched by `app/build.gradle.kts` with a pinned
  SHA-256 — see `docs/adr/ADR-0001-vpn-core.md`.
- Errors are typed (`VpnError`, `SubscriptionError`), not strings.
- Keep diffs small and vertical; do not scaffold speculative features.

## Git

- Conventional-ish short messages (`vpn: add TUN fd handoff`,
  `subscription: classify base64 bodies`). Imperative, <72 chars.
- Commit working states only; run the verification above first.
