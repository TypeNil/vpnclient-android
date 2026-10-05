# Useful, secret-free diagnostics export (WP-8g)

## Current flow

Diagnostics builds a redacted app/core snapshot and shares a fresh granted URI.
DiagnosticProvider is a read-only memory pipe, not a cache/disk file; process
 death expires both rings and the export. No recipient/chooser is needed to
 verify the pipe through the app's own test logic.

## A — previous core session

Two losses currently occur: CoreLogBuffer.stop clears its 500×512 ring, and
ConnectionManager.detachEngine discards the only engine reference used by export.
Retain only already-redacted bounded lines on guarded detach, not the engine,
config, keys or raw messages. Export labels them “previous session” (en/ru).
Stop still rejects stale callbacks and drops the config-sensitive context;
Redactor.redactCore runs BEFORE ring insertion, so retained text needs no keys.
A new attached engine epoch discards the previous tail; CoreLogBuffer.start
clears its own ring. Process death clears everything. Add a small explicit
“Clear core logs” action in Diagnostics for the current/retained tail.
Native clearLogs and screen-off token guards remain intact.
Limitation: native startup failure before the CommandLog client connects may
have no core messages; retain useful app failure logs, never invent core lines.
An unreachable outbound port can produce core connection errors while local
TUN state remains Connected; do not turn it into a fake app Error.

## B — config context

Keep existing credential/node/server keys, including short credentials.
Add path, service_name, host and ALL nested string values under headers;
propagate sensitive ancestry through objects AND arrays.
New context values need at least 4 non-whitespace characters (trimmed length).
This keeps '/', 'a', empty/blank values from rewriting normal prose globally.
Eligible values are replaced literally, case-insensitively even embedded in
words/URL-like text; longest first. Existing generic URL/address/token rules
still apply. Unlabelled shorter context fragments remain a known ambiguity,
not a claim of complete redaction. Review diagnostics before sharing.

## C — actual health only

Read a synchronized active-session snapshot from ConnectionHealthStore and
project TTL freshness at export time. No active session => “Health: not available”.
For each level export explicit enum level/status/reason/source/scope/freshness
names and checkedAt evidence Instant (or “not available” for unobserved time).
No node, IP probe result, response, endpoint, address, host, config or toString
payload. Ended health is not presented as live; no lifecycle/probe changes.
Previous core lines can coexist with explicitly unavailable current health.

## D — unused resource

Exact source search finds diag_export_empty only in en/ru definitions; remove
both. Keep diag_core_log_empty: no recorded core warnings is still meaningful.
Update SECURITY/ARCHITECTURE's old stop-clears/current-only statements briefly.

## Verification and safety

JVM boundaries: stop retention, epoch clearing, stale callbacks, previous label,
recursive embedded redaction/short negatives, real health/TTL/unavailable/safe fields.
Mutations remove new-session clear, new context keys, or introduce a health address.
Full JVM counts, lint, both APK builds; scoped emulator memory-pipe checks.
CPH2449: baseline, install-r, synthetic loopback success/stop and unreachable-port
errors, read whole export privately and scan known synthetic/owner secrets; no
share/recipient screen. Restore baseline, no connected*/wipe/force-stop/screenshots.
