# Google Play listing handoff

Run `node store-listing/validate.mjs` from the repository root before uploading the English, Simplified Chinese, Japanese, and Spanish text and visual assets. The validator checks listing lengths, the complete four-locale screenshot set, Play-compatible dimensions, opaque pixels, and that the map screenshot contains rendered map detail.

The checked-in assets under `store-listing/assets` are generated from the real app on an Android 15 emulator. Recreate them with `node store-listing/capture-screenshots.mjs` after a user-facing UI change; see `store-listing/assets/README.md` for prerequisites and safety constraints.

Recommended initial configuration:

- Product type: App
- Category: Tools
- Ads declaration: No ads
- Target audience: Adults; the product is a developer and QA utility, not a children's app
- Pricing: Free download, with `gps_updater_pro_lifetime` at approximately ¥28 or USD 4.99
- App access: Core features require no account; reviewers need instructions for enabling Developer options and selecting the mock-location app
- Countries: Start with markets supported by the chosen map, geocoding, privacy, tax, and customer-support arrangements

Console items that require the final production providers or publisher identity:

- Public privacy-policy HTTPS URL based on `docs/PRIVACY_POLICY.md`
- Data safety answers for map/geocoding requests, optional encrypted sync, billing, and diagnostics actually used in production
- Publisher legal name, support email, website, and jurisdiction
- Content-rating questionnaire and target-audience declaration
- Internal-test reviewer instructions and a licensed enterprise test path when enterprise features are reviewed
- Internal-track Pro purchase screenshot showing the real localized Play price and purchase state; do not substitute the Debug-build Pro screen
- Any tablet/Chromebook screenshots required for the selected device catalog

Do not describe the product as undetectable, anti-detection, attendance manipulation, game cheating, fraud, or a way to bypass another app's controls. Store copy should remain focused on development, QA, demonstrations, logistics testing, weather/localization scenarios, and authorized device labs.

Supply the final privacy-policy URL to the Android build as `GPS_PRIVACY_POLICY_URL`. Before Play submission, install the internal-track build and confirm that Settings > Privacy & data opens the same URL entered in Play Console.

Supply the monitored Play support address as `GPS_SUPPORT_EMAIL`. Confirm that Settings > About & support displays the Release version and edition and opens an email composer addressed to the same support contact shown in Play Console.
