import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { test } from "node:test";
import { canonicalLicensePayload, publicKeyBase64, signLicense, verifyLicense } from "../license.mjs";

test("enterprise licenses are signed and tamper evident", () => {
  const keys = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKey = keys.privateKey.export({ type: "pkcs8", format: "pem" });
  const license = signLicense({
    licenseId: "customer-1",
    organization: "QA Team",
    expiresAt: 0
  }, privateKey);

  assert.equal(verifyLicense(license, keys.publicKey), true);
  assert.equal(verifyLicense({ ...license, organization: "Other" }, keys.publicKey), false);
  assert.ok(publicKeyBase64(privateKey).length > 300);
});

test("device licenses are versioned, signed, and bound to one Android ID", () => {
  const keys = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKey = keys.privateKey.export({ type: "pkcs8", format: "pem" });
  const license = signLicense({
    licenseId: "device-1",
    organization: "Device QA",
    expiresAt: 0,
    deviceId: "A1B2C3D4E5F60718"
  }, privateKey);

  assert.equal(license.schemaVersion, 2);
  assert.equal(license.deviceId, "a1b2c3d4e5f60718");
  assert.equal(
    canonicalLicensePayload(license),
    "gps-updater-enterprise-license\n2\ndevice-1\nDevice QA\n0\na1b2c3d4e5f60718"
  );
  assert.equal(verifyLicense(license, keys.publicKey, "A1B2C3D4E5F60718"), true);
  assert.equal(verifyLicense(license, keys.publicKey, "1111222233334444"), false);
  assert.equal(verifyLicense({ ...license, deviceId: "1111222233334444" }, keys.publicKey), false);
});

test("license signing rejects ambiguous or Android-incompatible payload fields", () => {
  const keys = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKey = keys.privateKey.export({ type: "pkcs8", format: "pem" });

  assert.throws(() => signLicense({
    licenseId: "bad\nlicense",
    organization: "QA",
    expiresAt: 0,
  }, privateKey), /License ID/);
  assert.throws(() => signLicense({
    licenseId: "valid-license",
    organization: "QA\nTeam",
    expiresAt: 0,
  }, privateKey), /Organization/);
  assert.throws(() => signLicense({
    licenseId: "valid-license",
    organization: "QA Team",
    expiresAt: -1,
  }, privateKey), /expiry/);
});
