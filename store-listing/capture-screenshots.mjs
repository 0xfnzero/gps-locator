import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const storeRoot = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(storeRoot, "..");
const packageName = "com.gpsupdater.app";
const activity = `${packageName}/.MainActivity`;
const screenshotSize = "1080x1920";
const screenshotRoot = join(storeRoot, "assets", "phone");
const refreshDialogsOnly = process.env.GPS_SCREENSHOT_DIALOGS_ONLY === "1";
const uiDumpPath = "/sdcard/gps-locator-store-ui.xml";

const locales = [
  {
    tag: "en-US",
    appTitle: "GPS Locator",
    regionTitle: "Choose country, city, or region",
    routeName: "Sample delivery route",
    favoritesTitle: "Saved locations",
    favorites: [
      ["Los Angeles checkout", "Login", 34.05223, -118.24368],
      ["New York weather", "Localization", 40.7128, -74.0060],
      ["Seattle delivery", "Delivery", 47.6062, -122.3321],
    ],
  },
  {
    tag: "zh-CN",
    appTitle: "GPS 定位器",
    regionTitle: "选择国家、城市或地区",
    routeName: "示例配送路线",
    favoritesTitle: "收藏位置",
    favorites: [
      ["洛杉矶结账测试", "登录", 34.05223, -118.24368],
      ["纽约天气场景", "本地化", 40.7128, -74.0060],
      ["西雅图配送路线", "配送", 47.6062, -122.3321],
    ],
  },
  {
    tag: "ja-JP",
    appTitle: "GPSロケーター",
    regionTitle: "国、都市、地域を選択",
    routeName: "配送サンプルルート",
    favoritesTitle: "保存した位置",
    favorites: [
      ["ロサンゼルス決済テスト", "ログイン", 34.05223, -118.24368],
      ["ニューヨークの天気", "ローカライズ", 40.7128, -74.0060],
      ["シアトル配送ルート", "配送", 47.6062, -122.3321],
    ],
  },
  {
    tag: "es-ES",
    appTitle: "Localizador GPS",
    regionTitle: "Elegir país, ciudad o región",
    routeName: "Ruta de reparto de ejemplo",
    favoritesTitle: "Ubicaciones guardadas",
    favorites: [
      ["Pago en Los Ángeles", "Inicio de sesión", 34.05223, -118.24368],
      ["Tiempo en Nueva York", "Localización", 40.7128, -74.0060],
      ["Reparto en Seattle", "Entregas", 47.6062, -122.3321],
    ],
  },
];

function executable(name, explicitPath) {
  if (explicitPath) return explicitPath;
  const result = spawnSync("sh", ["-c", `command -v ${name}`], { encoding: "utf8" });
  assert.equal(result.status, 0, `${name} was not found; configure its environment variable`);
  return result.stdout.trim();
}

const androidHome = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
const adbExecutable = executable(
  "adb",
  process.env.ADB || (androidHome ? join(androidHome, "platform-tools", "adb") : ""),
);
const ffmpegExecutable = executable("ffmpeg", process.env.FFMPEG);
const requestedSerial = process.env.GPS_SCREENSHOT_SERIAL?.trim();
let serial = requestedSerial;

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repositoryRoot,
    encoding: options.encoding === undefined ? "utf8" : options.encoding,
    env: options.env || process.env,
    input: options.input,
    maxBuffer: 32 * 1024 * 1024,
    stdio: options.stdio,
  });
  if (result.error) throw result.error;
  assert.equal(
    result.status,
    0,
    `${command} ${args.join(" ")} failed\n${result.stderr || ""}${result.stdout || ""}`,
  );
  return result.stdout;
}

function adb(args, options = {}) {
  const serialArgs = serial ? ["-s", serial] : [];
  return run(adbExecutable, [...serialArgs, ...args], options);
}

function adbText(args, options = {}) {
  return adb(args, { ...options, encoding: "utf8" }).trim();
}

function adbBestEffort(args) {
  const serialArgs = serial ? ["-s", serial] : [];
  spawnSync(adbExecutable, [...serialArgs, ...args], {
    encoding: "utf8",
    maxBuffer: 4 * 1024 * 1024,
  });
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}

