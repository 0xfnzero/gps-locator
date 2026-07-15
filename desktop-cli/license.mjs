import { createPrivateKey, createPublicKey, sign, verify } from "node:crypto";

export function canonicalLicensePayload(license) {
  const normalized = normalizeLicense(license);
  if (normalized.schemaVersion === undefined) {
    return `${normalized.licenseId}\n${normalized.organization}\n${normalized.expiresAt}`;
  }
  return [
    licenseDomain,
    deviceSchemaVersion,
    normalized.licenseId,
    normalized.organization,
    normalized.expiresAt,
    normalized.deviceId
  ].join("\n");
}

export function signLicense(license, privateKeyPem) {
  const normalized = normalizeLicense(license);
  const privateKey = createPrivateKey(privateKeyPem);
  const signature = sign(
    "RSA-SHA256",
    Buffer.from(canonicalLicensePayload(normalized)),
    privateKey
  ).toString("base64");
  return { ...normalized, signature };
}

export function publicKeyBase64(privateKeyPem) {
  return createPublicKey(createPrivateKey(privateKeyPem))
    .export({ type: "spki", format: "der" })
    .toString("base64");
}

export function verifyLicense(license, publicKey, expectedDeviceId) {
  const { signature, ...payload } = license;
  try {
    const normalized = normalizeLicense(payload);
    const signatureValid = verify(
      "RSA-SHA256",
      Buffer.from(canonicalLicensePayload(normalized)),
      publicKey,
      Buffer.from(signature, "base64")
    );
    if (!signatureValid) return false;
    if (normalized.deviceId && expectedDeviceId !== undefined) {
      return normalized.deviceId === normalizeDeviceId(expectedDeviceId);
    }
    return true;
  } catch {
    return false;
  }
}

function normalizeLicense(license) {
  const licenseId = normalizeLicenseId(license.licenseId);
  const organization = normalizeOrganization(license.organization);
  const expiresAt = normalizeExpiry(license.expiresAt);
  const deviceId = license.deviceId === undefined || license.deviceId === null
    ? undefined
    : normalizeDeviceId(license.deviceId);
  const schemaVersion = license.schemaVersion ?? (deviceId ? deviceSchemaVersion : legacySchemaVersion);
  if (schemaVersion === legacySchemaVersion) {
    if (deviceId) throw new Error("Legacy licenses cannot be device-bound");
    return {
      licenseId,
      organization,
      expiresAt
    };
  }
  if (schemaVersion !== deviceSchemaVersion) throw new Error("Unsupported license schema");
  if (!deviceId) throw new Error("A device-bound license requires --device-id");
  return {
    schemaVersion: deviceSchemaVersion,
    licenseId,
    organization,
    expiresAt,
    deviceId
  };
}

function normalizeLicenseId(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(normalized)) {
    throw new Error("License ID must be 1-128 portable characters");
  }
  return normalized;
}

function normalizeOrganization(value) {
  const normalized = typeof value === "string" ? value.trim() : "";
  if (!normalized || normalized.length > 160 || /[\u0000-\u001f\u007f]/.test(normalized)) {
    throw new Error("Organization must be 1-160 characters without control characters");
  }
  return normalized;
}

function normalizeExpiry(value) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error("License expiry must be zero or a non-negative integer timestamp");
  }
  return value;
}

function normalizeDeviceId(value) {
  const normalized = String(value).trim().toLowerCase();
  if (!/^[a-f0-9]{16}$/.test(normalized)) {
    throw new Error("Device ID must be a 16-character hexadecimal Android ID");
  }
  return normalized;
}

const legacySchemaVersion = 1;
const deviceSchemaVersion = 2;
const licenseDomain = "gps-updater-enterprise-license";
