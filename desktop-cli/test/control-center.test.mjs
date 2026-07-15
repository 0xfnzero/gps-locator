import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, test } from "node:test";
import {
  ControlCenterError,
  ControlCenterService,
  createControlCenter,
} from "../control-center.mjs";

const temporaryDirectories = [];
const runningServers = [];

afterEach(async () => {
  while (runningServers.length > 0) await runningServers.pop().close();
  while (temporaryDirectories.length > 0) {
    rmSync(temporaryDirectories.pop(), { recursive: true, force: true });
  }
});

test("control center discovers valid suites and reports invalid manifests", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture);
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });

  const bootstrap = await service.bootstrap();

  assert.deepEqual(
    bootstrap.devices.map((device) => device.serial),
    ["emulator-5554", "qa-phone-01", "qa-phone-02"],
  );
  assert.equal(bootstrap.devices[0].mockLocationReady, true);
  assert.equal(bootstrap.devices[0].commandReady, true);
  assert.equal(bootstrap.devices[0].readiness, "ready");
  assert.equal(bootstrap.devices[1].enterprise.organization, "Example QA");
  assert.equal(bootstrap.devices[1].simulationActive, true);
  assert.equal(bootstrap.devices[2].available, false);
  assert.equal(bootstrap.devices[2].commandReady, false);
  assert.equal(bootstrap.devices[2].readiness, "status_unavailable");
  assert.match(bootstrap.devices[2].error, /app is not installed/);
  assert.equal(bootstrap.deviceError, null);
  assert.equal(bootstrap.suites.length, 2);
  assert.deepEqual(
    bootstrap.suites.map((suite) => ({ id: suite.id, valid: suite.valid })),
    [
      { id: "global.json", valid: true },
      { id: "invalid.json", valid: false },
    ],
  );
  assert.equal(bootstrap.suites[0].steps, 2);
  assert.deepEqual(bootstrap.suites[0].types, ["static", "route"]);
  assert.match(bootstrap.suites[1].error, /schemaVersion/);
});

test("control center rejects malformed device status protocol data", async () => {
  const fixture = createFixture();
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: async (argumentsList) => argumentsList[0] === "status"
      ? { exitCode: 0, stdout: "not-json\n", stderr: "" }
      : { exitCode: 1, stdout: "", stderr: "Unexpected command" },
  });

  const bootstrap = await service.bootstrap();

  assert.deepEqual(bootstrap.devices, []);
  assert.match(bootstrap.deviceError, /returned invalid data/);
});

test("suite jobs target an explicit device subset and expose the completed report", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture, { deferSuite: true });
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });

  const job = await service.startSuite("global.json", ["emulator-5554", "qa-phone-02"]);
  assert.equal(job.status, "running");
  assert.deepEqual(job.devices, ["emulator-5554", "qa-phone-02"]);
  const suiteCall = runner.calls.find((call) => call[0] === "suite" && !call.includes("--dry-run"));
  assert.equal(suiteCall[suiteCall.indexOf("--serial") + 1], "emulator-5554,qa-phone-02");
  await assert.rejects(
    service.startSuite("global.json", ["qa-phone-01"]),
    (error) => error instanceof ControlCenterError && error.status === 409,
  );

  runner.finishSuite();
  const completed = await waitFor(() => service.job(job.id), (value) => value.status !== "running");
  assert.equal(completed.status, "completed");
  assert.match(completed.log, /suite login/);
  assert.equal(completed.summary.outcome, "completed");
  const recent = service.recentRuns();
  assert.equal(recent.length, 1);
  assert.equal(recent[0].deviceCount, 2);
  assert.equal(service.runSummary(recent[0].id).suite.name, "Global release smoke");
});

test("device commands reject disconnected serials before invoking a mutation", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture);
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });

  await assert.rejects(
    service.stopDevices(["missing-device"]),
    (error) => error instanceof ControlCenterError && error.status === 409,
  );
  assert.equal(runner.calls.some((call) => call[0] === "stop"), false);
});

