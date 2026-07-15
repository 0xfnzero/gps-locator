#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join, relative, resolve } from "node:path";
import { compileScenario, validateStaticScenario } from "./scenario.mjs";
import { assessDeviceStatus, isDeviceStatusPayload } from "./device-status.mjs";
import { suiteSummaryToJUnit } from "./junit-report.mjs";
import { loadScenarioSuite } from "./suite.mjs";

const packageName = "com.gpsupdater.app";
const receiverComponent = `${packageName}/.enterprise.EnterpriseControlReceiver`;
const mainActivityComponent = `${packageName}/.MainActivity`;
const mockLocationServiceComponent = `${packageName}/.location.MockLocationService`;
const actions = {
  set: `${packageName}.enterprise.SET_LOCATION`,
  route: `${packageName}.enterprise.START_ROUTE`,
  stop: `${packageName}.enterprise.STOP`,
  report: `${packageName}.enterprise.WRITE_REPORT`,
  deviceId: `${packageName}.enterprise.GET_DEVICE_ID`,
  status: `${packageName}.enterprise.GET_DEVICE_STATUS`
};
const [command, ...rawArguments] = process.argv.slice(2);
const argumentsMap = parseArguments(rawArguments);

try {
  if (!command || command === "help" || argumentsMap.has("help")) {
    usage();
    process.exit(0);
  }
  if (command === "devices") {
    for (const serial of connectedDevices()) console.log(serial);
    process.exit(0);
  }
  if (command === "suite") {
    runSuite(argumentsMap);
  } else {
    const devices = selectedDevices(argumentsMap.get("serial"));
    if (command === "prepare") prepare(devices, argumentsMap.get("apk"));
    else if (command === "device-id") printDeviceIds(devices, argumentsMap.has("plain"));
    else if (command === "status") {
      const records = printDeviceStatuses(devices);
      if (argumentsMap.has("require-ready")) {
        const unready = records.filter((record) => !record.commandReady);
        if (unready.length > 0) {
          throw new Error(
            `${unready.length} of ${records.length} selected devices are not ready: ` +
            unready.map((record) => `${record.serial} (${record.readiness})`).join(", "),
          );
        }
      }
    }
    else if (command === "set") setLocation(devices, argumentsMap);
    else if (command === "route") startRoute(devices, required(argumentsMap, "file"));
    else if (command === "scenario") runScenario(devices, required(argumentsMap, "file"));
    else if (command === "stop") stopSimulations(devices);
    else if (command === "report") pullReports(devices, argumentsMap.get("output") ?? "reports");
    else throw new Error(`Unknown command: ${command}`);
  }
} catch (error) {
  console.error(`gps-lab: ${error.message}`);
  process.exit(1);
}

function prepare(devices, apk) {
  for (const serial of devices) {
    if (apk) adb(serial, ["install", "-r", resolve(apk)]);
    adb(serial, ["shell", "pm", "grant", packageName, "android.permission.ACCESS_FINE_LOCATION"], true);
    adb(serial, ["shell", "pm", "grant", packageName, "android.permission.POST_NOTIFICATIONS"], true);
    adb(serial, ["shell", "appops", "set", packageName, "android:mock_location", "allow"]);
    console.log(`${serial}: prepared`);
  }
}

function setLocation(devices, values) {
  const latitude = required(values, "lat");
  const longitude = required(values, "lon");
  const location = validateStaticScenario({
    type: "static",
    name: values.get("name") ?? `${latitude}, ${longitude}`,
    latitude,
    longitude,
    timeZone: values.get("time-zone"),
  });
  const compiled = {
    type: "static",
    name: location.name,
    timeZone: location.timeZone,
    location,
  };
  for (const serial of devices) executeCompiledScenario(serial, compiled);
}

function startRoute(devices, file) {
  const document = readFileSync(resolve(file), "utf8");
  sendRouteDocument(devices, document, basename(file));
}

function runScenario(devices, file) {
  const scenario = JSON.parse(readFileSync(resolve(file), "utf8"));
  const compiled = compileScenario(scenario);
  for (const serial of devices) executeCompiledScenario(serial, compiled);
}

function sendRouteDocument(devices, document, name) {
  const extras = routeExtras(document, name);
  broadcast(devices, actions.route, extras);
}

function routeExtras(document, name) {
  const encoded = Buffer.from(document).toString("base64url");
  if (encoded.length > 100_000) throw new Error("Route document is too large for an ADB broadcast");
  return [
    "--es", "route_document", encoded,
    "--es", "name_base64", encodeText(name)
  ];
}

