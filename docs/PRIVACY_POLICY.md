# Privacy Policy Template

Effective date: replace before publication.

GPS Locator is an open-source Android development and QA utility. Complete this template with the publisher's legal name and contact information before hosting it at a public HTTPS URL.

## Data handled on the device

The app can access the device's last known location when the user chooses the current-location control. Selected coordinates, saved locations, routes, simulation history, and optional encrypted-sync configuration are stored locally. Android automatic cloud backup is disabled so sync credentials are not copied by the operating system. From Settings > Privacy & data, users can stop an active simulation and clear selected coordinates, saved locations, routes, history, simulation-run records, and generated reports. Sync configuration and remote encrypted backups are kept unless the user deletes them separately.

When a user exports a team template, the app writes only the selected saved locations and routes to a JSON file through Android's system document picker. The file does not contain history, sync server details, encryption keys, or deletion markers.

## Network services

Map tile and geocoding requests are sent to the providers configured at build time. Those providers can receive the IP address, request time, map tile coordinates, and location search terms under their own privacy policies.

## Optional encrypted sync

Cloud backup is optional. Before upload, saved locations, routes, history, and deletion markers are encrypted on the device with AES-256-GCM. The sync service receives an opaque identifier, authorization hash, encrypted payload, revision tag, update time, IP address, and operational request metadata. It does not receive the plaintext sync key or plaintext location data.

## ADB testing

ADB commands are accepted only from privileged Android shell/system callers. When a signed license is used, the app reads the Android ID scoped to the app signing key, Android user, and device. The app does not upload the Android ID automatically. Test reports remain in the app-specific files directory until cleared.

## Children's privacy and prohibited use

The product is intended for developers, QA teams, and authorized device testing, not children. It must not be used for deception, attendance or game manipulation, fraud, bypassing safety controls, or unauthorized tracking.

## Contact

Replace with publisher legal name, address/jurisdiction where required, and a contact email before publication.
