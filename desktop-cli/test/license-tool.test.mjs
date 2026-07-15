import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { afterEach, test } from "node:test";
import { publicKeyBase64, verifyLicense } from "../license.mjs";

const testDirectory = dirname(fileURLToPath(import.meta.url));
const cli = resolve(testDirectory, "../license-tool.mjs");
const temporaryDirectories = [];

afterEach(() => {
  while (temporaryDirectories.length > 0) {
    rmSync(temporaryDirectories.pop(), { recursive: true, force: true });
  }
});

test("license tool issues a verified device batch and SHA-256 delivery manifest", () => {
  const fixture = createFixture();
  const devicesFile = join(fixture.directory, "devices.tsv");
  const outputDirectory = join(fixture.directory, "licenses");
  writeFileSync(devicesFile, [
    "emulator-5554\ta1b2c3d4e5f60718",
    "qa-phone-01\t1111222233334444",
  ].join("\n"));

  const argumentsList = [
    "--private-key", fixture.privateKeyFile,
    "--organization", "Global QA",
    "--expires", "never",
    "--devices-file", devicesFile,
    "--license-id-prefix", "customer-2026",
    "--output-dir", outputDirectory,
  ];
  const result = runTool(argumentsList);

  assert.equal(result.status, 0, result.stderr);
  const manifest = JSON.parse(readFileSync(join(outputDirectory, "license-manifest.json"), "utf8"));
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.licenseCount, 2);
  assert.match(manifest.publicKeySha256, /^[a-f0-9]{64}$/);
  assert.match(manifest.signature, /^[a-zA-Z0-9+/]+={0,2}$/);
  for (const record of manifest.licenses) {
    const file = join(outputDirectory, record.file);
    const license = JSON.parse(readFileSync(file, "utf8"));
    assert.equal(license.schemaVersion, 2);
    assert.equal(verifyLicense(license, fixture.publicKey, record.deviceId), true);
    assert.match(record.sha256, /^[a-f0-9]{64}$/);
    assert.equal(statSync(file).mode & 0o777, 0o600);
  }
  assert.equal(statSync(join(outputDirectory, "license-manifest.json")).mode & 0o777, 0o600);
  const verification = runTool([
    "--verify-manifest", join(outputDirectory, "license-manifest.json"),
    "--public-key-file", fixture.publicKeyFile,
  ]);
  assert.equal(verification.status, 0, verification.stderr);
  assert.match(verification.stdout, /Verified 2 device licenses/);
  const pemVerification = runTool([
    "--verify-manifest", join(outputDirectory, "license-manifest.json"),
    "--public-key-file", fixture.publicKeyPemFile,
  ]);
  assert.equal(pemVerification.status, 0, pemVerification.stderr);

  const wrongKey = createFixture();
  const wrongKeyVerification = runTool([
    "--verify-manifest", join(outputDirectory, "license-manifest.json"),
    "--public-key-file", wrongKey.publicKeyFile,
  ]);
  assert.equal(wrongKeyVerification.status, 1);
  assert.match(wrongKeyVerification.stderr, /fingerprint does not match/);

  const unsafeManifest = {
    ...manifest,
    licenses: [
      { ...manifest.licenses[0], file: "../outside.license.json" },
      manifest.licenses[1],
    ],
  };
  const unsafeManifestFile = join(outputDirectory, "unsafe-manifest.json");
  writeFileSync(unsafeManifestFile, JSON.stringify(unsafeManifest));
  const unsafeVerification = runTool([
    "--verify-manifest", unsafeManifestFile,
    "--public-key-file", fixture.publicKeyFile,
  ]);
  assert.equal(unsafeVerification.status, 1);
  assert.match(unsafeVerification.stderr, /unsafe path/);

  const remappedManifest = {
    ...manifest,
    licenses: [
      { ...manifest.licenses[0], serial: "another-valid-serial" },
      manifest.licenses[1],
    ],
  };
  const remappedManifestFile = join(outputDirectory, "remapped-manifest.json");
  writeFileSync(remappedManifestFile, JSON.stringify(remappedManifest));
  const remappedVerification = runTool([
    "--verify-manifest", remappedManifestFile,
    "--public-key-file", fixture.publicKeyFile,
  ]);
  assert.equal(remappedVerification.status, 1);
  assert.match(remappedVerification.stderr, /manifest signature verification failed/i);

  const manifestBeforeRetry = readFileSync(join(outputDirectory, "license-manifest.json"), "utf8");
  const retry = runTool(argumentsList);
  assert.equal(retry.status, 1);
  assert.match(retry.stderr, /Refusing to overwrite existing files/);
  assert.equal(readFileSync(join(outputDirectory, "license-manifest.json"), "utf8"), manifestBeforeRetry);

  const tamperedFile = join(outputDirectory, manifest.licenses[0].file);
  writeFileSync(tamperedFile, readFileSync(tamperedFile, "utf8").replace("Global QA", "Other QA"));
  const tamperedVerification = runTool([
    "--verify-manifest", join(outputDirectory, "license-manifest.json"),
    "--public-key-file", fixture.publicKeyFile,
  ]);
  assert.equal(tamperedVerification.status, 1);
  assert.match(tamperedVerification.stderr, /SHA-256 mismatch/);
});

