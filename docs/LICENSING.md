# Licensing

## This project: GPL-3.0

`singbox-android/libbox` is licensed **GPL-3.0** (sing-box itself is also GPL-3.0
with a namesake/attribution clause). Distributing an app that links it requires
GPL-3.0-compatible distribution. This project is an open-source portfolio
project, so that is acceptable — the repository is licensed GPL-3.0.

Implication: this code cannot be shipped closed-source or inside a proprietary
app without replacing the core (the `VpnEngine` boundary exists partly for that).

## Dependency licenses (build-time, informational)

| Dependency                        | License   |
|-----------------------------------|-----------|
| libbox / sing-box                 | GPL-3.0   |
| AndroidX / Compose / Room / etc.  | Apache-2.0|
| Hilt                              | Apache-2.0|
| OkHttp                            | Apache-2.0|
| kotlinx-serialization / coroutines| Apache-2.0|
| SnakeYAML                         | Apache-2.0|
| Turbine, MockWebServer (test)     | Apache-2.0|

## Alternatives noted

Xray-core is GPL-3.0 as well; Mihomo is GPL-3.0. Changing cores does not change
the licensing posture.
