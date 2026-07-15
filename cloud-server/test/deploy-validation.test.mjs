import assert from "node:assert/strict";
import { test } from "node:test";
import { dockerPreflight, validateDeployment } from "../deploy/validate.mjs";

test("deployment validation reports a missing Docker CLI clearly", () => {
  assert.throws(
    () => dockerPreflight(() => ({ error: Object.assign(new Error("missing"), { code: "ENOENT" }) })),
    /Docker CLI is not installed/,
  );
});

test("deployment validation distinguishes an unreachable engine and missing Compose", () => {
  assert.throws(
    () => dockerPreflight(() => ({ status: 1, stdout: "", stderr: "500 Internal Server Error" })),
    /Docker Engine is not running or not reachable.*500 Internal Server Error/,
  );
  let call = 0;
  assert.throws(
    () => dockerPreflight(() => {
      call += 1;
      return call === 1
        ? { status: 0, stdout: "28.0.0\n", stderr: "" }
        : { status: 1, stdout: "", stderr: "compose is not a docker command" };
    }),
    /Docker Compose plugin is unavailable.*compose is not a docker command/,
  );
});

test("deployment validation runs every gate after a successful preflight", () => {
  const calls = [];
  const runCommand = (command, arguments_, options) => {
    calls.push({ command, arguments: arguments_, options });
    if (arguments_[0] === "version") return { status: 0, stdout: "28.0.0\n", stderr: "" };
    if (arguments_[0] === "compose" && arguments_[1] === "version") {
      return { status: 0, stdout: "2.35.0\n", stderr: "" };
    }
    return { status: 0 };
  };

  validateDeployment(runCommand);

  assert.equal(calls.length, 5);
  assert.deepEqual(calls.slice(0, 2).map((call) => call.arguments.slice(0, 2)), [
    ["version", "--format"],
    ["compose", "version"],
  ]);
  assert.ok(calls[2].arguments.includes("config"));
  assert.equal(calls[3].arguments[0], "build");
  assert.ok(calls[4].arguments.includes("caddy:2.10.2-alpine"));
});