test("device preparation isolates failures and verifies post-command readiness", async () => {
  const fixture = createFixture();
  const prepared = new Set();
  const runCli = async (argumentsList) => {
    if (argumentsList[0] === "status") {
      return {
        exitCode: 0,
        stdout: [
          deviceStatus("emulator-5554", { mockLocationReady: prepared.has("emulator-5554") }),
          deviceStatus("qa-phone-01", { mockLocationReady: false }),
        ].join("\n") + "\n",
        stderr: "",
      };
    }
    if (argumentsList[0] === "prepare") {
      const serial = argumentsList[argumentsList.indexOf("--serial") + 1];
      if (serial === "qa-phone-01") {
        return { exitCode: 1, stdout: "", stderr: "GPS Lab app is not installed\n" };
      }
      prepared.add(serial);
      return { exitCode: 0, stdout: `${serial}: prepared\n`, stderr: "" };
    }
    return { exitCode: 1, stdout: "", stderr: "Unexpected command" };
  };
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli,
  });

  const result = await service.prepareDevices(["emulator-5554", "qa-phone-01"]);

  assert.equal(result.outcome, "incomplete");
  assert.equal(result.completedCount, 1);
  assert.equal(result.failedCount, 1);
  assert.deepEqual(result.results.map((record) => record.outcome), ["prepared", "failed"]);
  assert.match(result.results[1].error, /not installed/);
});

test("quick location validates structured input and targets the selected fleet subset", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture);
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });

  const result = await service.setLocation(
    ["emulator-5554", "qa-phone-02"],
    {
      name: "New York QA",
      latitude: "40.757955",
      longitude: "-73.985532",
      timeZone: "America/New_York",
    },
  );

  assert.deepEqual(result.devices, ["emulator-5554", "qa-phone-02"]);
  assert.equal(result.location.latitude, 40.757955);
  assert.equal(result.location.timeZone, "America/New_York");
  const setCall = runner.calls.find((call) => call[0] === "set");
  assert.equal(setCall[setCall.indexOf("--serial") + 1], "emulator-5554,qa-phone-02");
  assert.equal(setCall[setCall.indexOf("--time-zone") + 1], "America/New_York");

  await assert.rejects(
    service.setLocation(["emulator-5554"], {
      name: "Invalid",
      latitude: 91,
      longitude: 1,
    }),
    (error) => error instanceof ControlCenterError && error.status === 400,
  );
  assert.equal(runner.calls.filter((call) => call[0] === "set").length, 1);
});

test("stop automatically retains device reports as a fleet command summary", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture);
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });

  const result = await service.stopDevices(["emulator-5554", "qa-phone-02"]);

  assert.equal(result.reportOutcome, "completed");
  assert.equal(result.reportName, "New York smoke / 纽约");
  assert.equal(result.warning, null);
  const commandNames = runner.calls.map((call) => call[0]);
  assert.ok(commandNames.indexOf("stop") < commandNames.indexOf("report"));
  const runs = service.recentRuns();
  assert.equal(runs.length, 1);
  assert.equal(runs[0].kind, "fleet-command");
  assert.equal(runs[0].name, "New York smoke / 纽约");
  assert.equal(runs[0].deviceCount, 2);
  assert.equal(runs[0].itemCount, 2);
  assert.equal(runs[0].failureCount, 0);
  const summary = service.runSummary(result.reportId);
  assert.equal(summary.command, "stop-and-collect");
  assert.equal(summary.junitFile, "fleet-results.junit.xml");
  assert.equal(summary.junitSchemaVersion, 1);
  assert.deepEqual(summary.events.map((event) => event.serial), ["emulator-5554", "qa-phone-02"]);
  assert.ok(summary.deviceReports.every((report) => report.outcome === "pulled"));
  const junitFile = join(fixture.reportDirectory, result.reportId, "fleet-results.junit.xml");
  assert.equal(existsSync(junitFile), true);
  const junit = readFileSync(junitFile, "utf8");
  assert.match(junit, /tests="4" failures="0"/);
  assert.match(junit, /name="New York smoke \/ 纽约 \[emulator-5554\]"/);
  rmSync(junitFile);
  const migratedRun = service.recentRuns()[0];
  assert.equal(migratedRun.artifacts.junit, true);
  assert.equal(existsSync(junitFile), true);
  const migratedSummary = service.runSummary(result.reportId);
  assert.equal(migratedSummary.junitFile, "fleet-results.junit.xml");
  assert.equal(migratedSummary.junitSchemaVersion, 1);
});

