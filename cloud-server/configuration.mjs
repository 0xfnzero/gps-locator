import { fileURLToPath } from "node:url";

export function loadConfiguration(environment = process.env) {
  return {
    port: integer(environment, "PORT", 8787, 1, 65_535),
    host: text(environment, "HOST", "127.0.0.1"),
    dataDirectory: text(environment, "DATA_DIR", fileURLToPath(new URL("./data", import.meta.url))),
    maxBodyBytes: integer(environment, "MAX_BODY_BYTES", 2_097_152, 1_024, 67_108_864),
    rateLimitPerMinute: integer(environment, "RATE_LIMIT_PER_MINUTE", 60, 1, 100_000),
    trustProxy: boolean(environment, "TRUST_PROXY", false),
  };
}

function integer(environment, name, fallback, minimum, maximum) {
  const raw = environment[name] ?? String(fallback);
  if (!/^[0-9]+$/.test(raw)) throw new Error(`${name} must be an integer`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function text(environment, name, fallback) {
  const value = (environment[name] ?? fallback).trim();
  if (!value) throw new Error(`${name} must not be blank`);
  return value;
}

function boolean(environment, name, fallback) {
  const raw = environment[name];
  if (raw === undefined) return fallback;
  if (raw === "true") return true;
  if (raw === "false") return false;
  throw new Error(`${name} must be true or false`);
}
