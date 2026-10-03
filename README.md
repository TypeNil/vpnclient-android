# VPNClient

Android VPN/proxy client built on **sing-box** (libbox). Material 3 + Compose,
Kotlin, coroutines/Flow, Hilt, Room, DataStore.

Targets the Remnawave subscription ecosystem plus generic share-link
subscriptions (VLESS/VMess/Trojan/Shadowsocks/Hysteria2/TUIC).

## Status

Early implementation — see `docs/` for architecture, testing and known limits.
Core decision record: `docs/adr/ADR-0001-vpn-core.md`.

## Build

```bash
./gradlew assembleDebug   # downloads + checksum-verifies libbox.aar first
./gradlew test            # JVM unit tests (parsers, classifier, redaction)
./gradlew lint
```

The libbox AAR is fetched at build time from the pinned `singbox-android/libbox`
GitHub release and verified against a SHA-256 recorded in `app/build.gradle.kts`.
Core version lives in `gradle/libs.versions.toml` as `vpnCore`.

Requirements (from the build files, not from memory):

- **JDK 25 to run Gradle.** `gradle/gradle-daemon-jvm.properties` pins the
  daemon to Java 25 (`toolchainVersion=25`) and `settings.gradle.kts` applies
  the Foojay resolver, so a missing JDK 25 is provisioned automatically. CI
  (`.github/workflows/android-ci.yml`) uses Temurin 25. The compiled bytecode
  targets Java 11 (`compileOptions` in `app/build.gradle.kts`).
- Gradle 9.5.0 (wrapper, checksum-pinned), AGP 9.3.3, Kotlin 2.2.10.
- Android SDK platform 37 (`compileSdk`/`targetSdk` 37), `minSdk` 26.
- Network access on the first build to fetch the pinned libbox AAR.

Instrumented tests: see `docs/TESTING.md`. Never run `connected*` Gradle tasks
on a physical device — they uninstall the app and erase its data.

## Emulator verification

```bash
adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5560 shell am start -n dev.typenil.vpnclient/.MainActivity
```

## Layout

```
app/src/main/java/dev/typenil/vpnclient/
├── core/
│   ├── common/log/          # SecureLog + Redactor (no secrets in logs)
│   ├── engine/              # VpnEngine boundary (no libbox types)
│   │   └── singbox/         # libbox adapter, platform bridge, config compiler
│   ├── subscription/        # fetch → classify → parse pipeline
│   │   └── model/           # ProxyNode, SubscriptionProfile, errors
│   └── vpn/                 # ClientVpnService, ConnectionManager, state machine
├── data/                    # Room db, DataStore settings, NodeConfigProvider
├── di/                      # Hilt modules
└── ui/                      # Compose screens + viewmodels
```

## License

GPL-3.0 — required by the libbox dependency. See `docs/LICENSING.md`.
