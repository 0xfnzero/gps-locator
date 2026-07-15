import assert from "node:assert/strict";
import { test } from "node:test";
import { assessDeviceStatus, isDeviceStatusPayload } from "../device-status.mjs";

const payload = {
  schemaVersion: 1,
  applicationId: "com.gpsupdater.app",
  appVersion: "2.0.0",
  versionCode: 4,
  debug: false,
  tier: "enterprise",
  deviceId: "app-scoped-id",
  mockLocationReady: true,
  simulationActive: false,
  enterprise: { organization: "QA", expiresAt: 0, active: true, deviceBound: true },
};

test("device status validation accepts the Android schema and rejects partial payloads", () => {
  assert.equal(isDeviceStatusPayload(payload), true);
  assert.equal(isDeviceStatusPayload({ ...payload, mockLocationReady: "yes" }), false);
  assert.equal(isDeviceStatusPayload({ ...payload, enterprise: null }), false);
});

test("device readiness distinguishes setup, licensing, activity, and unavailable states", () => {
  assert.deepEqual(assessDeviceStatus({ ...payload, available: true }), {
    commandReady: true,
    readiness: "ready",
  });
  assert.equal(assessDeviceStatus({ ...payload, available: true, simulationActive: true }).readiness, "simulation_active");
  assert.equal(assessDeviceStatus({ ...payload, available: true, mockLocationReady: false }).readiness, "setup_required");
  assert.equal(assessDeviceStatus({
    ...payload,
    available: true,
    tier: "pro",
    enterprise: { organization: "", expiresAt: 0, active: false, deviceBound: false },
  }).readiness, "license_required");
  assert.deepEqual(assessDeviceStatus({ available: false }), {
    commandReady: false,
    readiness: "status_unavailable",
  });
});
