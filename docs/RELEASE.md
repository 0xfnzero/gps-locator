# Release Checklist

## Android and Google Play

1. Create the app `com.gpsupdater.app` in Play Console.
2. Create a one-time in-app product named `gps_updater_pro_lifetime` and activate it.
3. Configure Play App Signing and a private upload key; do not release the Debug-signed APK.
4. Upload an Android App Bundle to an internal testing track.
5. Install from the testing track and verify localized price, purchase, pending purchase, acknowledgement, reinstall, startup ownership restoration, refund/revocation downgrade, and manual restore.
6. Complete Data safety and privacy disclosures for location history, encrypted backup, and network map/geocoding requests.
7. Complete `docs/PRIVACY_POLICY.md`, publish it at a stable public HTTPS URL, and set the same URL in Play Console and `GPS_PRIVACY_POLICY_URL`.
8. Verify Settings > Privacy & data opens the published policy from the internal-track build.
9. Seed saved locations, routes, history, selected coordinates, and an Enterprise report; run Settings > Privacy & data > Clear local test data and verify those records are removed while Pro ownership, Enterprise licensing, sync configuration, and any remote encrypted backup remain available.
10. From Settings > Team templates, select a subset of saved locations and routes, export it, and verify unselected items are absent. Import it on a second test device, verify the replacement preview before confirming, then verify same-ID replacement, unrelated-item preservation, and that history and sync settings do not move with the file.
11. Set `GPS_SUPPORT_EMAIL` to the same monitored support address used in Play Console and verify Settings > About & support opens an email app.
12. Set `GPS_ENTERPRISE_SALES_EMAIL` to a monitored sales address, or intentionally use its support-email fallback, and verify the Pro page's Enterprise inquiry opens an email draft with the localized subject.
13. Clearly describe the developer-option mock-location requirement and the development/QA purpose during policy review.
14. Regenerate real localized screenshots after UI changes with `node store-listing/capture-screenshots.mjs`, then validate all text and visual assets with `node store-listing/validate.mjs`.
15. Capture the Pro purchase screen from the Play internal-track build so it contains the real localized price and purchase state, then add publisher details.

Configure upload signing through CI environment variables. Keep all four values in the CI secret store and use an absolute keystore path:

- `GPS_UPLOAD_STORE_FILE`
- `GPS_UPLOAD_STORE_PASSWORD`
- `GPS_UPLOAD_KEY_ALIAS`
- `GPS_UPLOAD_KEY_PASSWORD`

## Map and geocoding

Do not operate a commercial release at scale against the default public OpenStreetMap tile or Nominatim endpoints. Configure these CI variables:

- `GPS_TILE_URL_TEMPLATE`
- `GPS_GEOCODING_BASE_URL`
- `GPS_MAP_ATTRIBUTION`
- `GPS_PRIVACY_POLICY_URL`
- `GPS_SUPPORT_EMAIL`
- `GPS_ENTERPRISE_SALES_EMAIL`
- `GPS_ENTERPRISE_LICENSE_PUBLIC_KEY`

The tile URL must contain `{z}`, `{x}`, and `{y}` placeholders. The geocoding URL is the service base URL; the app appends `/search`.

Set `GPS_COMMERCIAL_RELEASE=true` for production builds. The Release build then fails before packaging if upload signing is incomplete, the privacy-policy URL is not public HTTPS, support or Enterprise sales email is invalid/placeholder, the Enterprise public key is not a valid RSA key of at least 2048 bits, endpoints are not HTTPS, the tile template is malformed, example/test/local hosts are present, or a public OpenStreetMap tile/Nominatim host is still configured. Host checks are case-insensitive and reject alternate public tile subdomains and URL path variants. Private HTTPS map/geocoding hosts remain valid for contracted private deployments, but the privacy policy must be publicly reachable. `GPS_ENTERPRISE_SALES_EMAIL` falls back to `GPS_SUPPORT_EMAIL` when omitted.

## Cloud sync

Deploy `cloud-server` behind HTTPS with a persistent `/data` volume, backups, monitoring, request logs that exclude authorization headers/bodies, and an appropriate retention/deletion policy. The single-node package in `cloud-server/deploy` supplies a hardened Caddy Compose baseline; validate its Caddyfile and images in the production CI environment. Test public `/health`, internal writable-storage `/ready`, and confirm public `/ready` returns 404. Then test upload, cross-device restore, invalid key, unauthorized access, deletion, payload limit, and rate limiting. Create an archive with the tool profile, restore it into a new empty-volume project, and complete an application-level recovery drill before launch. Run simultaneous backups from two devices against the same revision and verify one conditional write retries rather than overwriting the other. Delete a favorite, route, and history entry on one device, sync an older offline device, and verify encrypted tombstones prevent all three items from reappearing.

## Enterprise

Generate the enterprise RSA key outside the repository. Set only the Base64 DER public key in `GPS_ENTERPRISE_LICENSE_PUBLIC_KEY`; commercial release validation requires an RSA key of at least 2048 bits. Read the app-scoped Android ID with `desktop-cli/gps-lab.mjs device-id`, issue team or device-bound customer licenses with `desktop-cli/license-tool.mjs`, deploy the sync server privately when required, and validate license import, wrong-device rejection, expiration, renewal, and ADB commands on every supported Android/OEM version. For a device batch, copy the selected serial/ID rows from the control center, run the batch issuer, independently run `gps-license --verify-manifest ... --public-key-file ...`, retain the signed manifest in the customer delivery record, and confirm an existing output file makes reissuance fail instead of overwriting evidence. The independent check must fail for a wrong public key, modified serial mapping, unsafe file path, modified license document, or invalid device binding. Run `gps-lab status --serial all --require-ready` and verify the app version, build type, tier, license, mock-location readiness, active-simulation state, readiness code, and CI exit status for every target, including isolated output for an authorized device where the app is absent. Run `npm run validate:examples` in `desktop-cli` so all versioned team suite templates compile without requiring a connected device. Start `npm run control-center`, verify that it remains loopback-only, confirm all readiness states and the selected-device warning render without disabling device selection, select a strict subset from at least three devices, use Quick location with and without an IANA time zone, then stop it and confirm its `fleet-summary.json`, `fleet-results.junit.xml`, and each selected `devices/*.json` appear under Recent runs with the finalized event outcome. Open the report and verify both JSON and JUnit downloads. Validate a suite, run it, inspect live output and the retained result. Execute one successful and one intentionally failing fleet suite, publish `suite-results.junit.xml` in the target CI system, and retain the JSON summary and per-device reports as diagnostic artifacts. On each supported OEM, run a suite with at least two IANA time zones and verify both the report's requested values and restoration of the exact pre-suite device time zone.

## Verification commands

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
./gradlew connectedDebugAndroidTest
(cd cloud-server && npm test)
(cd cloud-server && npm run validate:deployment)
(cd desktop-cli && npm test)
(cd desktop-cli && npm run validate:examples)
```

Production CI should run `GPS_COMMERCIAL_RELEASE=true ./gradlew bundleRelease` with the map, privacy-policy, support/sales email, and signing variables above.

The Release APK/AAB still requires the external store product, production service endpoints, completed hosted privacy policy, an internal-track purchase screenshot, publisher details, and signing credentials before publication.
