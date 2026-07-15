import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { afterEach, test } from "node:test";

const testDirectory = dirname(fileURLToPath(import.meta.url));
const cli = resolve(testDirectory, "../gps-lab.mjs");
const temporaryDirectories = [];

afterEach(() => {
  while (temporaryDirectories.length > 0) {
    rmSync(temporaryDirectories.pop(), { recursive: true, force: true });
  }
});

test("set foregrounds the app and preserves spaces and international labels", () => {
  const harness = createAdbHarness();
  const result = runCli(harness, [
    "set", "--serial", "emulator-5554", "--lat", "31.2304", "--lon", "121.4737",
    "--name", "Shanghai QA / 上海"
  ]);

  assert.equal(result.status, 0, result.stderr);
  const calls = harness.calls();
  assert.deepEqual(calls[1], [
    "-s", "emulator-5554", "shell", "am", "start", "-W", "-n",
    "com.gpsupdater.app/.MainActivity"
  ]);
  const broadcast = calls[2];
  assert.ok(broadcast.includes("com.gpsupdater.app/.enterprise.EnterpriseControlReceiver"));
  assert.equal(broadcast.includes("name"), false);
  const encodedName = broadcast[broadcast.indexOf("name_base64") + 1];
  assert.equal(Buffer.from(encodedName, "base64url").toString("utf8"), "Shanghai QA / 上海");
});

test("set applies and verifies an optional IANA time zone before location delivery", () => {
  const harness = createAdbHarness();
  const result = runCli(harness, [
    "set", "--serial", "emulator-5554", "--lat", "40.757955", "--lon", "-73.985532",
    "--name", "New York QA", "--time-zone", "America/New_York",
  ]);

  assert.equal(result.status, 0, result.stderr);
  const calls = harness.calls();
  const timeZoneCall = calls.find((call) => call.includes("set-timezone"));
  assert.equal(timeZoneCall[timeZoneCall.indexOf("set-timezone") + 1], "America/New_York");
  const foregroundIndex = calls.findIndex((call) => call.includes("com.gpsupdater.app/.MainActivity"));
  const timeZoneIndex = calls.findIndex((call) => call.includes("set-timezone"));
  assert.ok(timeZoneIndex > 0 && timeZoneIndex < foregroundIndex);
  assert.match(result.stdout, /timeZone=America\/New_York/);
});

test("a receiver error makes the CLI command fail", () => {
  const harness = createAdbHarness("4", "invalid_coordinates");
  const result = runCli(harness, [
    "set", "--serial", "emulator-5554", "--lat", "31", "--lon", "121"
  ]);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /result 4 \(invalid_coordinates\)/);
});

test("an invalid static location fails before any device is changed", () => {
  const harness = createAdbHarness();
  const result = runCli(harness, [
    "set", "--serial", "emulator-5554", "--lat", "91", "--lon", "121"
  ]);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /latitude is out of range/);
  assert.deepEqual(harness.calls(), [["devices"]]);
});

test("a comma-separated serial list targets only the selected device subset", () => {
  const harness = createAdbHarness();
  harness.setDevices(["emulator-5554", "qa-phone-01", "qa-phone-02"]);
  const result = runCli(harness, [
    "set", "--serial", "emulator-5554,qa-phone-02",
    "--lat", "31.2304", "--lon", "121.4737",
  ]);

  assert.equal(result.status, 0, result.stderr);
  const foregrounded = harness.calls()
    .filter((call) => call.includes("com.gpsupdater.app/.MainActivity"))
    .map((call) => call[1]);
  assert.deepEqual(foregrounded, ["emulator-5554", "qa-phone-02"]);
});

test("device-id reads the app-scoped ID without foregrounding the app", () => {
  const harness = createAdbHarness("1", "a1b2c3d4e5f60718");
  const result = runCli(harness, [
    "device-id", "--serial", "emulator-5554", "--plain"
  ]);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), "a1b2c3d4e5f60718");
  const calls = harness.calls();
  assert.equal(calls.length, 2);
  assert.equal(calls[1].includes("am"), true);
  assert.equal(calls[1].includes("start"), false);
  assert.equal(calls[1].includes("com.gpsupdater.app.enterprise.GET_DEVICE_ID"), true);
});

