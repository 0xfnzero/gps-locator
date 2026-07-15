import { createServer } from "node:http";
import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, rename, unlink, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { loadConfiguration } from "./configuration.mjs";

const {
  port,
  host,
  dataDirectory,
  maxBodyBytes,
  rateLimitPerMinute,
  trustProxy,
} = loadConfiguration();
const rateLimits = new Map();
const snapshotLocks = new Map();
let lastRateLimitMinute = -1;

await mkdir(dataDirectory, { recursive: true });

function json(response, status, body, additionalHeaders = {}) {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    ...additionalHeaders,
  });
  response.end(JSON.stringify(body));
}

function secretHash(value) {
  return createHash("sha256").update(value).digest("hex");
}

function matchesAuthorization(record, authorization) {
  if (typeof record.authorizationHash === "string" && /^[a-f0-9]{64}$/.test(record.authorizationHash)) {
    const stored = Buffer.from(record.authorizationHash, "hex");
    const candidate = createHash("sha256").update(authorization).digest();
    return timingSafeEqual(stored, candidate);
  }
  if (typeof record.authorization === "string") {
    const stored = createHash("sha256").update(record.authorization).digest();
    const candidate = createHash("sha256").update(authorization).digest();
    return timingSafeEqual(stored, candidate);
  }
  return false;
}

function authorize(request) {
  const value = request.headers.authorization ?? "";
  const match = /^Bearer ([a-f0-9]{64})$/.exec(value);
  return match?.[1] ?? null;
}

function snapshotPath(id) {
  return join(dataDirectory, `${id}.json`);
}

async function readSnapshot(id) {
  try {
    return JSON.parse(await readFile(snapshotPath(id), "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
}

async function storageIsReady() {
  const probePath = join(dataDirectory, `.ready.${process.pid}.${randomUUID()}.tmp`);
  try {
    await writeFile(probePath, "", { encoding: "utf8", flag: "wx", mode: 0o600 });
    await unlink(probePath);
    return true;
  } catch {
    await unlink(probePath).catch(() => {});
    return false;
  }
}

async function withSnapshotLock(id, operation) {
  const previous = snapshotLocks.get(id) ?? Promise.resolve();
  const current = previous.catch(() => {}).then(operation);
  snapshotLocks.set(id, current);
  try {
    return await current;
  } finally {
    if (snapshotLocks.get(id) === current) snapshotLocks.delete(id);
  }
}

function snapshotRevision(record) {
  if (typeof record?.revision === "string" && /^[a-f0-9]{64}$/.test(record.revision)) {
    return record.revision;
  }
  return secretHash(JSON.stringify(record?.payload ?? null));
}

function quotedEtag(record) {
  return `"${snapshotRevision(record)}"`;
}

function writePreconditionMatches(request, existing) {
  const ifNoneMatch = request.headers["if-none-match"];
  if (ifNoneMatch === "*" && existing) return false;
  const ifMatch = request.headers["if-match"];
  if (typeof ifMatch === "string") {
    return existing !== null && ifMatch === quotedEtag(existing);
  }
  return true;
}

async function readBody(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > maxBodyBytes) throw Object.assign(new Error("Payload too large"), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function readJsonBody(request) {
  const body = await readBody(request);
  try {
    return JSON.parse(body);
  } catch {
    throw Object.assign(new Error("Invalid JSON"), { status: 400 });
  }
}

function isBase64Url(value, minimumLength, maximumLength) {
  return typeof value === "string" &&
    value.length >= minimumLength && value.length <= maximumLength &&
    /^[A-Za-z0-9_-]+$/.test(value);
}

function validEncryptedPayload(payload) {
  return payload && payload.version === 1 &&
    isBase64Url(payload.salt, 22, 22) &&
    isBase64Url(payload.nonce, 16, 16) &&
    isBase64Url(payload.ciphertext, 22, maxBodyBytes);
}

function clientAddress(request) {
  if (trustProxy) {
    const forwarded = request.headers["x-forwarded-for"];
    if (typeof forwarded === "string" && forwarded.trim()) return forwarded.split(",")[0].trim();
  }
  return request.socket.remoteAddress ?? "unknown";
}

function isRateLimited(request) {
  const currentMinute = Math.floor(Date.now() / 60_000);
  if (currentMinute !== lastRateLimitMinute) {
    rateLimits.clear();
    lastRateLimitMinute = currentMinute;
  }
  const address = clientAddress(request);
  const existing = rateLimits.get(address);
  if (!existing || existing.minute !== currentMinute) {
    rateLimits.set(address, { minute: currentMinute, count: 1 });
    return false;
  }
  existing.count += 1;
  return existing.count > rateLimitPerMinute;
}

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (request.method === "GET" && url.pathname === "/health") {
      return json(response, 200, { status: "ok" });
    }
    if (request.method === "GET" && url.pathname === "/ready") {
      return await storageIsReady()
        ? json(response, 200, { status: "ready" })
        : json(response, 503, { error: "storage_unavailable" });
    }
    if (isRateLimited(request)) return json(response, 429, { error: "rate_limited" });

    const match = /^\/v1\/sync\/([a-f0-9]{64})$/.exec(url.pathname);
    if (!match) return json(response, 404, { error: "not_found" });
    const id = match[1];
    const authorization = authorize(request);
    if (!authorization) return json(response, 401, { error: "unauthorized" });
    if (request.method === "PUT") {
      const payload = await readJsonBody(request);
      if (!validEncryptedPayload(payload)) return json(response, 400, { error: "invalid_payload" });
      return await withSnapshotLock(id, async () => {
        const existing = await readSnapshot(id);
        if (existing && !matchesAuthorization(existing, authorization)) {
          return json(response, 403, { error: "forbidden" });
        }
        if (!writePreconditionMatches(request, existing)) {
          return json(response, 412, { error: "snapshot_conflict" });
        }
        const record = {
          authorizationHash: secretHash(authorization),
          payload,
          revision: secretHash(JSON.stringify(payload)),
          updatedAt: Date.now(),
        };
        const temporaryPath = `${snapshotPath(id)}.${process.pid}.${randomUUID()}.tmp`;
        try {
          await writeFile(temporaryPath, JSON.stringify(record), { encoding: "utf8", mode: 0o600 });
          await rename(temporaryPath, snapshotPath(id));
        } finally {
          await unlink(temporaryPath).catch(() => {});
        }
        return json(
          response,
          200,
          { updatedAt: record.updatedAt },
          { etag: quotedEtag(record) },
        );
      });
    }

    if (request.method === "DELETE") {
      return await withSnapshotLock(id, async () => {
        const existing = await readSnapshot(id);
        if (!existing) return json(response, 404, { error: "snapshot_not_found" });
        if (!matchesAuthorization(existing, authorization)) {
          return json(response, 403, { error: "forbidden" });
        }
        await unlink(snapshotPath(id));
        return json(response, 200, { deleted: true });
      });
    }

    const existing = await readSnapshot(id);
    if (!existing) return json(response, 404, { error: "snapshot_not_found" });
    if (!matchesAuthorization(existing, authorization)) {
      return json(response, 403, { error: "forbidden" });
    }
    if (request.method === "GET") {
      return json(response, 200, existing.payload, { etag: quotedEtag(existing) });
    }
    return json(response, 405, { error: "method_not_allowed" });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    if (status === 500) console.error(error);
    return json(response, status, { error: status === 500 ? "internal_error" : error.message });
  }
});

server.listen(port, host, () => {
  console.log(`GPS Updater sync server listening on http://${host}:${port}`);
});

function shutdown() {
  server.close(() => process.exit(0));
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
