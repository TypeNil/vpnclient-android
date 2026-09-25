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
| `profile-update-interval`   | stored; used by WorkManager when provider-following auto-refresh is enabled |
| `x-hwid-*`                  | device-limit diagnostics                       |

## Response formats

Remnawave emits, depending on route/UA/rules: Base64 URI lists, sing-box JSON,
Xray JSON, Mihomo/Clash YAML. We parse all except Xray JSON (rejected with a
typed error — the sing-box engine can't consume it; use the sing-box route/UA).

## Refresh scheduling and caching

Refresh can be manual or scheduled through WorkManager. The effective interval
comes from the user's explicit override, or—when following the provider—from
`profile-update-interval`. If auto-refresh is disabled or neither source gives
an interval, refresh remains manual. Scheduled work requires network
connectivity; WorkManager's minimum interval is 15 minutes. Transient network
and timeout failures receive bounded retries.

The client does not use ETag/Last-Modified conditional requests, so scheduled
refreshes fetch the subscription normally.

## Known gaps

- Encrypted (age) subscriptions: not implemented.
- `xhttp`-transport nodes are skipped (sing-box lacks XHTTP).
- HWID device registration UX (delete-old-device flow) is not implemented —
  a rejected device shows the typed error.
