import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const moduleFile = fileURLToPath(import.meta.url);
const deployDirectory = dirname(moduleFile);
const serverDirectory = dirname(deployDirectory);
const caddyfile = join(deployDirectory, "Caddyfile");

export function validateDeployment(runCommand = spawnSync) {
  dockerPreflight(runCommand);
  runDeploymentGates(runCommand);
}

function runDeploymentGates(runCommand) {
  runChecked(
    runCommand,
    "Compose configuration",
    [
      "compose",
      "--file", join(deployDirectory, "compose.yaml"),
      "--env-file", join(deployDirectory, ".env.example"),
      "--profile", "tools",
      "config",
      "--quiet",
    ],
  );
  runChecked(
    runCommand,
    "Sync image build",
    ["build", "--tag", "gps-updater-sync:validation", "."],
  );
  runChecked(
    runCommand,
    "Caddy configuration",
    [
      "run", "--rm",
      "--env", "SYNC_DOMAIN=sync.example.com",
      "--env", "CADDY_EMAIL=ops@example.com",
      "--env", "CADDY_MAX_BODY_SIZE=3MB",
      "--volume", `${caddyfile}:/etc/caddy/Caddyfile:ro`,
      "caddy:2.10.2-alpine",
      "caddy", "validate",
      "--config", "/etc/caddy/Caddyfile",
      "--adapter", "caddyfile",
    ],
  );
}

export function dockerPreflight(runCommand = spawnSync) {
  const engine = runCommand(
    "docker",
    ["version", "--format", "{{.Server.Version}}"],
    preflightOptions(),
  );
  if (engine.error?.code === "ENOENT") {
    throw new Error("Docker CLI is not installed or is not on PATH");
  }
  if (timedOut(engine)) {
    throw new Error("Docker Engine readiness check timed out after 15 seconds");
  }
  if (engine.error || engine.status !== 0 || !engine.stdout?.trim()) {
    throw new Error(
      "Docker Engine is not running or not reachable. " +
      `Start Docker Engine (or Docker Desktop) and retry${commandDetail(engine)}`,
    );
  }

  const compose = runCommand(
    "docker",
    ["compose", "version", "--short"],
    preflightOptions(),
  );
  if (timedOut(compose)) {
    throw new Error("Docker Compose readiness check timed out after 15 seconds");
  }
  if (compose.error || compose.status !== 0 || !compose.stdout?.trim()) {
    throw new Error(
      `Docker Compose plugin is unavailable or unusable${commandDetail(compose)}`,
    );
  }
  return {
    engineVersion: engine.stdout.trim(),
    composeVersion: compose.stdout.trim(),
  };
}

function runChecked(runCommand, label, arguments_) {
  const result = runCommand("docker", arguments_, {
    cwd: serverDirectory,
    stdio: "inherit",
  });
  if (result.error) throw new Error(`${label} could not start: ${result.error.message}`);
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status ?? "unknown"}`);
  }
}

function preflightOptions() {
  return {
    cwd: serverDirectory,
    encoding: "utf8",
    timeout: 15_000,
    stdio: ["ignore", "pipe", "pipe"],
  };
}

function timedOut(result) {
  return result.error?.code === "ETIMEDOUT" || result.signal === "SIGTERM";
}

function commandDetail(result) {
  const detail = String(result.stderr || result.stdout || result.error?.message || "")
    .replaceAll(/\s+/g, " ")
    .trim()
    .slice(0, 300);
  return detail ? `. Details: ${detail}` : "";
}

if (process.argv[1] && resolve(process.argv[1]) === moduleFile) {
  try {
    const versions = dockerPreflight();
    console.log(`Docker Engine ${versions.engineVersion}; Compose ${versions.composeVersion}`);
    runDeploymentGates(spawnSync);
    console.log("Private deployment configuration is valid");
  } catch (error) {
    console.error(`Deployment validation failed: ${error.message}`);
    process.exitCode = 1;
  }
}
