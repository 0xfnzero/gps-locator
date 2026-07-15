# Google Play visual assets

`phone/<locale>` contains four real Android screenshots for each supported store locale:

1. map and coordinate selection;
2. country, city, and region search;
3. saved-location groups;
4. multi-point route editing.

All phone images are opaque 1080 x 1920 PNG files. `feature-graphic.png` is an opaque
1024 x 500 crop of the real English map screen. The files are generated from the Android
15 emulator by `../capture-screenshots.mjs`; do not manually add captions or mock interface
elements that are not present in the app.

The capture script rejects physical devices, temporarily changes the emulator display size,
uses a screenshot-only OpenStreetMap tile endpoint, and restores the display size and app
language when it finishes. It needs one running API 35 emulator, JDK 17, Android SDK tools,
Node.js, and FFmpeg:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
node store-listing/capture-screenshots.mjs
node store-listing/validate.mjs
```

Set `GPS_SCREENSHOT_SERIAL` when more than one emulator is connected. Override
`GPS_SCREENSHOT_TILE_URL_TEMPLATE` only with a compatible HTTPS template containing
`{z}`, `{x}`, and `{y}`. This value is used solely for the screenshot Debug build and does
not replace the production map configuration.
