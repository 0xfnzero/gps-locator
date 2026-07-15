import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import { after, before, test } from "node:test";

const dataDirectory = await mkdtemp(join(tmpdir(), "gps-updater-sync-"));
const port = 18787 + Math.floor(Math.random() * 1000);
const baseUrl = `http://127.0.0.1:${port}`;
const id = "a".repeat(64);
const concurrentId = "d".repeat(64);
const legacyId = "e".repeat(64);
const authorization = "b".repeat(64);
let server;

before(async () => {
  server = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname], {
    env: {
      ...process.env,
      PORT: String(port),
      DATA_DIR: dataDirectory,
      MAX_BODY_BYTES: "1024",
      RATE_LIMIT_PER_MINUTE: "100"
    },
    stdio: ["ignore", "pipe", "inherit"]
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Server did not start")), 5000);
    server.stdout.on("data", (chunk) => {
      if (chunk.toString().includes("listening")) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });
});

after(async () => {
  server?.kill("SIGTERM");
  await rm(dataDirectory, { recursive: true, force: true });
});

test("health endpoint remains available without credentials", async () => {
  const response = await fetch(`${baseUrl}/health`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok" });
});

test("readiness verifies that persistent storage is writable", async () => {
  const response = await fetch(`${baseUrl}/ready`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ready" });
  assert.equal((await readdir(dataDirectory)).some((file) => file.startsWith(".ready.")), false);
});

test("liveness and readiness probes do not consume the request limit", async () => {
  for (let index = 0; index < 110; index += 1) {
    assert.equal((await fetch(`${baseUrl}/health`)).status, 200);
    assert.equal((await fetch(`${baseUrl}/ready`)).status, 200);
  }
});

test("rejects malformed paths and missing credentials", async () => {
  assert.equal((await fetch(`${baseUrl}/v1/sync/not-an-id`)).status, 404);
  assert.equal((await fetch(`${baseUrl}/v1/sync/${id}`)).status, 401);
});

test("rejects invalid and oversized encrypted payloads", async () => {
  const headers = { authorization: `Bearer ${authorization}`, "content-type": "application/json" };
  const malformed = await fetch(`${baseUrl}/v1/sync/${id}`, {
    method: "PUT",
    headers,
    body: "{"
  });
  assert.equal(malformed.status, 400);

  const invalidEnvelope = await fetch(`${baseUrl}/v1/sync/${id}`, {
    method: "PUT",
    headers,
    body: JSON.stringify({ version: 1, salt: "salt", nonce: "nonce", ciphertext: "data" })
  });
  assert.equal(invalidEnvelope.status, 400);

  const oversized = await fetch(`${baseUrl}/v1/sync/${id}`, {
    method: "PUT",
    headers,
    body: "x".repeat(1200)
  });
  assert.equal(oversized.status, 413);
});

test("stores, retrieves, protects, and deletes encrypted snapshots", async () => {
  const payload = {
    version: 1,
    salt: Buffer.alloc(16, 1).toString("base64url"),
    nonce: Buffer.alloc(12, 2).toString("base64url"),
    ciphertext: Buffer.alloc(32, 3).toString("base64url")
  };
  const headers = { authorization: `Bearer ${authorization}`, "content-type": "application/json" };

  const put = await fetch(`${baseUrl}/v1/sync/${id}`, {
    method: "PUT",
    headers,
    body: JSON.stringify(payload)
  });
  assert.equal(put.status, 200);
  const stored = await readFile(join(dataDirectory, `${id}.json`), "utf8");
  assert.equal(stored.includes(authorization), false);

  const get = await fetch(`${baseUrl}/v1/sync/${id}`, { headers });
  assert.equal(get.status, 200);
  assert.match(get.headers.get("etag"), /^"[a-f0-9]{64}"$/);
  assert.deepEqual(await get.json(), payload);

  const forbidden = await fetch(`${baseUrl}/v1/sync/${id}`, {
    headers: { authorization: `Bearer ${"c".repeat(64)}` }
  });
  assert.equal(forbidden.status, 403);

  const deleted = await fetch(`${baseUrl}/v1/sync/${id}`, { method: "DELETE", headers });
  assert.equal(deleted.status, 200);
  assert.equal((await fetch(`${baseUrl}/v1/sync/${id}`, { headers })).status, 404);
});

test("serializes concurrent conditional writes and rejects stale snapshots", async () => {
  const headers = { authorization: `Bearer ${authorization}`, "content-type": "application/json" };
  const initialPayload = encryptedPayload(4);
  const created = await fetch(`${baseUrl}/v1/sync/${concurrentId}`, {
    method: "PUT",
    headers: { ...headers, "if-none-match": "*" },
    body: JSON.stringify(initialPayload),
  });
  assert.equal(created.status, 200);
  const etag = created.headers.get("etag");
  assert.match(etag, /^"[a-f0-9]{64}"$/);

  const duplicateCreate = await fetch(`${baseUrl}/v1/sync/${concurrentId}`, {
    method: "PUT",
    headers: { ...headers, "if-none-match": "*" },
    body: JSON.stringify(encryptedPayload(5)),
  });
  assert.equal(duplicateCreate.status, 412);

  const candidates = [encryptedPayload(6), encryptedPayload(7)];
  const writes = await Promise.all(candidates.map((payload) => fetch(
    `${baseUrl}/v1/sync/${concurrentId}`,
    {
      method: "PUT",
      headers: { ...headers, "if-match": etag },
      body: JSON.stringify(payload),
    },
  )));
  assert.deepEqual(writes.map((response) => response.status).sort(), [200, 412]);

  const stored = await fetch(`${baseUrl}/v1/sync/${concurrentId}`, { headers });
  assert.equal(stored.status, 200);
  const storedPayload = await stored.json();
  assert.ok(candidates.some((candidate) => JSON.stringify(candidate) === JSON.stringify(storedPayload)));
  assert.equal((await fetch(`${baseUrl}/v1/sync/${concurrentId}`, {
    method: "PUT",
    headers: { ...headers, "if-match": etag },
    body: JSON.stringify(encryptedPayload(8)),
  })).status, 412);
  assert.equal((await readdir(dataDirectory)).some((file) => file.endsWith(".tmp")), false);
});

test("serves legacy disk records with a derived ETag", async () => {
  const payload = encryptedPayload(9);
  await writeFile(
    join(dataDirectory, `${legacyId}.json`),
    JSON.stringify({
      authorizationHash: createHash("sha256").update(authorization).digest("hex"),
      payload,
      updatedAt: 1,
    }),
  );

  const response = await fetch(`${baseUrl}/v1/sync/${legacyId}`, {
    headers: { authorization: `Bearer ${authorization}` },
  });

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), payload);
  assert.match(response.headers.get("etag"), /^"[a-f0-9]{64}"$/);
});

test("rate limits non-health requests", async () => {
  let status = 0;
  for (let index = 0; index < 130 && status !== 429; index += 1) {
    status = (await fetch(`${baseUrl}/missing-${index}`)).status;
  }
  assert.equal(status, 429);
  assert.equal((await fetch(`${baseUrl}/health`)).status, 200);
});

function encryptedPayload(fill) {
  return {
    version: 1,
    salt: Buffer.alloc(16, fill).toString("base64url"),
    nonce: Buffer.alloc(12, fill + 1).toString("base64url"),
    ciphertext: Buffer.alloc(32, fill + 2).toString("base64url"),
  };
}