test("stop reports partial evidence failure without hiding the successful stop", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture, { reportFailure: true });
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });

  const result = await service.stopDevices(["emulator-5554"]);

  assert.equal(result.reportOutcome, "failed");
  assert.match(result.warning, /report pull failed/);
  assert.match(result.output, /devices stopped/);
  const run = service.recentRuns()[0];
  assert.equal(run.outcome, "failed");
  assert.equal(run.failureCount, 1);
  const junit = readFileSync(
    join(fixture.reportDirectory, result.reportId, "fleet-results.junit.xml"),
    "utf8",
  );
  assert.match(junit, /tests="1" failures="1"/);
  assert.match(junit, /Device report file is missing/);
});

test("HTTP control API requires a same-origin token and never accepts file paths", async () => {
  const fixture = createFixture();
  const runner = createRunner(fixture);
  const service = new ControlCenterService({
    suiteDirectory: fixture.suiteDirectory,
    reportDirectory: fixture.reportDirectory,
    runCli: runner.run,
  });
  const controlCenter = createControlCenter({ host: "127.0.0.1", port: 0, service });
  runningServers.push(controlCenter);
  const address = await controlCenter.start();

  const page = await fetch(address.url);
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-security-policy"), /default-src 'self'/);
  assert.match(await page.text(), /Enterprise control center/);

  const productAsset = await fetch(new URL("/assets/product-map.png", address.url));
  assert.equal(productAsset.status, 200);
  assert.equal(productAsset.headers.get("content-type"), "image/png");
  assert.deepEqual(
    [...Buffer.from(await productAsset.arrayBuffer()).subarray(0, 8)],
    [137, 80, 78, 71, 13, 10, 26, 10],
  );

  const bootstrapResponse = await fetch(new URL("/api/bootstrap", address.url));
  const bootstrap = await bootstrapResponse.json();
  assert.match(bootstrap.controlToken, /^[a-zA-Z0-9_-]+$/);

  const missingToken = await fetch(new URL("/api/suites/validate", address.url), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ suiteId: "global.json" }),
  });
  assert.equal(missingToken.status, 403);

  const arbitraryPath = await fetch(new URL("/api/suites/validate", address.url), {
    method: "POST",
    headers: mutationHeaders(address.url, bootstrap.controlToken),
    body: JSON.stringify({ suiteId: "../../outside.json" }),
  });
  assert.equal(arbitraryPath.status, 404);

  const validation = await fetch(new URL("/api/suites/validate", address.url), {
    method: "POST",
    headers: mutationHeaders(address.url, bootstrap.controlToken),
    body: JSON.stringify({ suiteId: "global.json" }),
  });
  assert.equal(validation.status, 200);
  assert.match((await validation.json()).output, /Suite valid/);

  const crossOrigin = await fetch(new URL("/api/suites/validate", address.url), {
    method: "POST",
    headers: {
      ...mutationHeaders(address.url, bootstrap.controlToken),
      Origin: "http://example.test",
    },
    body: JSON.stringify({ suiteId: "global.json" }),
  });
  assert.equal(crossOrigin.status, 403);

  const quickLocation = await fetch(new URL("/api/location/set", address.url), {
    method: "POST",
    headers: mutationHeaders(address.url, bootstrap.controlToken),
    body: JSON.stringify({
      deviceSerials: ["qa-phone-01"],
      location: {
        name: "Madrid QA",
        latitude: 40.4168,
        longitude: -3.7038,
        timeZone: "Europe/Madrid",
      },
    }),
  });
  assert.equal(quickLocation.status, 200);
  const quickLocationResult = await quickLocation.json();
  assert.deepEqual(quickLocationResult.devices, ["qa-phone-01"]);
  assert.equal(quickLocationResult.location.timeZone, "Europe/Madrid");

  const prepareResponse = await fetch(new URL("/api/devices/prepare", address.url), {
    method: "POST",
    headers: mutationHeaders(address.url, bootstrap.controlToken),
    body: JSON.stringify({ deviceSerials: ["qa-phone-01"] }),
  });
  assert.equal(prepareResponse.status, 200);
  const prepareResult = await prepareResponse.json();
  assert.equal(prepareResult.outcome, "completed");
  assert.deepEqual(prepareResult.devices, ["qa-phone-01"]);

  const stopResponse = await fetch(new URL("/api/devices/stop", address.url), {
    method: "POST",
    headers: mutationHeaders(address.url, bootstrap.controlToken),
    body: JSON.stringify({ deviceSerials: ["qa-phone-01"] }),
  });
  assert.equal(stopResponse.status, 200);
  const stopResult = await stopResponse.json();
  const summaryArtifact = await fetch(new URL(
    `/api/runs/${encodeURIComponent(stopResult.reportId)}/artifacts/summary`,
    address.url,
  ));
  assert.equal(summaryArtifact.status, 200);
  assert.match(summaryArtifact.headers.get("content-disposition"), /attachment; filename=/);
  assert.equal((await summaryArtifact.json()).kind, "fleet-command");
  const junitArtifact = await fetch(new URL(
    `/api/runs/${encodeURIComponent(stopResult.reportId)}/artifacts/junit`,
    address.url,
  ));
  assert.equal(junitArtifact.status, 200);
  assert.match(junitArtifact.headers.get("content-type"), /application\/xml/);
  assert.match(await junitArtifact.text(), /tests="2" failures="0"/);

  const escapedArtifact = await fetch(new URL(
    "/api/runs/..%2Foutside/artifacts/summary",
    address.url,
  ));
  assert.equal(escapedArtifact.status, 400);
});

