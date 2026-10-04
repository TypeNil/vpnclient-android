# Remnawave compatibility

Remnawave support is a compatibility layer over the generic subscription
pipeline. The engine is unaffected; UI explains consent and typed refusals.

## Request contract

`SubscriptionFetcher.fetch(url, hwid)`:

- `User-Agent: sing-box/<pinned VPN_CORE_VERSION> (VPNClient; android)` — the
  version comes from `gradle/libs.versions.toml`; panels keying on UA return
  a sing-box JSON subscription; panels without UA rules return the default
  Base64 URI list, which the classifier handles anyway. No special `/singbox`
  path is required, but path-suffixed URLs work too (panel decides).
- Only with Allowed consent (see below), sends:
  `x-hwid`, `x-device-os=android`, `x-ver-os=<release>`,
  `x-device-model=<model>`, `x-app-version=<VERSION_NAME>`.
  Unset/Denied omit all five device headers; UA remains unchanged.
- Redirects retain device headers only on the exact request origin
  (scheme/host/port). A transport fallback is a separate request and re-reads
  consent, including when its origin differs.

## HWID / device limits

- HWID is a random dashed UUID stored in DataStore, **not** a hardware ID.
  A new installation starts Unset and creates it only after Allowed consent.
  A legacy `remnawave_hwid` key with no consent key is grandfathered to Allowed;
  an explicit Denied is never overridden. Legacy undashed hex is normalized
  without changing identity; disabling transmission retains the saved ID.
- Remote add/import asks once before fetching when Unset: Allow or Continue
  without HWID. Settings has a send toggle and the same explanation under
  “What is HWID”; no ID display/copy/reset. Background/manual/launch/edit and
  fallback fetches all obey the same gate without background prompts.
- Providers receive the ID with OS/version/model and app metadata and may link
  requests and count devices. Reinstalling may occupy another slot, not free
  the old one. Ask the provider to remove old devices or raise the limit.
- Refusal flags are checked before status/body for **any status**, including 200.
  Only exact trimmed case-insensitive `true` values are accepted (bounded to
  16 characters and the first 16 values). Duplicate true wins.
  `x-hwid-max-devices-reached` means max devices; `x-hwid-not-supported`
  means missing/invalid ID; both true means ambiguous. Explicit flags beat
  legacy-only `x-hwid-limit` (ambiguous); `x-hwid-active` is informational.
  False/unknown/active-only values are not refusals; 404 without flags is HTTP.
- Refusal bodies are never parsed/committed, even valid remark nodes in 200.
  Last-known-good nodes stay intact. Fixed safe `sub:hwid:*` / `sub:http:<code>`
  tokens use shared en/ru rendering across card/detail/add/refresh errors;
  old fixed errors remain recognized, without a database schema migration.
  The saved-servers suffix appears only when saved nodes exist.

## Response metadata consumed

| Header                      | Use                                            |
|-----------------------------|------------------------------------------------|
| `subscription-userinfo`     | `upload/download/total` bytes + `expire` epoch → traffic/expiry row |
| `profile-title`             | subscription display name (`base64:`-prefixed values decoded) |
| `announce`                  | decoded for completeness (`base64:` prefix)    |
| `profile-web-page-url` / `support-url` | support link                        |
| `profile-update-interval`   | stored; used by WorkManager when provider-following auto-refresh is enabled |
| `x-hwid-max-devices-reached` / `x-hwid-not-supported` | typed refusal before status/body |
| `x-hwid-limit` / `x-hwid-active` | legacy ambiguous refusal / informational only |

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
