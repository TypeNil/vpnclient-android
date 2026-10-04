# E-05 — HWID / device-limit audit (read-only)

Audited app logic at `e5cf5f7`; no E-05 product changes. No identifier values collected here.
Goal v1: explain HWID before sending it and clearly explain a device-limit refresh refusal.

## a. Requests and identifier lifetime

- `core/subscription/SubscriptionFetcher.kt:91–130`: always sends `User-Agent: sing-box/<VPN_CORE_VERSION> (VPNClient; android)` (currently core 1.14.1).
- With non-null HWID: `x-hwid`, `x-device-os=android`, `x-ver-os=Build.VERSION.RELEASE`, `x-device-model=Build.MODEL`, `x-app-version=BuildConfig.VERSION_NAME`.
- These device headers survive only exact-origin redirects (scheme/host/port); UA remains on all hops. No HWID header values are logged by this path.
- `SubscriptionRepository.kt:414,1014`: remote add/refresh/periodic/launch refresh and URL edits supply `settings.getOrCreateHwid()` unconditionally, for every provider, not only Remnawave.
- Transport-failure fallback starts a separate fetch with the same HWID, so its new origin receives it too. Manual/share-link imports do not fetch.
- `data/settings/SettingsRepository.kt:124–150`: random UUID generated atomically on first fetch, NOT `Settings.Secure.ANDROID_ID` or a hardware fingerprint.
- Stored in Preferences DataStore `settings` / key `remnawave_hwid` (`files/datastore/settings.preferences_pb`); legacy undashed 32-hex spelling is normalized without changing identity.
- Updates retain it; deleting app data/reinstalling normally removes it and the next fetch creates a new panel device. This is source analysis, NOT an uninstall/clear-data experiment.
- Manifest disables backup; `res/xml/data_extraction_rules.xml` excludes cloud and device transfer. OEM restore behavior was not tested.
- No user-facing display, reset, copy or HWID send switch exists; `hwid` flow has no UI consumer. Do not suggest reinstalling to bypass device limits.

## b. Current response handling and visible texts

`SubscriptionFetcher.kt:211–266`: unsuccessful HTTP responses are mapped before body reads; **any** header name starting `x-hwid` plus HTTP 404 becomes `SubscriptionError.DeviceLimitReached(detail)`.
Values, explicit reasons and contradictory/false flags are NOT interpreted. Other HTTP codes remain `SubscriptionError.Http`.
Successful non-empty responses collect all `x-hwid*` values in `FetchedSubscription.hwidHeaders`, but no downstream consumer uses/persists them.

| Actual outcome | Current subscription card / detail | Current manual-refresh snackbar (en / ru) |
|---|---|---|
| 404 + ANY `x-hwid*` (including active-only, false or unknown flag) | `device limit / HWID rejected` (English in both locales) | `Refresh failed` / `Не удалось обновить` |
| Other HTTP failure (including 404 without hint, 403 or 429) | `HTTP <code>` | Raw host string from `Http.message`, not a localized device-limit explanation |
| 2xx + active / not-supported / max-devices / legacy flag, usable body | Normal successful refresh; last error cleared, no HWID-specific text | No HWID notice |
| 2xx + refusal flag + empty body | `no usable nodes` | `no usable nodes in subscription` (English in both locales) |
| 2xx + refusal flag + non-empty body | Ordinary classifier/parser/engine outcome; no special HWID handling | Ordinary parse/validation failure or no notice on success |

`DeviceLimitReached` is the typed error (not a separate engine state); it has `detail`, but no `message` override, so VM uses the generic snackbar fallback.
Evidence: `SubscriptionRepository.failRefresh/safeMessage:1199–1254`; `SubscriptionsViewModel.postSubscriptionFailure:116–127`; `SubscriptionsScreen:454,646` renders stored `lastError` verbatim.
Failed refresh retains last-known-good nodes. **Gap:** a 2xx refusal with parseable custom-remark nodes may pass validation and replace them; check refusal flags BEFORE parsing/committing.
Existing `docs/REMNAWAVE.md` has stale 32-hex/UA wording; current code is authoritative. No docs outside this audit were edited.

## c. Consent and opting out

- The add/import confirmation explains neither HWID nor device metadata; HTTP opt-in is not HWID consent. No explanation is shown before first transmission.
- No opt-out currently exists. Fetcher accepts `hwid=null`, which suppresses ALL five device headers while retaining UA, but repository does not use that mode.
- A future opt-out means a HWID-enforcing provider can refuse refresh unless its operator exempts the user/disables that limit; keep existing nodes and explain the choice. No reconnect is justified.

## d. Official contract — internet sources checked

