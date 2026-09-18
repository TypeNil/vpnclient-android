# Remnawave compatibility

Remnawave support is a compatibility layer over the generic subscription
pipeline — nothing Remnawave-specific lives in UI or engine code.

## Request contract

`SubscriptionFetcher.fetch(url, hwid)`:

- `User-Agent: sing-box/1.13.0 (VPNClient; android)` — panels keying on UA return
  a sing-box JSON subscription; panels without UA rules return the default
  Base64 URI list, which the classifier handles anyway. No special `/singbox`
  path is required, but path-suffixed URLs work too (panel decides).
- When HWID is known (see below), sends:
  `x-hwid`, `x-device-os=android`, `x-ver-os=<release>`, `x-device-model=<model>`.

## HWID / device limits

- HWID is generated once per install: a UUID-derived 32-char hex string stored in
  DataStore (`SettingsRepository.getOrCreateHwid()`). It satisfies the panel's
  `^[a-zA-Z0-9=-]{10,64}$` rule. It is **not** a hardware identifier — reinstall
  produces a new device on the panel.
- Panels with device limits answer **404 for both** "unknown subscription" and
  "missing/unregistered HWID". We disambiguate by the presence of `x-hwid-*`
  response headers → `DeviceLimitReached` error surfaced as
  "device limit / HWID rejected".

## Response metadata consumed

| Header                      | Use                                            |
|-----------------------------|------------------------------------------------|
| `subscription-userinfo`     | `upload/download/total` bytes + `expire` epoch → traffic/expiry row |
| `profile-title`             | subscription display name (`base64:`-prefixed values decoded) |
| `announce`                  | decoded for completeness (`base64:` prefix)    |
| `profile-web-page-url` / `support-url` | support link                        |
| `profile-update-interval`   | stored; future auto-refresh cadence (manual refresh only for now) |
| `x-hwid-*`                  | device-limit diagnostics                       |

## Response formats

Remnawave emits, depending on route/UA/rules: Base64 URI lists, sing-box JSON,
Xray JSON, Mihomo/Clash YAML. We parse all except Xray JSON (rejected with a
typed error — the sing-box engine can't consume it; use the sing-box route/UA).

## Caching

No ETag/conditional-fetch contract was found upstream. Refresh is manual;
`profile-update-interval` is recorded for a future scheduler.

## Known gaps

- Encrypted (age) subscriptions: not implemented.
- `xhttp`-transport nodes are skipped (sing-box lacks XHTTP).
- HWID device registration UX (delete-old-device flow) is not implemented —
  a rejected device shows the typed error.