function escapeXmlText(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

function favoritePreferences(fixture) {
  const favorites = fixture.favorites.map(([name, group, latitude, longitude], index) => ({
    id: `store-${fixture.tag}-${index + 1}`,
    name,
    group,
    latitude,
    longitude,
    createdAt: fixture.favorites.length - index,
  }));
  const stored = escapeXmlText(JSON.stringify(favorites));
  return `<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n` +
    `<map>\n<string name="favorites">${stored}</string>\n</map>\n`;
}

function seedFavorites(fixture) {
  adbText(["shell", "am", "force-stop", packageName]);
  adbText(["shell", "run-as", packageName, "mkdir", "-p", "shared_prefs"]);
  for (const [file, xml] of Object.entries({
    mock_location: '<map><string name="latitude">34.05223</string><string name="longitude">-118.24368</string><boolean name="active" value="false" /></map>',
    region_directory: '<map><string name="country">US</string><int name="state" value="1416" /><int name="district" value="1619" /><int name="city" value="120784" /></map>',
  })) {
    adb(["shell", "run-as", packageName, "tee", `shared_prefs/${file}.xml`], { input: xml });
  }
  adb(
    ["shell", "run-as", packageName, "tee", "shared_prefs/favorite_locations.xml"],
    { input: favoritePreferences(fixture), encoding: "utf8" },
  );
}

function dumpUi() {
  adbText(["shell", "uiautomator", "dump", uiDumpPath]);
  return adbText(["exec-out", "cat", uiDumpPath]);
}

async function waitForUi(fragment, timeoutMilliseconds = 6_000) {
  const deadline = Date.now() + timeoutMilliseconds;
  let lastDump = "";
  while (Date.now() < deadline) {
    lastDump = dumpUi();
    if (lastDump.includes(fragment)) return;
    await delay(400);
  }
  throw new Error(`UI did not contain ${JSON.stringify(fragment)}\n${lastDump.slice(0, 2_000)}`);
}

async function waitForRegionDirectory() {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    if (!dumpUi().includes(`resource-id="${packageName}:id/progress"`)) return;
    await delay(300);
  }
  throw new Error("Offline region directory did not finish loading");
}

function tap(x, y) {
  adbText(["shell", "input", "tap", String(x), String(y)]);
}

function tapResource(resourceName) {
  const resourceId = `${packageName}:id/${resourceName}`;
  const node = [...dumpUi().matchAll(/<node\b[^>]*>/g)]
    .map((match) => match[0])
    .find((candidate) => candidate.includes(`resource-id="${resourceId}"`));
  assert.ok(node, `Could not find ${resourceId}`);
  const bounds = node.match(/bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"/);
  assert.ok(bounds, `Could not read bounds for ${resourceId}`);
  const [, left, top, right, bottom] = bounds.map(Number);
  tap(Math.round((left + right) / 2), Math.round((top + bottom) / 2));
}

async function backToMain(fixture) {
  adbText(["shell", "input", "keyevent", "4"]);
  await waitForUi(fixture.appTitle);
}

async function capture(outputPath, minimumBytes = 180_000) {
  let image;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    image = adb(["exec-out", "screencap", "-p"], { encoding: null });
    if (image.length >= minimumBytes) break;
    await delay(2_000);
  }
  assert.ok(
    image.length >= minimumBytes,
    `${outputPath} is only ${image.length} bytes; map tiles probably did not load`,
  );
  writeFileSync(outputPath, image);
  console.log(`${outputPath.slice(repositoryRoot.length + 1)}: ${image.length} bytes`);
}

async function captureLocale(fixture) {
  console.log(`\nCapturing ${fixture.tag}`);
  const localeDirectory = join(screenshotRoot, fixture.tag);
  mkdirSync(localeDirectory, { recursive: true });

  adbText([
    "shell",
    "cmd",
    "locale",
    "set-app-locales",
    packageName,
    "--user",
    "0",
    "--locales",
    fixture.tag,
  ]);
  seedFavorites(fixture);
  adbText(["shell", "am", "start", "-W", "-n", activity]);
  await waitForUi(fixture.appTitle);
  // Allow remote map tiles to finish loading before capturing the first screen.
  await delay(refreshDialogsOnly ? 2_000 : 10_000);
  if (!refreshDialogsOnly) await capture(join(localeDirectory, "01-map-selection.png"), 350_000);

  tapResource("regionSearchButton");
  await waitForUi(fixture.regionTitle);
  await waitForRegionDirectory();
  await capture(join(localeDirectory, "02-region-search.png"));
  await backToMain(fixture);

  if (!refreshDialogsOnly) {
  tapResource("favoritesButton");
  await waitForUi(fixture.favoritesTitle);
  await waitForUi(fixture.favorites[0][0]);
  await capture(join(localeDirectory, "03-saved-locations.png"));
  await backToMain(fixture);

  }

  tapResource("routeButton");
  await waitForUi("sampleRouteButton");
  tapResource("sampleRouteButton");
  await waitForUi(fixture.routeName);
  await delay(5_000);
  await capture(join(localeDirectory, "04-route-editor.png"));
  await backToMain(fixture);
}

