# Release checklist

## Android APK

1. Update `versionName` and `versionCode` in `app/build.gradle.kts` when shipping a new version.
2. Run verification:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
(cd desktop-cli && npm test)
(cd cloud-server && npm test)
```

3. Tag the release as `vX.Y.Z` and attach `app/build/outputs/apk/release/app-release.apk`.
4. Optional Play upload still uses `./gradlew bundleRelease` with `GPS_UPLOAD_*` signing secrets.

## Optional build configuration

```bash
GPS_TILE_URL_TEMPLATE='https://tiles.example.com/{z}/{x}/{y}.png'
GPS_GEOCODING_BASE_URL='https://geocode.example.com'
GPS_MAP_ATTRIBUTION='© Your map provider'
GPS_PRIVACY_POLICY_URL='https://example.com/privacy/gps-locator'
GPS_SUPPORT_EMAIL='support@example.com'
GPS_ENTERPRISE_LICENSE_PUBLIC_KEY='<optional Base64 DER RSA public key>'
```

These values are optional. Defaults use public OpenStreetMap tiles and Nominatim. ADB fleet control does not require a signed license.

## Signing

- Local and GitHub APKs fall back to the Android debug keystore when `GPS_UPLOAD_*` is unset.
- Production Play uploads should set `GPS_UPLOAD_STORE_FILE`, `GPS_UPLOAD_STORE_PASSWORD`, `GPS_UPLOAD_KEY_ALIAS`, and `GPS_UPLOAD_KEY_PASSWORD`.