test("status returns decoded JSON for every device without foregrounding the app", () => {
  const status = {
    schemaVersion: 1,
    applicationId: "com.gpsupdater.app",
    appVersion: "2.0.0",
    versionCode: 4,
    debug: true,
    tier: "pro",
    deviceId: "a1b2c3d4e5f60718",
    mockLocationReady: true,
    simulationActive: false,
    enterprise: { organization: "", expiresAt: 0, active: false, deviceBound: false },
  };
  const harness = createAdbHarness("1", Buffer.from(JSON.stringify(status)).toString("base64url"));
  harness.setDevices(["emulator-5554", "qa-phone-01"]);
  const result = runCli(harness, ["status", "--serial", "all", "--require-ready"]);

  assert.equal(result.status, 0, result.stderr);
  const records = result.stdout.trim().split("\n").map(JSON.parse);
  assert.deepEqual(records.map((record) => record.serial), ["emulator-5554", "qa-phone-01"]);
  assert.ok(records.every((record) => record.available));
  assert.ok(records.every((record) => record.commandReady));
  assert.ok(records.every((record) => record.readiness === "ready"));
  assert.ok(records.every((record) => record.appVersion === "2.0.0"));
  assert.equal(harness.calls().some((call) => call.includes("am") && call.includes("start")), false);
});

test("status isolates a receiver failure to the affected device", () => {
  const status = {
    schemaVersion: 1,
    applicationId: "com.gpsupdater.app",
    appVersion: "2.0.0",
    versionCode: 4,
    debug: false,
    tier: "enterprise",
    deviceId: "device-id",
    mockLocationReady: true,
    simulationActive: true,
    enterprise: { organization: "QA", expiresAt: 0, active: true, deviceBound: true },
  };
  const harness = createAdbHarness("1", Buffer.from(JSON.stringify(status)).toString("base64url"));
  harness.setDevices(["emulator-5554", "qa-phone-01"]);
  harness.failStatusFor("qa-phone-01");
  const result = runCli(harness, ["status", "--serial", "all"]);

  assert.equal(result.status, 0, result.stderr);
  const records = result.stdout.trim().split("\n").map(JSON.parse);
  assert.equal(records[0].available, true);
  assert.equal(records[1].available, false);
  assert.match(records[1].error, /status_unavailable/);
  assert.equal(records[1].commandReady, false);
  assert.equal(records[1].readiness, "status_unavailable");
});

test("status readiness gate emits every record before returning nonzero", () => {
  const status = {
    schemaVersion: 1,
    applicationId: "com.gpsupdater.app",
    appVersion: "2.0.0",
    versionCode: 4,
    debug: true,
    tier: "pro",
    deviceId: "device-id",
    mockLocationReady: false,
    simulationActive: false,
    enterprise: { organization: "", expiresAt: 0, active: false, deviceBound: false },
  };
  const harness = createAdbHarness("1", Buffer.from(JSON.stringify(status)).toString("base64url"));
  harness.setDevices(["emulator-5554", "qa-phone-01"]);
  const result = runCli(harness, ["status", "--serial", "all", "--require-ready"]);

  assert.equal(result.status, 1);
  const records = result.stdout.trim().split("\n").map(JSON.parse);
  assert.equal(records.length, 2);
  assert.ok(records.every((record) => record.commandReady === false));
  assert.ok(records.every((record) => record.readiness === "setup_required"));
  assert.match(result.stderr, /2 of 2 selected devices are not ready/);
});

test("report creates its output directory", () => {
  const harness = createAdbHarness();
  const output = join(harness.directory, "nested", "reports");
  const result = runCli(harness, [
    "report", "--serial", "emulator-5554", "--output", output
  ]);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(existsSync(output), true);
});

test("stop confirms the mock location service has exited before succeeding", () => {
  const harness = createAdbHarness();
  const result = runCli(harness, ["stop", "--serial", "emulator-5554"]);

  assert.equal(result.status, 0, result.stderr);
  const calls = harness.calls();
  const stopBroadcastIndex = calls.findIndex((call) => (
    call.includes("com.gpsupdater.app.enterprise.STOP")
  ));
  const serviceCheckIndex = calls.findIndex((call) => (
    call.includes("dumpsys") && call.includes("com.gpsupdater.app/.location.MockLocationService")
  ));
  assert.ok(stopBroadcastIndex > 0 && serviceCheckIndex > stopBroadcastIndex);
});

test("suite dry-run validates shared templates without calling ADB", () => {
  const harness = createAdbHarness();
  const fixture = createSuiteFixture(harness.directory);
  const result = runCli(harness, ["suite", "--file", fixture.suite, "--dry-run"]);

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Suite valid: Global release smoke \(2 steps\)/);
  assert.deepEqual(harness.calls(), []);
});

test("suite rejects any invalid step before connecting to devices", () => {
  const harness = createAdbHarness();
  const fixture = createSuiteFixture(harness.directory);
  writeFileSync(fixture.route, JSON.stringify({
    type: "route",
    name: "Invalid",
    waypoints: [
      { latitude: 31, longitude: 121 },
      { latitude: 31, longitude: 121 },
    ],
  }));
  const result = runCli(harness, ["suite", "--file", fixture.suite]);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /distinct coordinates/);
  assert.deepEqual(harness.calls(), []);
});