function selectDevice() {
  const devices = run(adbExecutable, ["devices"], { encoding: "utf8" })
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.split("\t"))
    .filter(([, state]) => state === "device")
    .map(([deviceSerial]) => deviceSerial);
  if (serial) {
    assert.ok(devices.includes(serial), `Device ${serial} is not connected`);
  } else {
    assert.equal(devices.length, 1, "Connect exactly one emulator or set GPS_SCREENSHOT_SERIAL");
    [serial] = devices;
  }
  assert.equal(adbText(["shell", "getprop", "ro.kernel.qemu"]), "1", "Screenshots must run on an emulator");
}

selectDevice();
const originalSizeOutput = adbText(["shell", "wm", "size"]);
const originalOverride = originalSizeOutput.match(/Override size: (\d+x\d+)/)?.[1];
let originalAppLocales = "";
let appInstalled = false;

try {
  const tileUrl = process.env.GPS_SCREENSHOT_TILE_URL_TEMPLATE ||
    "https://tile.openstreetmap.de/{z}/{x}/{y}.png";
  assert.ok(
    ["{z}", "{x}", "{y}"].every((placeholder) => tileUrl.includes(placeholder)),
    "GPS_SCREENSHOT_TILE_URL_TEMPLATE must include {z}, {x}, and {y}",
  );
  run(join(repositoryRoot, "gradlew"), [":app:assembleDebug"], {
    stdio: "inherit",
    env: {
      ...process.env,
      GPS_TILE_URL_TEMPLATE: tileUrl,
      GPS_MAP_ATTRIBUTION: "© OpenStreetMap contributors",
    },
  });
  adbText(["install", "-r", join(repositoryRoot, "app", "build", "outputs", "apk", "debug", "app-debug.apk")]);
  appInstalled = true;
  const localeOutput = adbText([
    "shell",
    "cmd",
    "locale",
    "get-app-locales",
    packageName,
    "--user",
    "0",
  ]);
  originalAppLocales = localeOutput.match(/\[([^\]]*)]/)?.[1]?.trim() || "";
  adbText(["shell", "wm", "size", screenshotSize]);
  assert.ok(adbText(["shell", "wm", "size"]).includes(`Override size: ${screenshotSize}`));
  adbText(["shell", "settings", "put", "global", "development_settings_enabled", "1"]);
  adbText(["shell", "appops", "set", packageName, "android:mock_location", "allow"]);

  for (const fixture of locales) await captureLocale(fixture);

  const featureGraphic = join(storeRoot, "assets", "feature-graphic.png");
  run(
    ffmpegExecutable,
    [
      "-hide_banner",
      "-loglevel",
      "error",
      "-y",
      "-i",
      join(screenshotRoot, "en-US", "01-map-selection.png"),
      "-vf",
      "crop=1024:500:28:400,format=rgb24",
      "-frames:v",
      "1",
      featureGraphic,
    ],
    { stdio: "inherit" },
  );
  console.log(`\nCreated ${featureGraphic.slice(repositoryRoot.length + 1)}`);
} finally {
  if (appInstalled) {
    adbBestEffort(["shell", "am", "force-stop", packageName]);
    const localeArgs = [
      "shell",
      "cmd",
      "locale",
      "set-app-locales",
      packageName,
      "--user",
      "0",
    ];
    if (originalAppLocales) localeArgs.push("--locales", originalAppLocales);
    adbBestEffort(localeArgs);
  }
  if (originalOverride) adbBestEffort(["shell", "wm", "size", originalOverride]);
  else adbBestEffort(["shell", "wm", "size", "reset"]);
}