function createFixture() {
  const directory = mkdtempSync(join(tmpdir(), "gps-control-center-test-"));
  temporaryDirectories.push(directory);
  const suiteDirectory = join(directory, "suites");
  const scenarioDirectory = join(directory, "scenarios");
  const reportDirectory = join(directory, "reports");
  mkdirSync(suiteDirectory);
  mkdirSync(scenarioDirectory);
  mkdirSync(reportDirectory);
  writeFileSync(join(scenarioDirectory, "login.json"), JSON.stringify({
    schemaVersion: 1,
    type: "static",
    name: "New York login",
    timeZone: "America/New_York",
    latitude: 40.757955,
    longitude: -73.985532,
  }));
  writeFileSync(join(scenarioDirectory, "delivery.json"), JSON.stringify({
    schemaVersion: 1,
    type: "route",
    name: "Shanghai delivery",
    timeZone: "Asia/Shanghai",
    waypoints: [
      { latitude: 31.230416, longitude: 121.473701 },
      { latitude: 31.231416, longitude: 121.473701 },
    ],
  }));
  writeFileSync(join(suiteDirectory, "global.json"), JSON.stringify({
    schemaVersion: 1,
    name: "Global release smoke",
    steps: [
      { id: "login", scenario: "../scenarios/login.json", holdSeconds: 0 },
      { id: "delivery", scenario: "../scenarios/delivery.json", holdSeconds: 0 },
    ],
  }));
  writeFileSync(join(suiteDirectory, "invalid.json"), JSON.stringify({
    schemaVersion: 9,
    name: "Invalid suite",
    steps: [],
  }));
  return { directory, suiteDirectory, scenarioDirectory, reportDirectory };
}