test("suite executes every step and writes consolidated device results", () => {
  const harness = createAdbHarness();
  const fixture = createSuiteFixture(harness.directory);
  const output = join(harness.directory, "suite-output");
  const result = runCli(harness, [
    "suite", "--file", fixture.suite, "--serial", "all", "--output", output,
  ]);

  assert.equal(result.status, 0, result.stderr);
  const summary = JSON.parse(readFileSync(join(output, "suite-summary.json"), "utf8"));
  const junit = readFileSync(join(output, "suite-results.junit.xml"), "utf8");
  assert.equal(summary.outcome, "completed");
  assert.equal(summary.steps.length, 2);
  assert.match(summary.suite.sha256, /^[a-f0-9]{64}$/);
  assert.match(summary.steps[0].scenarioSha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(summary.steps.map((step) => step.devices[0].outcome), ["completed", "completed"]);
  assert.equal(summary.deviceReports[0].outcome, "pulled");
  assert.ok(
    harness.calls().some((call) => call.some((argument) => argument.endsWith("WRITE_REPORT"))),
  );
  assert.ok(harness.calls().some((call) => call.includes("pull")));
  assert.match(junit, /tests="3" failures="0"/);
  assert.match(junit, /name="us-login \[emulator-5554\]"/);
  assert.match(junit, /name="device report \[emulator-5554\]"/);
});

test("suite writes a failure summary before returning a nonzero status", () => {
  const harness = createAdbHarness("4", "license_required");
  const fixture = createSuiteFixture(harness.directory);
  const output = join(harness.directory, "failed-suite");
  const result = runCli(harness, ["suite", "--file", fixture.suite, "--output", output]);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /Suite failed; summary:/);
  const summary = JSON.parse(readFileSync(join(output, "suite-summary.json"), "utf8"));
  const junit = readFileSync(join(output, "suite-results.junit.xml"), "utf8");
  assert.equal(summary.outcome, "failed");
  assert.equal(summary.steps[0].devices[0].outcome, "failed");
  assert.match(summary.steps[0].devices[0].error, /license_required/);
  assert.match(junit, /failures="3"/);
  assert.match(junit, /<failure type="GpsLabFailure"/);
  assert.match(junit, /license_required/);
});

test("suite applies scenario time zones and restores each device environment", () => {
  const harness = createAdbHarness();
  const fixture = createSuiteFixture(harness.directory, { timeZones: true });
  const output = join(harness.directory, "time-zone-suite");
  const result = runCli(harness, ["suite", "--file", fixture.suite, "--output", output]);

  assert.equal(result.status, 0, result.stderr);
  const summary = JSON.parse(readFileSync(join(output, "suite-summary.json"), "utf8"));
  const junit = readFileSync(join(output, "suite-results.junit.xml"), "utf8");
  assert.deepEqual(summary.steps.map((step) => step.timeZone), [
    "America/New_York",
    "Asia/Shanghai",
  ]);
  assert.deepEqual(summary.deviceEnvironments, [
    {
      serial: "emulator-5554",
      originalTimeZone: "Etc/UTC",
      requestedTimeZones: ["America/New_York", "Asia/Shanghai"],
      currentTimeZone: "Etc/UTC",
      restorationOutcome: "restored",
    },
  ]);
  const requestedTimeZones = harness.calls()
    .filter((call) => call.includes("set-timezone"))
    .map((call) => call[call.indexOf("set-timezone") + 1]);
  assert.deepEqual(requestedTimeZones, ["America/New_York", "Asia/Shanghai", "Etc/UTC"]);
  assert.match(junit, /tests="4" failures="0"/);
  assert.match(junit, /name="device environment \[emulator-5554\]"/);
  assert.match(junit, /restorationOutcome=restored/);
});

test("suite reports a failed device time-zone restoration", () => {
  const harness = createAdbHarness("1", "started", "Etc/UTC");
  const fixture = createSuiteFixture(harness.directory, { timeZones: true });
  const output = join(harness.directory, "failed-restoration");
  const result = runCli(harness, ["suite", "--file", fixture.suite, "--output", output]);

  assert.equal(result.status, 1);
  const summary = JSON.parse(readFileSync(join(output, "suite-summary.json"), "utf8"));
  const junit = readFileSync(join(output, "suite-results.junit.xml"), "utf8");
  assert.equal(summary.outcome, "failed");
  assert.equal(summary.deviceEnvironments[0].restorationOutcome, "failed");
  assert.match(summary.deviceEnvironments[0].error, /time zone restoration failed/);
  assert.match(junit, /tests="4" failures="1"/);
  assert.match(junit, /Device refused time zone Etc\/UTC/);
});

