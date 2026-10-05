# Background-work hint (WP-8f)

## Platform and Play policy

Research checked against official sources on 2026-10-05:
- [VpnService](https://developer.android.com/reference/android/net/VpnService):
  API 26+ temporarily allowlists background startup; the service must become
  foreground. Our foreground VPN is not immune to process death or OEM limits.
- [Doze/App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby):
  foreground work avoids ordinary App Standby idle classification, not every
  Doze restriction. Allowlisting is partial, not a promise of continuous uptime.
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)):
  `isIgnoringBatteryOptimizations(packageName)` reads the device power allowlist;
  it does not diagnose a disconnect or expose every OEM background restriction.
- [Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS):
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` opens the general list without
  permission. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a direct request
  requiring `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and restricted eligibility.
- [Play Console Help: Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en):
  apps must comply with default Android optimization requirements; ineligible
  allowlisting attempts to bypass power management are a violation. There is
  no blanket VPN approval here. Android's Doze guidance also restricts direct
  exemption requests to cases where core functionality is adversely affected.

Decision: do NOT declare the restricted permission or request direct exemption.
Use only an explicit user click to the resolved general battery-settings screen;
no package exemption URI, OEM activity names, automatic launch or whitelist edits.
Pin the resolved component to avoid a chooser; missing resolution, permission
failure or a disappearing activity produces inline manual guidance, not a crash.
The manifest action query grants visibility only, not a permission.
WP-6b remains binding: restart after `kill -9` on OEM devices is not guaranteed.

## Trigger and persistence

Home shows a nonblocking card only during real `Connected`, when optimization
is known enabled and dismissal is known false. Idle, errors, connecting,
reconnecting, exempt/unknown battery state or unreadable dismissal suppress it.
The first observed successful connection qualifies (including an existing live
session on upgrade). Dismissal is persisted once per installation in DataStore,
not reset per connection or process. Opening settings does not imply dismissal.
A failed write must not pretend dismissal persisted; the card remains available.
Battery status is re-read on resume; no polling service or VPN lifecycle change.
Settings always offers “Background work”, including after dismissal/exemption.
Always-on + lockdown are NOT a suppression gate: restart/blocking behavior is
not immunity to power management; the nullable platform flags cannot prove it.
This is contextual advice, never a statement about a particular failure cause.

## Copy (en / ru)

- Title: “Background work” / «Работа в фоне».
- Body: “On some devices, the system may stop the VPN in the background.
  You can review battery settings. This does not explain a particular
  disconnect, and changing settings may not prevent the VPN from stopping.” /
  «На некоторых устройствах система может остановить VPN в фоне. Можно
  проверить настройки батареи. Это не объясняет конкретный обрыв соединения,
  и изменение настроек может не предотвратить остановку VPN.»
- Action: “Open battery settings” / «Открыть настройки батареи».
- Close: “Dismiss background-work hint” / «Закрыть подсказку о работе в фоне».
- Fallback: “This screen is unavailable. Review this app’s battery options
  manually in system settings.” / «Этот экран недоступен. Проверьте параметры
  батареи для приложения вручную в системных настройках.»

No manufacturer names, uptime promises or invented stopped/connected state.

## Verification

JVM checks cover every connection phase, enabled/exempt/unknown optimization,
dismissal and persisted preference reload; mutations must break the gate/write.
Build both APKs, full JVM tests and lint; scoped emulator instrumentation for
DataStore and UI/intent failure paths. Physical: read-only baseline, `install -r`,
resolve general intent without launching, own-app XML selectors, dismissal and
Settings entry, restore baseline. Never open system battery settings or alter
power/network/always-on/lockdown/whitelist on the owner's phone.