function createRunner(fixture, options = {}) {
  const calls = [];
  let resolveSuite;
  const run = (argumentsList, onOutput = () => {}) => {
    calls.push(argumentsList);
    if (argumentsList[0] === "status") {
      return Promise.resolve({
        exitCode: 0,
        stdout: [
          deviceStatus("emulator-5554", { debug: true, tier: "pro" }),
          deviceStatus("qa-phone-01", {
            tier: "enterprise",
            simulationActive: true,
            enterprise: {
              organization: "Example QA",
              expiresAt: 0,
              active: true,
              deviceBound: true,
            },
          }),
          JSON.stringify({
            serial: "qa-phone-02",
            available: false,
            error: "GPS Lab app is not installed",
          }),
        ].join("\n") + "\n",
        stderr: "",
      });
    }
    if (argumentsList.includes("--dry-run")) {
      return Promise.resolve({
        exitCode: 0,
        stdout: "Suite valid: Global release smoke (2 steps)\n",
        stderr: "",
      });
    }
    if (argumentsList[0] === "stop") {
      return Promise.resolve({ exitCode: 0, stdout: "devices stopped\n", stderr: "" });
    }
    if (argumentsList[0] === "set") {
      return Promise.resolve({ exitCode: 0, stdout: "location set\n", stderr: "" });
    }
    if (argumentsList[0] === "prepare") {
      return Promise.resolve({ exitCode: 0, stdout: "device prepared\n", stderr: "" });
    }
    if (argumentsList[0] === "report") {
      if (options.reportFailure) {
        return Promise.resolve({ exitCode: 1, stdout: "", stderr: "report pull failed\n" });
      }
      const outputDirectory = argumentsList[argumentsList.indexOf("--output") + 1];
      const serialArgument = argumentsList[argumentsList.indexOf("--serial") + 1];
      const devices = serialArgument === "all"
        ? ["emulator-5554", "qa-phone-01", "qa-phone-02"]
        : serialArgument.split(",");
      mkdirSync(outputDirectory, { recursive: true });
      for (const serial of devices) {
        writeFileSync(join(outputDirectory, `${serial}-report.json`), JSON.stringify({
          schemaVersion: 2,
          generatedAt: 2_000,
          device: { manufacturer: "Google", model: serial, sdk: 35 },
          events: [{
            id: `run-${serial}`,
            name: "New York smoke / 纽约",
            mode: "static",
            outcome: "stopped",
            startedAt: 1_000,
            finishedAt: 1_900,
            durationMs: 900,
            startLatitude: 40.757955,
            startLongitude: -73.985532,
            endLatitude: 40.757955,
            endLongitude: -73.985532,
            timeZoneId: "America/New_York",
            utcOffsetSeconds: -14_400,
          }],
        }));
      }
      return Promise.resolve({ exitCode: 0, stdout: "reports pulled\n", stderr: "" });
    }
    if (argumentsList[0] === "suite") {
      const outputDirectory = argumentsList[argumentsList.indexOf("--output") + 1];
      const serialArgument = argumentsList[argumentsList.indexOf("--serial") + 1];
      const devices = serialArgument === "all"
        ? ["emulator-5554", "qa-phone-01", "qa-phone-02"]
        : serialArgument.split(",");
      const finish = () => {
        onOutput("stdout", "suite login: New York login\n");
        mkdirSync(outputDirectory, { recursive: true });
        writeFileSync(join(outputDirectory, "suite-summary.json"), JSON.stringify({
          schemaVersion: 1,
          suite: { name: "Global release smoke", file: "global.json", sha256: "a".repeat(64) },
          outcome: "completed",
          startedAt: 100,
          finishedAt: 200,
          devices,
          deviceEnvironments: [],
          steps: [{
            id: "login",
            scenario: "../scenarios/login.json",
            scenarioSha256: "b".repeat(64),
            type: "static",
            timeZone: "America/New_York",
            startedAt: 100,
            finishedAt: 150,
            devices: devices.map((serial) => ({ serial, outcome: "completed" })),
          }],
          deviceReports: devices.map((serial) => ({ serial, outcome: "pulled", file: `${serial}.json` })),
        }));
        return { exitCode: 0, stdout: "suite completed\n", stderr: "" };
      };
      if (!options.deferSuite) return Promise.resolve(finish());
      return new Promise((resolve) => {
        resolveSuite = () => resolve(finish());
      });
    }
    return Promise.resolve({ exitCode: 1, stdout: "", stderr: "Unexpected command" });
  };
  return {
    calls,
    run,
    finishSuite: () => resolveSuite(),
  };
}

function deviceStatus(serial, overrides = {}) {
  return JSON.stringify({
    schemaVersion: 1,
    applicationId: "com.gpsupdater.app",
    appVersion: "2.0.0",
    versionCode: 4,
    debug: false,
    tier: "enterprise",
    deviceId: `id-${serial}`,
    mockLocationReady: true,
    simulationActive: false,
    enterprise: {
      organization: "Example QA",
      expiresAt: 0,
      active: true,
      deviceBound: false,
    },
    serial,
    available: true,
    ...overrides,
  });
}

function mutationHeaders(baseUrl, token) {
  return {
    "Content-Type": "application/json",
    "X-GPS-Control-Token": token,
    Origin: new URL(baseUrl).origin,
  };
}

async function waitFor(read, predicate) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const value = read();
    if (predicate(value)) return value;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error("Timed out waiting for state change");
}