function createSuiteFixture(directory, options = {}) {
  const point = join(directory, "login.json");
  const route = join(directory, "delivery.json");
  const suite = join(directory, "suite.json");
  writeFileSync(point, JSON.stringify({
    type: "static",
    name: "US login location",
    ...(options.timeZones ? { timeZone: "America/New_York" } : {}),
    latitude: 40.757955,
    longitude: -73.985532,
  }));
  writeFileSync(route, JSON.stringify({
    type: "route",
    name: "Delivery route",
    ...(options.timeZones ? { timeZone: "Asia/Shanghai" } : {}),
    speedKph: 120,
    loop: false,
    waypoints: [
      { name: "Start", latitude: 31.230416, longitude: 121.473701 },
      { name: "Finish", latitude: 31.231416, longitude: 121.473701 },
    ],
  }));
  writeFileSync(suite, JSON.stringify({
    schemaVersion: 1,
    name: "Global release smoke",
    steps: [
      { id: "us-login", scenario: "login.json", holdSeconds: 0 },
      { id: "delivery", scenario: "delivery.json", holdSeconds: 0 },
    ],
  }));
  return { point, route, suite };
}

function createAdbHarness(resultCode = "1", resultData = "started", failedTimeZone = "") {
  const directory = mkdtempSync(join(tmpdir(), "gps-lab-test-"));
  temporaryDirectories.push(directory);
  const log = join(directory, "calls.jsonl");
  const timeZoneFile = join(directory, "time-zone.txt");
  const devicesFile = join(directory, "devices.txt");
  const failedStatusDeviceFile = join(directory, "failed-status-device.txt");
  writeFileSync(timeZoneFile, "Etc/UTC");
  writeFileSync(devicesFile, "emulator-5554");
  writeFileSync(failedStatusDeviceFile, "");
  const executable = join(directory, "adb");
  writeFileSync(executable, `#!/usr/bin/env node
const { appendFileSync, readFileSync, writeFileSync } = require("node:fs");
appendFileSync(process.env.ADB_LOG, JSON.stringify(process.argv.slice(2)) + "\\n");
if (process.argv[2] === "devices") {
  const devices = readFileSync(process.env.ADB_DEVICES_FILE, "utf8").trim().split("\\n").filter(Boolean);
  process.stdout.write("List of devices attached\\n" + devices.map((serial) => serial + "\\tdevice").join("\\n") + "\\n");
} else if (process.argv.includes("set-timezone")) {
  const timeZone = process.argv[process.argv.indexOf("set-timezone") + 1];
  if (timeZone === process.env.ADB_FAIL_TIME_ZONE) {
    process.stderr.write(\`Device refused time zone \${timeZone}\\n\`);
    process.exit(1);
  }
  writeFileSync(process.env.ADB_TIME_ZONE_FILE, timeZone);
} else if (process.argv.includes("getprop") && process.argv.includes("persist.sys.timezone")) {
  process.stdout.write(readFileSync(process.env.ADB_TIME_ZONE_FILE, "utf8") + "\\n");
} else if (process.argv.includes("broadcast")) {
  const serial = process.argv[3];
  const failedStatusDevice = readFileSync(process.env.ADB_FAILED_STATUS_DEVICE_FILE, "utf8").trim();
  if (process.argv.includes("com.gpsupdater.app.enterprise.GET_DEVICE_STATUS") && serial === failedStatusDevice) {
    process.stdout.write('Broadcast completed: result=5, data="status_unavailable"\\n');
  } else {
    process.stdout.write('Broadcast completed: result=${resultCode}, data="${resultData}"\\n');
  }
}
`);
  chmodSync(executable, 0o755);
  return {
    directory,
    log,
    timeZoneFile,
    devicesFile,
    failedStatusDeviceFile,
    failedTimeZone,
    setDevices: (devices) => writeFileSync(devicesFile, devices.join("\n")),
    failStatusFor: (serial) => writeFileSync(failedStatusDeviceFile, serial),
    calls: () => existsSync(log)
      ? readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map(JSON.parse)
      : []
  };
}

function runCli(harness, argumentsList) {
  return spawnSync(process.execPath, [cli, ...argumentsList], {
    encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${harness.directory}:${process.env.PATH}`,
      ADB_LOG: harness.log,
      ADB_TIME_ZONE_FILE: harness.timeZoneFile,
      ADB_DEVICES_FILE: harness.devicesFile,
      ADB_FAILED_STATUS_DEVICE_FILE: harness.failedStatusDeviceFile,
      ADB_FAIL_TIME_ZONE: harness.failedTimeZone,
    }
  });
}
