# Privacy Policy Template

Effective date: replace before publication.

Public URL: publish the completed policy at the same HTTPS URL supplied through `GPS_PRIVACY_POLICY_URL` and Play Console.

GPS Locator is an Android development and QA utility. This template must be reviewed, completed with the publisher's legal name/contact information, hosted at a public HTTPS URL, and adapted to the actual production map, geocoding, billing, and cloud providers before release.

## Data handled on the device

The app can access the device's last known location when the user chooses the current-location control. Selected coordinates, saved locations, routes, simulation history, encrypted-sync configuration, purchase entitlement, and enterprise license information are stored locally on the device. Android automatic cloud backup is disabled so purchase, license, and encrypted-sync credentials are not copied by the operating system. From Settings > Privacy & data, users can stop an active simulation and clear selected coordinates, saved locations, routes, history, simulation-run records, and generated Enterprise reports from the device. This action preserves verified Pro ownership, Enterprise licenses, encrypted-sync configuration, remote encrypted backups, and opaque item-deletion markers needed to keep older synced devices from restoring removed items. Users can delete the remote backup separately from the cloud-sync screen before or after clearing local test data. Clearing all app data through Android settings or uninstalling removes the local sync metadata as well.

When a user exports a team template, the app lets the user select saved locations and routes, then writes only those items to a JSON file through Android's system document picker. The selected templates are plaintext so the user can share them with a team. The file does not contain unselected templates, location history, sync server details, encryption keys, deletion markers, purchase data, or Enterprise license data. The app does not upload team template files automatically; the user chooses where to save them and how to share them. Before import, the app previews item and replacement counts and asks for confirmation, then merges the selected file's saved locations and routes into local app storage.

## Network services

Map tile and geocoding requests are sent to the providers configured by the publisher. Those providers can receive the IP address, request time, map tile coordinates, and location search terms under their own privacy policies. Production documentation must name and link the actual providers.

Google Play processes Pro purchases and can provide the app with product, purchase state, purchase token, and acknowledgement status. The app does not receive payment card details.

## Optional encrypted sync

Cloud backup is optional. Before upload, saved locations, routes, history, and deletion markers are encrypted on the device with AES-256-GCM using a key derived from the user's sync key. Deletion markers contain an item type, local item ID, and deletion time so an older device does not recreate removed data; the server cannot read them. The sync service receives an opaque identifier, authorization hash, encrypted payload, revision tag, update time, IP address, and operational request metadata. It does not receive the plaintext sync key or plaintext location data. Users can delete the remote encrypted backup from the app.

The publisher must document the production server location, retention period, subprocessors, backup retention, incident contact, and account/data-deletion process. The provided Caddy baseline removes Authorization and Cookie headers from its access log, but the publisher must re-verify log redaction after any proxy, observability, or hosting change.

## Enterprise testing

Enterprise ADB commands are accepted only from privileged Android shell/system callers and require a signed enterprise license in Release builds. When an administrator chooses per-device licensing, the app reads the Android ID scoped to the app signing key, Android user, and device; it displays the ID for the user to copy, can return it to a privileged ADB shell for first-time provisioning, and verifies it against the ID embedded in the signed license. The app does not upload the Android ID automatically. Test reports contain device/app metadata, location simulation details, and the time-zone ID and UTC offset observed at run start. Reports remain in the app-specific external files directory until the user clears local test data, a test operator removes them, or Android/device-management policy removes them.

## Children's privacy and prohibited use

The product is intended for developers, QA teams, and authorized device testing, not children. It must not be marketed for deception, attendance or game manipulation, fraud, bypassing safety controls, or unauthorized tracking/testing.

## Contact

Replace with publisher legal name, address/jurisdiction where required, privacy email, and data-protection contact before publication.

## Publication check

After hosting the completed policy, build with `GPS_PRIVACY_POLICY_URL` and verify that Settings > Privacy & data opens the exact public URL. Keep the URL stable and update both the app build configuration and Play Console if it must change.
