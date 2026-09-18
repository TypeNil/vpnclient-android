# Testing

## Commands

```bash
./gradlew test            # JVM unit tests
./gradlew lint            # Android lint
./gradlew assembleDebug   # full build incl. libbox fetch+checksum
```

## Unit test coverage (JVM)

- `UriListParserTest` — VLESS(+REALITY/WS), VMess, Trojan, SS (3 forms),
  Hysteria2, TUIC; malformed lines skipped; xhttp skipped; stable node ids;
  fragment decoding.
- `SingBoxJsonParserTest` — node outbounds extracted, `direct`/`block`/`dns`/
  `selector`/`urltest` skipped, tag→name mapping.
- `ClashYamlParserTest` — proxies mapped, unsupported types skipped.
- `SubscriptionClassifierTest` — plain/base64/JSON/YAML detection, unknown →
  typed error, empty → `EmptyResult`.
- `SubscriptionFetcherTest` (MockWebServer) — metadata header parsing
  (`subscription-userinfo`, `base64:` profile-title), HTTP error mapping,
  size cap, UA/HWID request headers.
- `RedactorTest` — UUID/secret redaction in URLs and tokens.
- `ConnectionManager`/state tests — transition rules with a fake config provider.

## On-device (emulator-5560)

```bash
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 shell am start -n dev.typenil.vpnclient/.MainActivity
```

Manual checklist: app starts → add subscription → nodes listed → select node →
VPN consent → connect → notification shows state → disconnect cleanly.

A real connection additionally needs a working subscription/server — unit tests
cover everything up to `VpnService.prepare`; the TUN path is verified on the
emulator.
