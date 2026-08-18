# Contributing

GPS Locator is an open-source Android mock-location / fake GPS tool. Bug reports, translations, and pull requests are welcome.

Please keep the project free: do not add ads, Google Play billing, or paid feature gates.

## Development

- Android Studio Ladybug or newer, JDK 17, Android SDK 35
- Run `./gradlew testDebugUnitTest lintDebug` before opening a pull request
- For CLI or sync-server changes, also run `npm test` in `desktop-cli` or `cloud-server`

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Describe the problem and how you tested the change.
3. Do not commit secrets, keystores, license private keys, or generated APKs.

## Issue reports

Include Android version, device or emulator, GPS Locator version, and steps to reproduce. This project is for authorized development and QA testing only.