- [Official HWID guide](https://docs.rw/features/hwid-device-limit): optional panel feature, off by default; only `x-hwid` required; OS/version/model/UA optional.
- Since panel v3, identifier must match `/^[a-zA-Z0-9=-]{10,64}$/`; invalid input is ignored. Stable app-generated identifier satisfies it; a hardware identifier is not required by this validator.
- Flags: `x-hwid-active=true` = enforcement active, NOT refusal; `x-hwid-not-supported=true` = no accepted identifier; `x-hwid-max-devices-reached=true` = device limit; `x-hwid-limit=true` = legacy compatibility hint.
- Docs explicitly say missing HWID yields 404. **Version caveat:** inspected official backend `b22970cc88481a7e278b5767721672a18f8b2ada` returns a `SubscriptionWithConfigResponse` for HWID refusal, with empty/custom-remark body and flags; public controller sends that as HTTP 200. Unknown subscription uses 404.
- [Pinned backend source](https://github.com/remnawave/backend/tree/b22970cc88481a7e278b5767721672a18f8b2ada/src/modules/subscription): `subscription.service.ts:188–248`, `controllers/subscription.controller.ts:43–61,96–104`; validator: `src/common/utils/extract-hwid-headers/extract-hwid-headers.util.ts`.
- Backend sets legacy `x-hwid-limit` for both missing-id and max-device refusal; prioritize explicit flags. It also upserts supplied HWID when enforcement is disabled. Never infer a device limit from HTTP status or `active` alone.
- Panel administrators can remove old devices; client identifier reset does not release an occupied panel slot. No real panel/account or specific deployed panel version was tested.

## e. Minimal v1 recommendation — NOT implemented

| Change | Estimated changed lines (production + resources, excluding tests) |
|---|---:|
| Typed bounded flag parsing before body/status handling: MaxDevices, MissingOrInvalidHwid, ambiguous legacy rejection; explicit flags outrank legacy, active-only is informational | 45–70 |
| Store fixed safe error tokens in existing `lastError` for shared card/detail localization; recognize old fixed errors too, no schema migration | 20–35 |
| DataStore HWID-send opt-in, default OFF incl. upgrades; central repository gate covers add/refresh/edit/fallback/workers; create ID only after consent | 30–50 |
| Pre-send explanation + allow/continue-without choices; Settings switch and “What is HWID” screen/dialog; workers never prompt or bypass refusal | 60–90 |
| Shared en/ru error rendering + resources for card/detail and refresh/add messages; safe provider-support guidance | 35–55 |

Accept only bounded, recognized true flags; false/unknown/active-only headers must not become a refusal. Conflicting explicit refusal flags need ambiguous guidance.
Tests: ~80–120 lines for flag/status matrix (200/404/403, false/active-only/legacy/contradictory flags), last-known-good preservation and all consent entry points. Split into vertical commits; estimates are not a promised total.
No new dependency, hardware-ID collection, automatic reset, panel API integration or raw-ID display is needed for v1.

| Outcome (proposed) | Text en | Text ru | Placement |
|---|---|---|---|
| Explicit max-device refusal (any status) | Subscription not updated: device limit reached. Ask your provider to remove an old device or raise the limit. Saved servers were kept. | Подписка не обновлена: достигнут лимит устройств. Попросите провайдера удалить старое устройство или увеличить лимит. Сохранённые серверы оставлены. | Card/detail + refresh/add error; add omits saved-servers sentence if none exist |
| Missing/invalid identifier | Subscription not updated: provider requires a valid HWID. Review HWID settings or contact support. | Подписка не обновлена: провайдер требует корректный HWID. Проверьте настройку HWID или обратитесь в поддержку. | Card/detail + refresh/add error |
| HWID sending disabled / permission not granted | HWID sending is off. This provider may refuse updates without it. | Отправка HWID выключена. Этот провайдер может отклонять обновления без него. | Setting + pre-send choice; card/error only when provider refusal is evidenced |
| Legacy-only / contradictory rejection | Provider rejected device identification. A device limit is not confirmed; contact support. | Провайдер отклонил идентификацию устройства. Лимит устройств не подтверждён; обратитесь в поддержку. | Card/detail + refresh/add error |
| Active-only, successful content | Provider uses device identification. | Провайдер использует идентификацию устройства. | Optional subscription detail; NOT an error |
| Unknown HTTP failure, no refusal flags | Subscription not updated: server returned HTTP <code>. | Подписка не обновлена: сервер вернул HTTP <code>. | Card/detail + refresh/add error; no device-limit claim |
| Before any first HWID transmission | HWID is a random identifier for this app installation, not a hardware ID. If allowed, subscription providers receive it with OS/version/model and app metadata and can link requests and count devices. Reinstalling may create another device slot, not free the old one. | HWID — случайный идентификатор этой установки приложения, не аппаратный ID. При разрешении провайдеры подписок получают его вместе с ОС, версией, моделью и данными приложения, могут связывать запросы и считать устройства. Переустановка может занять ещё одно место, а не освободить старое. | “What is HWID” + pre-send explanation; no identifier value |