function executeCompiledScenario(serial, compiled) {
  if (compiled.timeZone) setDeviceTimeZone(serial, compiled.timeZone);
  executeCompiledLocation(serial, compiled);
}

function executeCompiledLocation(serial, compiled) {
  if (compiled.type === "static") {
    const { location } = compiled;
    broadcastOne(serial, actions.set, [
      "--es", "latitude", String(location.latitude),
      "--es", "longitude", String(location.longitude),
      "--es", "name_base64", encodeText(location.name)
    ], true);
  } else {
    broadcastOne(serial, actions.route, routeExtras(compiled.document, compiled.name), true);
  }
  const timeZoneDetail = compiled.timeZone ? `; timeZone=${compiled.timeZone}` : "";
  console.log(`${serial}: ${compiled.type} ${compiled.name}${timeZoneDetail}`);
}

function runSuite(values) {
  const suite = loadScenarioSuite(required(values, "file"));
  if (values.has("dry-run")) {
    console.log(`Suite valid: ${suite.name} (${suite.steps.length} steps)`);
    for (const step of suite.steps) {
      console.log(
        `${step.id}: ${step.compiled.type} ${step.compiled.name}; ` +
        `hold=${step.holdSeconds}s; stopAfter=${step.stopAfter}; ` +
        `timeZone=${step.compiled.timeZone ?? "unchanged"}`
      );
    }
    return;
  }

  const devices = selectedDevices(values.get("serial"));
  const startedAt = Date.now();
  const outputDirectory = resolve(
    values.get("output") ?? join(
      "suite-reports",
      `${safeFileComponent(suite.name)}-${new Date(startedAt).toISOString().replaceAll(/[:.]/g, "-")}`
    )
  );
  const deviceReportDirectory = join(outputDirectory, "devices");
  mkdirSync(deviceReportDirectory, { recursive: true });
  let failed = false;
  const stepResults = [];
  const deviceEnvironments = [];
  const environmentBySerial = new Map();
  if (suite.steps.some((step) => step.compiled.timeZone)) {
    for (const serial of devices) {
      try {
        const originalTimeZone = readDeviceTimeZone(serial);
        const environment = {
          serial,
          originalTimeZone,
          requestedTimeZones: [],
          currentTimeZone: originalTimeZone,
          touched: false,
          restorationOutcome: "pending",
        };
        deviceEnvironments.push(environment);
        environmentBySerial.set(serial, environment);
      } catch (error) {
        failed = true;
        const environment = {
          serial,
          originalTimeZone: null,
          requestedTimeZones: [],
          currentTimeZone: null,
          touched: false,
          restorationOutcome: "capture_failed",
          error: error.message,
        };
        deviceEnvironments.push(environment);
        environmentBySerial.set(serial, environment);
      }
    }
  }

  for (const step of suite.steps) {
    const stepStartedAt = Date.now();
    const deviceResults = [];
    const startedDevices = [];
    console.log(`suite ${step.id}: ${step.compiled.name}`);
    for (const serial of devices) {
      const environment = environmentBySerial.get(serial);
      if (environment?.restorationOutcome === "capture_failed") {
        deviceResults.push({
          serial,
          outcome: "failed",
          error: `time zone capture failed: ${environment.error}`,
        });
        continue;
      }
      try {
        if (step.compiled.timeZone) {
          environment.requestedTimeZones.push(step.compiled.timeZone);
          if (environment.currentTimeZone !== step.compiled.timeZone) {
            environment.touched = true;
            setDeviceTimeZone(serial, step.compiled.timeZone);
            environment.currentTimeZone = step.compiled.timeZone;
          }
        }
        executeCompiledLocation(serial, step.compiled);
        startedDevices.push(serial);
        deviceResults.push({ serial, outcome: step.stopAfter ? "running" : "started" });
      } catch (error) {
        failed = true;
        deviceResults.push({ serial, outcome: "failed", error: error.message });
      }
    }

    if (startedDevices.length > 0 && step.holdSeconds > 0) {
      waitMilliseconds(step.holdSeconds * 1_000);
    }
    if (step.stopAfter) {
      for (const serial of startedDevices) {
        const result = deviceResults.find((candidate) => candidate.serial === serial);
        try {
          broadcastOne(serial, actions.stop, [], true);
          result.outcome = "completed";
        } catch (error) {
          failed = true;
          result.outcome = "failed";
          result.error = `stop failed: ${error.message}`;
        }
      }
    }
    stepResults.push({
      id: step.id,
      scenario: step.scenarioReference,
      scenarioSha256: step.scenarioSha256,
      name: step.compiled.name,
      type: step.compiled.type,
      timeZone: step.compiled.timeZone,
      holdSeconds: step.holdSeconds,
      stopAfter: step.stopAfter,
      startedAt: stepStartedAt,
      finishedAt: Date.now(),
      devices: deviceResults,
    });
  }

  const deviceReports = [];
  for (const serial of devices) {
    try {
      const report = pullReportOne(serial, deviceReportDirectory);
      deviceReports.push({
        serial,
        outcome: "pulled",
        file: relative(outputDirectory, report),
      });
    } catch (error) {
      failed = true;
      deviceReports.push({ serial, outcome: "failed", error: error.message });
    }
  }

  for (const environment of deviceEnvironments) {
    if (environment.restorationOutcome === "capture_failed") continue;
    if (!environment.touched) {
      environment.restorationOutcome = "unchanged";
      continue;
    }
    try {
      setDeviceTimeZone(environment.serial, environment.originalTimeZone);
      environment.currentTimeZone = environment.originalTimeZone;
      environment.restorationOutcome = "restored";
    } catch (error) {
      failed = true;
      environment.restorationOutcome = "failed";
      environment.error = `time zone restoration failed: ${error.message}`;
    }
  }

  const summary = {
    schemaVersion: 1,
    suite: {
      name: suite.name,
      file: basename(suite.file),
      sha256: suite.sha256,
    },
    outcome: failed ? "failed" : "completed",
    startedAt,
    finishedAt: Date.now(),
    devices,
    deviceEnvironments: deviceEnvironments.map(({ touched, ...environment }) => environment),
    steps: stepResults,
    deviceReports,
  };
  const summaryFile = join(outputDirectory, "suite-summary.json");
  const junitFile = join(outputDirectory, "suite-results.junit.xml");
  writeFileSync(summaryFile, `${JSON.stringify(summary, null, 2)}\n`);
  writeFileSync(junitFile, suiteSummaryToJUnit(summary));
  console.log(`suite summary: ${summaryFile}`);
  console.log(`suite JUnit: ${junitFile}`);
  if (failed) throw new Error(`Suite failed; summary: ${summaryFile}`);
}

