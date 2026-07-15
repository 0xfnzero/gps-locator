#!/usr/bin/env node

import {
  createHash,
  createPublicKey,
  sign as signBytes,
  verify as verifyBytes,
} from "node:crypto";
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { publicKeyBase64, signLicense, verifyLicense } from "./license.mjs";

const manifestDomain = "gps-updater-enterprise-license-manifest";

try {
  const values = parseArguments(process.argv.slice(2));
  assertKnownArguments(values);
  if (values.has("help")) {
    usage();
    process.exit(0);
  }
  if (values.has("verify-manifest")) {
    verifyDeliveryManifest(values);
    process.exit(0);
  }
  const privateKeyPath = required(values, "private-key");
  const privateKey = readFileSync(resolve(privateKeyPath), "utf8");
  if (values.has("print-public-key")) {
    console.log(publicKeyBase64(privateKey));
    process.exit(0);
  }
  const expiresAt = parseExpiry(values.get("expires"));
  if (values.has("devices-file")) {
    issueDeviceBatch(values, privateKey, expiresAt);
    process.exit(0);
  }
  const deviceId = optional(values, "device-id");
  const license = signLicense({
    licenseId: required(values, "license-id"),
    organization: required(values, "organization"),
    expiresAt,
    ...(deviceId ? { deviceId } : {})
  }, privateKey);
  verifySignedLicense(license, privateKey, deviceId);
  const output = resolve(values.get("output") ?? `${license.licenseId}.license.json`);
  writeFileSync(output, `${JSON.stringify(license, null, 2)}\n`, { mode: 0o600 });
  console.log(output);
} catch (error) {
  console.error(`gps-license: ${error.message}`);
  process.exit(1);
}

function parseExpiry(value) {
  if (!value || value === "never") return 0;
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) throw new Error("--expires must be an ISO date or 'never'");
  if (timestamp <= Date.now()) throw new Error("--expires must be in the future or 'never'");
  return timestamp;
}

function issueDeviceBatch(values, privateKey, expiresAt) {
  for (const incompatible of ["license-id", "device-id", "output"]) {
    if (values.has(incompatible)) {
      throw new Error(`--${incompatible} cannot be used with --devices-file`);
    }
  }
  const organization = required(values, "organization");
  const licenseIdPrefix = required(values, "license-id-prefix");
  const devices = parseDevicesFile(required(values, "devices-file"));
  const outputDirectory = resolve(required(values, "output-dir"));
  const publicKey = createPublicKey(privateKey);
  const publicKeyDer = Buffer.from(publicKeyBase64(privateKey), "base64");
  const publicKeySha256 = createHash("sha256").update(publicKeyDer).digest("hex");
  const planned = devices.map((device) => {
    const serialFileName = safeFileComponent(device.serial);
    const licenseId = `${licenseIdPrefix}-${serialFileName}`;
    const fileName = `${serialFileName}.license.json`;
    const license = signLicense({
      licenseId,
      organization,
      expiresAt,
      deviceId: device.deviceId,
    }, privateKey);
    if (!verifyLicense(license, publicKey, device.deviceId)) {
      throw new Error(`Self-verification failed for ${device.serial}`);
    }
    return { ...device, licenseId, fileName, license };
  });
  assertUnique(planned.map((record) => record.licenseId), "generated license ID");
  assertUnique(planned.map((record) => record.fileName), "generated file name");
  const manifestFile = join(outputDirectory, "license-manifest.json");
  const targetFiles = [...planned.map((record) => join(outputDirectory, record.fileName)), manifestFile];
  const collisions = targetFiles.filter(existsSync);
  if (collisions.length > 0) {
    throw new Error(
      `Refusing to overwrite existing files: ${collisions.map((file) => basename(file)).join(", ")}`,
    );
  }

  mkdirSync(outputDirectory, { recursive: true, mode: 0o700 });
  const records = [];
  for (const record of planned) {
    const document = `${JSON.stringify(record.license, null, 2)}\n`;
    writePrivateFile(join(outputDirectory, record.fileName), document);
    records.push({
      serial: record.serial,
      deviceId: record.deviceId,
      licenseId: record.licenseId,
      file: record.fileName,
      sha256: createHash("sha256").update(document).digest("hex"),
    });
  }
  const manifestPayload = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    organization: organization.trim(),
    expiresAt,
    publicKeySha256,
    licenseCount: records.length,
    licenses: records,
  };
  const manifest = {
    ...manifestPayload,
    signature: signBytes(
      "RSA-SHA256",
      Buffer.from(canonicalManifestPayload(manifestPayload)),
      privateKey,
    ).toString("base64"),
  };
  writePrivateFile(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(manifestFile);
}

