# GPS Locator

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208%2B-3DDC84.svg)](https://github.com/0xfnzero/gps-locator/releases)
[![API](https://img.shields.io/badge/API-26%2B-blue.svg)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF.svg)](https://kotlinlang.org/)
[![Release](https://img.shields.io/github/v/release/0xfnzero/gps-locator)](https://github.com/0xfnzero/gps-locator/releases)

Open-source **Android mock location** / **fake GPS** tool for developers and QA. Simulate a single point, search 200+ countries, replay GPX/KML routes, sync encrypted backups, and drive a device fleet over ADB.

[中文说明](README.zh-CN.md) · [Releases](https://github.com/0xfnzero/gps-locator/releases) · [Contributing](CONTRIBUTING.md)

## Why GPS Locator

GPS Locator is a developer mock GPS app, not a commercial fake-location product. Every feature is free and included in the source:

- OpenStreetMap map picker with tap, drag, zoom, and coordinate input
- Country / city / district search across 200+ regions
- Saved locations, groups, history, and meter-level joystick
- Multi-point route simulation with speed, stops, and loops
- GPX / KML import and export, plus team template JSON
- Optional end-to-end encrypted cloud backup
- ADB CLI and local control center for multi-device QA

Use it to test maps, logistics, weather, localization, and other location-aware apps on devices you are authorized to test. It does not hide Android mock-location state or bypass another app’s security checks.

## Install

Download the latest APK from [GitHub Releases](https://github.com/0xfnzero/gps-locator/releases).

1. Enable Developer options on the Android device.
2. Open **Select mock location app** and choose **GPS Locator**.
3. Grant location and notification permissions, then start a simulation.

If the app does not appear in the mock-location list, install 2.0.0 or newer, launch it once, then leave and re-enter Developer options.

## Build from source

Requires Android Studio Ladybug+, JDK 17, and Android SDK 35.

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. Without `GPS_UPLOAD_*` secrets the Release build is signed with the Android debug key so you can install it locally.

```bash
./gradlew testDebugUnitTest lintDebug
(cd desktop-cli && npm test)
(cd cloud-server && npm test)
```

## Optional services

| Path | What it is |
| --- | --- |
| [`desktop-cli`](desktop-cli/README.md) | ADB lab CLI, local web control center, scenario suites, JSON/JUnit reports |
| [`cloud-server`](cloud-server/README.md) | Self-hosted encrypted backup server with Caddy HTTPS deploy pack |
| [`store-listing`](store-listing/README.md) | Optional Play Store listing copy and screenshot tools |
| [`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md) | Privacy policy template |

Map tiles default to public OpenStreetMap / Nominatim. For heavier use, point the build at your own tile and geocoding endpoints:

```bash
GPS_TILE_URL_TEMPLATE='https://tiles.example.com/{z}/{x}/{y}.png' \
GPS_GEOCODING_BASE_URL='https://geocode.example.com' \
GPS_MAP_ATTRIBUTION='© Your map provider' \
GPS_PRIVACY_POLICY_URL='https://example.com/privacy/gps-locator' \
GPS_SUPPORT_EMAIL='support@example.com' \
./gradlew assembleRelease
```

## Topics

`android` `gps` `mock-location` `fake-gps` `location-simulator` `qa` `testing` `adb` `openstreetmap` `gpx` `kml` `kotlin`

## License

[MIT](LICENSE)