function pullReports(devices, outputDirectory) {
  mkdirSync(resolve(outputDirectory), { recursive: true });
  for (const serial of devices) {
    const output = pullReportOne(serial, resolve(outputDirectory));
    console.log(`${serial}: ${output}`);
  }
}

function stopSimulations(devices) {
  for (const serial of devices) {
    broadcastOne(serial, actions.stop, [], false);
    const deadline = Date.now() + 8_000;
    while (isMockLocationServiceRunning(serial)) {
      if (Date.now() >= deadline) {
        throw new Error(`${serial}: mock location service did not stop within 8 seconds`);
      }
      waitMilliseconds(100);
    }
    console.log(`${serial}: ${actions.stop}`);
  }
}

function isMockLocationServiceRunning(serial) {
  const output = adb(serial, [
    "shell", "dumpsys", "activity", "services", mockLocationServiceComponent,
  ]);
  return output.includes(mockLocationServiceComponent);
}

function pullReportOne(serial, outputDirectory) {
  broadcastOne(serial, actions.report, [], false);
  const output = join(outputDirectory, `${safeFileComponent(serial)}-report.json`);
  adb(serial, ["pull", `/sdcard/Android/data/${packageName}/files/reports/latest-report.json`, output]);
  return output;
}

function printDeviceIds(devices, plain) {
  if (plain && devices.length !== 1) throw new Error("--plain requires exactly one device");
  for (const serial of devices) {
    const deviceId = broadcastOne(serial, actions.deviceId, [], false);
    console.log(plain ? deviceId : `${serial}: ${deviceId}`);
  }
}

function printDeviceStatuses(devices) {
  const records = [];
  for (const serial of devices) {
    let record;
    try {
      const encoded = broadcastOne(serial, actions.status, [], false);
      const status = decodeDeviceStatus(encoded);
      const availableStatus = { ...status, serial, available: true };
      record = { ...availableStatus, ...assessDeviceStatus(availableStatus) };
    } catch (error) {
      const unavailableStatus = { serial, available: false, error: error.message };
      record = { ...unavailableStatus, ...assessDeviceStatus(unavailableStatus) };
    }
    records.push(record);
    console.log(JSON.stringify(record));
  }
  return records;
}

function decodeDeviceStatus(encoded) {
  if (typeof encoded !== "string" || encoded.length === 0 || encoded.length > 100_000 ||
      !/^[a-zA-Z0-9_-]+$/.test(encoded)) {
    throw new Error("device returned an invalid status encoding");
  }
  let status;
  try {
    status = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8"));
  } catch {
    throw new Error("device returned malformed status JSON");
  }
  if (!isDeviceStatusPayload(status, packageName)) {
    throw new Error("device returned an unsupported status payload");
  }
  return status;
}

