# Automatic-start failure notice (WP-8h)

Problem: a failed automatic start (system always-on, boot/update restore, sticky restart)
stops the service quietly; the user cannot tell why the VPN is down, and under lockdown
the network may be stranded. A manual start already shows an Error in the UI: no notice.

## Decisions

- Channel: reuse `vpn_alerts` (HIGH). A LOW channel gives no heads-up and would keep the
  failure silent; restart-guard and expiry alerts already live there. No new setting.
- One ordinary (non-foreground) notification, one id (`ALERT_NOTIFICATION_ID`): a newer
  notice replaces it (`onlyAlertOnce`), tap opens the app, autoCancel, cleared by the next
  successful start of any origin.
- Text depends on the kind only (fixed en/ru strings; no host, node name, config value or
  exception detail): NoServer, VpnPermission, Unencrypted, InvalidConfig, StartFailed
  (EngineFailed, TunnelFailed, Unexpected and any other error).
- API 33+ without POST_NOTIFICATIONS: nothing shown, nothing throws, one SecureLog line
  with the kind name only. The in-app state stays the source of truth.
- Pure `autoStartNotice(branch, error)` decides; a narrow sink posts/cancels. The service
  posts before `failStart` (it converges quietly when desired=false), only while the
  attempt still owns the service and no stop or connect is pending.

## AutomaticStartPolicy branches

| Branch | Reached when | Notice |
| --- | --- | --- |
| AlwaysOn | system request, consent and selection present | none itself; later failures below |
| Restore | null/boot/update intent, desired=true | none itself; later failures below |
| Stop | Restore with desired=false or unreadable store; Stray | never: nothing was requested |
| MissingPrerequisites | always-on without consent or selection | VpnPermission / NoServer |

## Where the service ends an automatic start

| Place | Notice |
| --- | --- |
| compile NoNodes (always-on, restore) | NoServer |
| compile Failed (unencrypted, invalid config, rule sets, other) | by error type |
| always-on selection differs from the compiled node | NoServer |
| launchEngine throws (TUN, native start) | by error type |
| restart guard tripped | keep its own existing alert |
| superseded by a connect, live session owns it, stop/destroy raced | never: the newer owner decides |
| onRevoke, rebuild failure, node set emptied | never: a running session ended, not a start |
| BootReceiver: desired but consent missing | VpnPermission |
| BootReceiver: startForegroundService throws | StartFailed |
| manual connect, any failure | never: UI Error / selectionError |

## WP-8g log follow-ups

- Native lines already begin with a level word: drop our duplicate label ("ERROR ERROR").
- Detach copies the tail once: prove late lines are not lost, else fix minimally; result in the commit.