test("license tool validates the complete device batch before creating output", () => {
  const fixture = createFixture();
  const devicesFile = join(fixture.directory, "duplicate.tsv");
  const outputDirectory = join(fixture.directory, "licenses");
  writeFileSync(devicesFile, [
    "phone-1\ta1b2c3d4e5f60718",
    "phone-2\ta1b2c3d4e5f60718",
  ].join("\n"));

  const result = runTool([
    "--private-key", fixture.privateKeyFile,
    "--organization", "Global QA",
    "--devices-file", devicesFile,
    "--license-id-prefix", "customer-2026",
    "--output-dir", outputDirectory,
  ]);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /Duplicate app-scoped device ID/);
  assert.equal(existsSync(outputDirectory), false);
});

test("license tool help succeeds and argument mistakes are concise", () => {
  const help = runTool(["--help"]);
  assert.equal(help.status, 0, help.stderr);
  assert.match(help.stdout, /Device batch rows use/);

  const unknown = runTool(["--private-keey", "missing.pem"]);
  assert.equal(unknown.status, 1);
  assert.match(unknown.stderr, /^gps-license: Unknown argument/m);
  assert.doesNotMatch(unknown.stderr, /at parseArguments/);

  const duplicate = runTool(["--private-key", "one.pem", "--private-key", "two.pem"]);
  assert.equal(duplicate.status, 1);
  assert.match(duplicate.stderr, /Duplicate argument/);
});

function createFixture() {
  const directory = mkdtempSync(join(tmpdir(), "gps-license-tool-test-"));
  temporaryDirectories.push(directory);
  const keys = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const privateKey = keys.privateKey.export({ type: "pkcs8", format: "pem" });
  const privateKeyFile = join(directory, "private.pem");
  const publicKeyFile = join(directory, "public-key.txt");
  const publicKeyPemFile = join(directory, "public-key.pem");
  writeFileSync(privateKeyFile, privateKey, { mode: 0o600 });
  writeFileSync(publicKeyFile, `${publicKeyBase64(privateKey)}\n`);
  writeFileSync(
    publicKeyPemFile,
    keys.publicKey.export({ type: "spki", format: "pem" }),
  );
  chmodSync(privateKeyFile, 0o600);
  return {
    directory,
    privateKeyFile,
    publicKey: keys.publicKey,
    publicKeyFile,
    publicKeyPemFile,
  };
}

function runTool(argumentsList) {
  return spawnSync(process.execPath, [cli, ...argumentsList], { encoding: "utf8" });
}
