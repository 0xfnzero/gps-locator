export const deviceReadiness = Object.freeze({
  READY: "ready",
  SIMULATION_ACTIVE: "simulation_active",
  SETUP_REQUIRED: "setup_required",
  LICENSE_REQUIRED: "license_required",
  STATUS_UNAVAILABLE: "status_unavailable",
});

export function isDeviceStatusPayload(record, applicationId = "com.gpsupdater.app") {
  if (!record || Array.isArray(record) || typeof record !== "object") return false;
  const enterprise = record.enterprise;
  return Boolean(record.schemaVersion === 1 &&
    record.applicationId === applicationId &&
    typeof record.appVersion === "string" &&
    Number.isInteger(record.versionCode) && record.versionCode >= 0 &&
    typeof record.debug === "boolean" &&
    ["free", "pro", "enterprise"].includes(record.tier) &&
    typeof record.deviceId === "string" &&
    typeof record.mockLocationReady === "boolean" &&
    typeof record.simulationActive === "boolean" &&
    enterprise && !Array.isArray(enterprise) && typeof enterprise === "object" &&
    typeof enterprise.organization === "string" &&
    Number.isFinite(enterprise.expiresAt) && enterprise.expiresAt >= 0 &&
    typeof enterprise.active === "boolean" &&
    typeof enterprise.deviceBound === "boolean");
}

export function assessDeviceStatus(record) {
  if (record?.available !== true) {
    return { commandReady: false, readiness: deviceReadiness.STATUS_UNAVAILABLE };
  }
  if (!record.mockLocationReady) {
    return { commandReady: false, readiness: deviceReadiness.SETUP_REQUIRED };
  }
  return {
    commandReady: true,
    readiness: record.simulationActive
      ? deviceReadiness.SIMULATION_ACTIVE
      : deviceReadiness.READY,
  };
}
