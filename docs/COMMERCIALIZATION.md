# Commercialization

## Product tiers

| Capability | Free | Pro lifetime | Enterprise |
| --- | --- | --- | --- |
| Map selection, coordinates, region search | Yes | Yes | Yes |
| Saved locations | 5 | Unlimited | Unlimited |
| Groups and saved-location search | No | Yes | Yes |
| Route editor preview | Yes | Yes | Yes |
| Save and run routes, speed, stops, loops | No | Yes | Yes |
| GPX/KML import and export | No | Yes | Yes |
| Import/export saved locations and routes as team templates | No | Yes | Yes |
| History and joystick | No | Yes | Yes |
| Encrypted self-hosted sync | No | Yes | Yes |
| ADB multi-device control, restorable time-zone suites, and reports | No | No | Yes |
| Signed license and private deployment | No | No | Yes |

The Android Debug build grants Pro for testing. Release builds enforce the table through `ProductAccessPolicy`, verified Google Play purchases, and signed Enterprise licenses.

Free users can build and adjust a temporary route in the editor so they can evaluate the workflow. Saving, running, importing, and exporting routes remain Pro actions and show the upgrade flow at the point of intent. Pro and Enterprise users can select saved locations and routes to exchange in versioned JSON team templates. These files intentionally exclude unselected templates, personal history, sync credentials, deletion markers, purchases, and Enterprise licenses.

## Pricing

- Pro lifetime: recommended launch price ¥28 or USD 4.99.
- Enterprise: recommended ¥199-499 per team/month, or an annual/private-deployment agreement.
- A managed hosted sync service can later be sold separately at about ¥8/month or ¥48/year. The current self-hosted encrypted sync does not force a subscription.

The app intentionally contains no ad SDK. Marketing and product copy must focus on development, QA, demos, logistics testing, weather, localization, and authorized device testing. Do not promise anti-detection or advertise attendance, game, financial, or platform-abuse scenarios.

The Pro upgrade screen keeps the lifetime purchase as the primary action and presents Enterprise as a separate QA-team path. Production builds send the Enterprise inquiry action to `GPS_ENTERPRISE_SALES_EMAIL`; when that value is omitted it falls back to `GPS_SUPPORT_EMAIL`. Use a monitored mailbox that can qualify team size, device count, private-deployment needs, and procurement requirements.

## Google Play product

The one-time in-app product ID is:

```text
gps_updater_pro_lifetime
```

Billing Library 9.1 queries the localized Play price, launches the purchase flow, handles pending transactions, acknowledges purchases, and restores ownership. Release builds also reconcile ownership with Google Play when the app starts, so reinstalls restore Pro and successful refund or revocation checks remove local Pro access without requiring the user to reopen the purchase page. Active Enterprise licenses remain active while their fallback tier follows verified Pro ownership. Encrypted sync uses conditional ETag writes and schema-v2 deletion markers to merge simultaneous device backups without silently reviving removed items. A production backend should additionally validate purchase tokens before high-value account/cloud entitlements are issued.

Team template import previews item and replacement counts before asking for confirmation. It then merges by stable item ID: an imported item with the same ID replaces the local template, while unrelated local items remain. Import also removes matching favorite/route deletion markers so a later encrypted sync does not immediately delete the newly imported templates.

## Enterprise delivery

Enterprise builds embed an RSA public key through `GPS_ENTERPRISE_LICENSE_PUBLIC_KEY`. Customer license files are signed offline with the matching private key. Existing schema-v1 licenses cover a team; optional schema-v2 licenses bind the signed entitlement to one Android ID for per-device contracts. The offline tool can issue a validated device batch from the control center's tab-separated serial/ID export. It self-verifies every license and emits a private, RSA-signed delivery manifest with the public-key fingerprint, device mapping, and per-file SHA-256 values without copying the private key. A receiving administrator can independently verify the manifest mapping, file hashes, signatures, and device bindings using only the production public key. The Android settings screen shows the licensed organization, expiration state, and any device binding and supports replacement or renewal. Release builds reject ADB enterprise commands unless a valid, non-expired license is active on the licensed device. The companion CLI supports schema-versioned shared scenario suites, verified IANA time-zone changes with per-device restoration, CI readiness gates, fleet execution, source hashes, per-device outcomes, consolidated JSON reports, and standard JUnit XML results for CI test publishers. The private key must never enter the repository or CI build artifacts.

The Enterprise workstation package includes a loopback-only Web control center over the existing ADB engine. Operators can select any connected-device subset, see each device's installed version, tier, Enterprise license, mock-location readiness, and active-simulation state, send a validated one-off coordinate and optional IANA time zone, validate shared suites, prevent overlapping fleet runs, inspect live output, stop simulations, and review retained reports without constructing CLI commands. Status failures are isolated per serial and do not hide or disable a connected device. A stop waits for the Android foreground service to exit, then automatically retains each selected device's finalized schema-v2 report, a Fleet summary, and CI-readable JUnit; partial evidence collection or a non-final event is preserved as a failed run for diagnosis. JSON and JUnit downloads are read-only, restricted to discovered report IDs, and served with attachment headers. Standalone quick locations intentionally keep their requested time zone active; versioned suites capture and restore each device environment. The same CLI remains the supported headless CI interface. Browser requests cannot select arbitrary files or commands, and state-changing calls require a per-process token and same-origin JSON request.

The private-deployment deliverable includes a single-node Caddy HTTPS Compose stack, an internal-only sync container, writable-storage readiness checks, authorization-header log removal, persistent volumes, and SHA-256-verified backup/empty-volume restore tools. Multi-replica hosting remains a separate engineering engagement because the filesystem implementation uses in-process locks.