function verifyDeliveryManifest(values) {
  assertOnlyArguments(values, ["verify-manifest", "public-key-file"], "manifest verification");
  const manifestFile = resolve(required(values, "verify-manifest"));
  const publicKey = readPublicKey(required(values, "public-key-file"));
  const publicKeyDer = publicKey.export({ type: "spki", format: "der" });
  const publicKeySha256 = createHash("sha256").update(publicKeyDer).digest("hex");
  const manifestDocument = readBoundedFile(manifestFile, 5_000_000, "License manifest");
  const manifest = parseJson(manifestDocument, "License manifest");
  validateManifest(manifest);
  if (manifest.publicKeySha256 !== publicKeySha256) {
    throw new Error("Manifest public-key fingerprint does not match the supplied public key");
  }

  const manifestDirectory = dirname(manifestFile);
  const serials = [];
  const deviceIds = [];
  const licenseIds = [];
  const files = [];
  for (const [index, record] of manifest.licenses.entries()) {
    validateManifestRecord(record, index);
    serials.push(record.serial);
    deviceIds.push(record.deviceId);
    licenseIds.push(record.licenseId);
    files.push(record.file);
  }
  assertUnique(serials, "manifest device serial");
  assertUnique(deviceIds, "manifest app-scoped device ID");
  assertUnique(licenseIds, "manifest license ID");
  assertUnique(files, "manifest license file");
  if (!verifyBytes(
    "RSA-SHA256",
    Buffer.from(canonicalManifestPayload(manifest)),
    publicKey,
    Buffer.from(manifest.signature, "base64"),
  )) {
    throw new Error("License manifest signature verification failed");
  }

  for (const record of manifest.licenses) {
    const licenseFile = join(manifestDirectory, record.file);
    const document = readBoundedFile(licenseFile, 1_000_000, `License file ${record.file}`);
    const sha256 = createHash("sha256").update(document).digest("hex");
    if (sha256 !== record.sha256) throw new Error(`SHA-256 mismatch for ${record.file}`);
    const license = parseJson(document, `License file ${record.file}`);
    if (license.schemaVersion !== 2 ||
        license.licenseId !== record.licenseId ||
        license.organization !== manifest.organization ||
        license.expiresAt !== manifest.expiresAt ||
        license.deviceId !== record.deviceId) {
      throw new Error(`Manifest mapping does not match ${record.file}`);
    }
    if (!verifyLicense(license, publicKey, record.deviceId)) {
      throw new Error(`Signature verification failed for ${record.file}`);
    }
  }
  console.log(`Verified ${manifest.licenseCount} device licenses: ${manifestFile}`);
}

function validateManifest(manifest) {
  if (!manifest || Array.isArray(manifest) || typeof manifest !== "object" ||
      manifest.schemaVersion !== 1 || typeof manifest.generatedAt !== "string" ||
      !Number.isFinite(Date.parse(manifest.generatedAt)) ||
      typeof manifest.organization !== "string" || !manifest.organization.trim() ||
      manifest.organization.length > 160 || /[\u0000-\u001f\u007f]/.test(manifest.organization) ||
      !Number.isSafeInteger(manifest.expiresAt) || manifest.expiresAt < 0 ||
      typeof manifest.publicKeySha256 !== "string" ||
      !/^[a-f0-9]{64}$/.test(manifest.publicKeySha256) ||
      !Number.isInteger(manifest.licenseCount) || manifest.licenseCount < 1 ||
      manifest.licenseCount > 1_000 || !Array.isArray(manifest.licenses) ||
      manifest.licenses.length !== manifest.licenseCount ||
      typeof manifest.signature !== "string" ||
      !/^[a-zA-Z0-9+/]+={0,2}$/.test(manifest.signature)) {
    throw new Error("License manifest has an invalid schema");
  }
}

function canonicalManifestPayload(manifest) {
  return `${manifestDomain}\n${JSON.stringify({
    schemaVersion: manifest.schemaVersion,
    generatedAt: manifest.generatedAt,
    organization: manifest.organization,
    expiresAt: manifest.expiresAt,
    publicKeySha256: manifest.publicKeySha256,
    licenseCount: manifest.licenseCount,
    licenses: manifest.licenses.map((record) => ({
      serial: record.serial,
      deviceId: record.deviceId,
      licenseId: record.licenseId,
      file: record.file,
      sha256: record.sha256,
    })),
  })}`;
}

function validateManifestRecord(record, index) {
  const row = `Manifest license ${index + 1}`;
  if (!record || Array.isArray(record) || typeof record !== "object" ||
      typeof record.serial !== "string" || !/^[a-zA-Z0-9._:-]{1,200}$/.test(record.serial) ||
      typeof record.deviceId !== "string" || !/^[a-f0-9]{16}$/.test(record.deviceId) ||
      typeof record.licenseId !== "string" ||
      !/^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/.test(record.licenseId) ||
      typeof record.file !== "string" || basename(record.file) !== record.file ||
      !/^[a-zA-Z0-9._-]+\.license\.json$/.test(record.file) ||
      typeof record.sha256 !== "string" || !/^[a-f0-9]{64}$/.test(record.sha256)) {
    throw new Error(`${row} is invalid or uses an unsafe path`);
  }
}