function broadcast(devices, action, extras = []) {
  for (const serial of devices) {
    broadcastOne(serial, action, extras, true);
    console.log(`${serial}: ${action}`);
  }
}

function broadcastOne(serial, action, extras, bringToForeground) {
  if (bringToForeground) {
    adb(serial, ["shell", "am", "start", "-W", "-n", mainActivityComponent]);
  }
  const output = adb(serial, [
    "shell", "am", "broadcast", "--receiver-foreground",
    "-n", receiverComponent, "-a", action, ...extras
  ]);
  const result = output.match(/Broadcast completed: result=(-?\d+)(?:, data="([^"]*)")?/);
  if (!result) throw new Error(`${serial}: enterprise command returned no result`);
  if (Number(result[1]) !== 1) {
    const detail = result[2] ? ` (${result[2]})` : "";
    throw new Error(`${serial}: enterprise command failed with result ${result[1]}${detail}`);
  }
  return result[2] ?? "";
}

function readDeviceTimeZone(serial) {
  const timeZone = adb(serial, ["shell", "getprop", "persist.sys.timezone"]).trim();
  if (!timeZone) throw new Error(`${serial}: device returned an empty system time zone`);
  return timeZone;
}

function setDeviceTimeZone(serial, timeZone) {
  adb(serial, ["shell", "cmd", "alarm", "set-timezone", timeZone]);
  const actual = readDeviceTimeZone(serial);
  if (actual !== timeZone) {
    throw new Error(`${serial}: requested time zone ${timeZone}, device reported ${actual}`);
  }
}

function encodeText(value) {
  return Buffer.from(String(value), "utf8").toString("base64url");
}

function safeFileComponent(value) {
  return String(value).trim().replaceAll(/[^a-zA-Z0-9._-]+/g, "-").replaceAll(/^-+|-+$/g, "") || "suite";
}

function waitMilliseconds(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function selectedDevices(requested) {
  const devices = connectedDevices();
  if (devices.length === 0) throw new Error("No authorized Android devices are connected");
  if (!requested || requested === "all") return devices;
  const requestedDevices = [...new Set(
    requested.split(",").map((serial) => serial.trim()).filter(Boolean),
  )];
  if (requestedDevices.length === 0) throw new Error("At least one device serial is required");
  const missing = requestedDevices.filter((serial) => !devices.includes(serial));
  if (missing.length > 0) {
    throw new Error(`Devices are not connected: ${missing.join(", ")}`);
  }
  return requestedDevices;
}

function connectedDevices() {
  const output = execFileSync("adb", ["devices"], { encoding: "utf8" });
  return output.split("\n").slice(1).map((line) => line.trim().split(/\s+/))
    .filter(([, state]) => state === "device").map(([serial]) => serial);
}

function adb(serial, args, allowFailure = false) {
  try {
    return execFileSync("adb", ["-s", serial, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    if (allowFailure) return "";
    throw new Error(error.stderr?.toString().trim() || error.message);
  }
}

function parseArguments(args) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (!argument.startsWith("--")) throw new Error(`Unexpected argument: ${argument}`);
    const key = argument.slice(2);
    const next = args[index + 1];
    if (!next || next.startsWith("--")) values.set(key, true);
    else {
      values.set(key, next);
      index += 1;
    }
  }
  return values;
}

function required(values, key) {
  const value = values.get(key);
  if (typeof value !== "string" || value === "") throw new Error(`--${key} is required`);
  return value;
}

function usage() {
  console.log(`GPS Updater Enterprise CLI

Usage:
  gps-lab devices
  gps-lab status [--serial all|SERIAL[,SERIAL...]] [--require-ready]
  gps-lab device-id [--serial all|SERIAL[,SERIAL...]] [--plain]
  gps-lab prepare [--serial all|SERIAL[,SERIAL...]] [--apk app-debug.apk]
  gps-lab set --lat LAT --lon LON [--name NAME] [--time-zone IANA]
    [--serial all|SERIAL[,SERIAL...]]
  gps-lab route --file route.gpx|route.kml|route.json [--serial all|SERIAL[,SERIAL...]]
  gps-lab scenario --file scenario.json [--serial all|SERIAL[,SERIAL...]]
  gps-lab suite --file suite.json [--serial all|SERIAL[,SERIAL...]] [--output DIR]
  gps-lab suite --file suite.json --dry-run
  gps-lab stop [--serial all|SERIAL[,SERIAL...]]
  gps-lab report [--output reports] [--serial all|SERIAL[,SERIAL...]]`);
}
