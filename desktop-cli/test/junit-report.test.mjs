import assert from "node:assert/strict";
import test from "node:test";
import { fleetSummaryToJUnit, suiteSummaryToJUnit } from "../junit-report.mjs";

test("suiteSummaryToJUnit emits CI-readable cases, failures, timing, and hashes", () => {
  const report = suiteSummaryToJUnit({
    schemaVersion: 1,
    suite: {
      name: 'Global <release> & "smoke"',
      file: "suite.json",
      sha256: "a".repeat(64),
    },
    outcome: "failed",
    startedAt: 1_000,
    finishedAt: 3_500,
    devices: ["device-1"],
    steps: [
      {
        id: "login",
        scenario: "login.json",
        scenarioSha256: "b".repeat(64),
        type: "static",
        startedAt: 1_000,
        finishedAt: 2_250,
        devices: [{ serial: "device-1", outcome: "failed", error: "license <required> & expired" }],
      },
    ],
    deviceReports: [{ serial: "device-1", outcome: "pulled", file: "devices/device-1.json" }],
  });

  assert.match(report, /^<\?xml version="1\.0" encoding="UTF-8"\?>/);
  assert.match(report, /tests="2" failures="1" errors="0" skipped="0" time="2\.500"/);
  assert.match(report, /Global &lt;release&gt; &amp; &quot;smoke&quot;/);
  assert.match(report, /name="login \[device-1\]" time="1\.250"/);
  assert.match(report, /scenarioSha256=b{64}/);
  assert.match(report, /<failure type="GpsLabFailure" message="license &lt;required&gt; &amp; expired">/);
  assert.match(report, /name="device report \[device-1\]" time="0\.000"/);
  assert.match(report, /gps-lab\.suite\.sha256/);
});

test("fleetSummaryToJUnit treats finalized events as success and running events as failures", () => {
  const report = fleetSummaryToJUnit({
    schemaVersion: 1,
    kind: "fleet-command",
    title: "Tokyo <checkout> / 東京",
    command: "stop-and-collect",
    outcome: "failed",
    startedAt: 1_000,
    finishedAt: 4_000,
    devices: ["device-1"],
    events: [
      {
        serial: "device-1",
        reportFile: "devices/device-1-report.json",
        name: "Tokyo checkout",
        mode: "static",
        outcome: "running",
        startedAt: 1_000,
        finishedAt: 3_000,
        startLatitude: 35.681236,
        startLongitude: 139.767125,
        timeZoneId: "Asia/Tokyo",
      },
    ],
    deviceReports: [{ serial: "device-1", outcome: "pulled", file: "devices/device-1-report.json" }],
  });

  assert.match(report, /tests="2" failures="1" errors="0" skipped="0" time="3\.000"/);
  assert.match(report, /Tokyo &lt;checkout&gt; \/ 東京/);
  assert.match(report, /name="Tokyo checkout \[device-1\]" time="2\.000"/);
  assert.match(report, /Unexpected final simulation outcome: running/);
  assert.match(report, /gps-lab\.kind/);
  assert.match(report, /gps-lab\.command/);
});