function readPublicKey(file) {
  const document = readBoundedFile(resolve(file), 1_000_000, "Public key").trim();
  try {
    if (document.startsWith("-----BEGIN PUBLIC KEY-----")) return createPublicKey(document);
    const compact = document.replaceAll(/\s+/g, "");
    if (!compact || !/^[a-zA-Z0-9+/]+={0,2}$/.test(compact)) {
      throw new Error("invalid Base64");
    }
    return createPublicKey({
      key: Buffer.from(compact, "base64"),
      format: "der",
      type: "spki",
    });
  } catch {
    throw new Error("Public key file must contain SPKI PEM or Base64 DER");
  }
}

function readBoundedFile(file, maximumBytes, label) {
  if (!existsSync(file) || !statSync(file).isFile()) throw new Error(`${label} was not found`);
  if (statSync(file).size > maximumBytes) throw new Error(`${label} is too large`);
  return readFileSync(file, "utf8");
}

function parseJson(document, label) {
  try {
    return JSON.parse(document);
  } catch {
    throw new Error(`${label} is not valid JSON`);
  }
}

function parseDevicesFile(file) {
  const input = readFileSync(resolve(file), "utf8");
  if (Buffer.byteLength(input) > 1_000_000) throw new Error("Device file is too large");
  const lines = input.split(/\r?\n/).filter((line) => line.trim() !== "");
  if (lines.length === 0 || lines.length > 1_000) {
    throw new Error("Device file must contain 1-1000 rows");
  }
  const devices = lines.map((line, index) => {
    const fields = line.split("\t");
    if (fields.length !== 2) {
      throw new Error(`Device row ${index + 1} must be serial<TAB>deviceId`);
    }
    const serial = fields[0].trim();
    const deviceId = fields[1].trim().toLowerCase();
    if (!/^[a-zA-Z0-9._:-]{1,200}$/.test(serial)) {
      throw new Error(`Device row ${index + 1} has an invalid serial`);
    }
    if (!/^[a-f0-9]{16}$/.test(deviceId)) {
      throw new Error(`Device row ${index + 1} has an invalid app-scoped device ID`);
    }
    return { serial, deviceId };
  });
  assertUnique(devices.map((device) => device.serial), "device serial");
  assertUnique(devices.map((device) => device.deviceId), "app-scoped device ID");
  return devices;
}

function verifySignedLicense(license, privateKey, deviceId) {
  if (!verifyLicense(license, createPublicKey(privateKey), deviceId)) {
    throw new Error("Signed license failed self-verification");
  }
}

function assertUnique(values, label) {
  if (new Set(values).size !== values.length) throw new Error(`Duplicate ${label}`);
}

function assertOnlyArguments(values, allowedKeys, mode) {
  const allowed = new Set(allowedKeys);
  const incompatible = [...values.keys()].filter((key) => !allowed.has(key));
  if (incompatible.length > 0) {
    throw new Error(`--${incompatible[0]} cannot be used for ${mode}`);
  }
}

function safeFileComponent(value) {
  return String(value).trim().replaceAll(/[^a-zA-Z0-9._-]+/g, "-")
    .replaceAll(/^-+|-+$/g, "") || "device";
}

function writePrivateFile(file, content) {
  writeFileSync(file, content, { mode: 0o600, flag: "wx" });
}

function usage() {
  console.log(`GPS Updater Enterprise License Tool

Usage:
  gps-license --private-key KEY.pem --print-public-key
  gps-license --private-key KEY.pem --license-id ID --organization NAME
    [--expires ISO_DATE|never] [--device-id ANDROID_ID] [--output FILE]
  gps-license --private-key KEY.pem --organization NAME --devices-file devices.tsv
    --license-id-prefix PREFIX --output-dir DIR [--expires ISO_DATE|never]
  gps-license --verify-manifest DIR/license-manifest.json --public-key-file PUBLIC_KEY

Device batch rows use: serial<TAB>app-scoped-device-id`);
}

function parseArguments(args) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (!argument.startsWith("--")) throw new Error(`Unexpected argument: ${argument}`);
    const key = argument.slice(2);
    if (values.has(key)) throw new Error(`Duplicate argument: --${key}`);
    const next = args[index + 1];
    if (!next || next.startsWith("--")) values.set(key, true);
    else {
      values.set(key, next);
      index += 1;
    }
  }
  return values;
}

function assertKnownArguments(values) {
  const allowed = new Set([
    "help",
    "private-key",
    "print-public-key",
    "license-id",
    "organization",
    "expires",
    "device-id",
    "output",
    "devices-file",
    "license-id-prefix",
    "output-dir",
    "verify-manifest",
    "public-key-file",
  ]);
  const unknown = [...values.keys()].filter((key) => !allowed.has(key));
  if (unknown.length > 0) throw new Error(`Unknown argument: --${unknown[0]}`);
}

function required(values, key) {
  const value = values.get(key);
  if (typeof value !== "string" || value === "") throw new Error(`--${key} is required`);
  return value;
}

function optional(values, key) {
  if (!values.has(key)) return undefined;
  return required(values, key);
}
