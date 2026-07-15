import assert from "node:assert/strict";
import { test } from "node:test";
import { loadConfiguration } from "../configuration.mjs";

test("configuration uses safe local defaults", () => {
  const configuration = loadConfiguration({});

  assert.equal(configuration.host, "127.0.0.1");
  assert.equal(configuration.port, 8787);
  assert.equal(configuration.maxBodyBytes, 2_097_152);
  assert.equal(configuration.rateLimitPerMinute, 60);
  assert.equal(configuration.trustProxy, false);
  assert.ok(configuration.dataDirectory.endsWith("/cloud-server/data"));
});

test("configuration accepts explicit production values", () => {
  assert.deepEqual(
    loadConfiguration({
      HOST: "0.0.0.0",
      PORT: "9000",
      DATA_DIR: "/data",
      MAX_BODY_BYTES: "4194304",
      RATE_LIMIT_PER_MINUTE: "120",
      TRUST_PROXY: "true",
    }),
    {
      host: "0.0.0.0",
      port: 9000,
      dataDirectory: "/data",
      maxBodyBytes: 4_194_304,
      rateLimitPerMinute: 120,
      trustProxy: true,
    },
  );
});

test("configuration rejects values that would disable production limits", () => {
  for (const environment of [
    { PORT: "0" },
    { PORT: "not-a-number" },
    { DATA_DIR: " " },
    { MAX_BODY_BYTES: "0" },
    { MAX_BODY_BYTES: "unlimited" },
    { RATE_LIMIT_PER_MINUTE: "0" },
    { TRUST_PROXY: "yes" },
  ]) {
    assert.throws(() => loadConfiguration(environment));
  }
});
