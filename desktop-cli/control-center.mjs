#!/usr/bin/env node

import { spawn } from "node:child_process";
import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createServer } from "node:http";
import { basename, dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { fleetSummaryToJUnit } from "./junit-report.mjs";
import { assessDeviceStatus, isDeviceStatusPayload } from "./device-status.mjs";
import { validateStaticScenario } from "./scenario.mjs";
import { loadScenarioSuite } from "./suite.mjs";

const moduleFile = fileURLToPath(import.meta.url);
const moduleDirectory = dirname(moduleFile);
const defaultSuiteDirectory = join(moduleDirectory, "suites");
const defaultReportDirectory = join(moduleDirectory, "control-center-reports");
const staticDirectory = join(moduleDirectory, "web");
const productImage = join(staticDirectory, "assets/product-map.png");
const maximumBodyBytes = 16 * 1024;
const maximumLogCharacters = 100_000;
const maximumProcessOutputCharacters = 1_000_000;

export class ControlCenterError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export class ControlCenterService {
  constructor(options = {}) {
    this.suiteDirectory = resolve(options.suiteDirectory ?? defaultSuiteDirectory);
    this.reportDirectory = resolve(options.reportDirectory ?? defaultReportDirectory);
    this.cliFile = resolve(options.cliFile ?? join(moduleDirectory, "gps-lab.mjs"));
    this.cliDirectory = resolve(options.cliDirectory ?? moduleDirectory);
    this.runCli = options.runCli ?? createCliRunner({
      cliFile: this.cliFile,
      cwd: this.cliDirectory,
      env: options.env,
    });
    this.jobs = new Map();
    this.startingJob = false;
    mkdirSync(this.reportDirectory, { recursive: true });
  }

  async bootstrap() {
    const [{ devices, error: deviceError }, suites] = await Promise.all([
      this.connectedDevices(),
      Promise.resolve(this.discoverSuites()),
    ]);
    return {
      devices,
      deviceError,
      suites,
      activeJob: this.activeJob() ? publicJob(this.activeJob()) : null,
      recentRuns: this.recentRuns(),
    };
  }

  discoverSuites() {
    if (!existsSync(this.suiteDirectory)) return [];
    return walkJsonFiles(this.suiteDirectory).map((file) => {
      const id = relative(this.suiteDirectory, file).split(sep).join("/");
      try {
        const suite = loadScenarioSuite(file);
        const types = [...new Set(suite.steps.map((step) => step.compiled.type))];
        const timeZones = [...new Set(
          suite.steps.map((step) => step.compiled.timeZone).filter(Boolean),
        )];
        return {
          id,
          name: suite.name,
          valid: true,
          steps: suite.steps.length,
          types,
          timeZones,
          sha256: suite.sha256,
        };
      } catch (error) {
        return {
          id,
          name: basename(file, ".json"),
          valid: false,
          error: error.message,
        };
      }
    }).sort((left, right) => left.name.localeCompare(right.name));
  }

  suiteRecord(suiteId) {
    if (typeof suiteId !== "string" || suiteId.length === 0 || suiteId.length > 300) {
      throw new ControlCenterError(400, "A valid suite ID is required");
    }
    const record = this.discoverSuites().find((suite) => suite.id === suiteId);
    if (!record) throw new ControlCenterError(404, "Suite not found");
    if (!record.valid) throw new ControlCenterError(422, record.error);
    const file = resolve(this.suiteDirectory, ...suiteId.split("/"));
    if (!isWithin(this.suiteDirectory, file)) {
      throw new ControlCenterError(400, "Suite path is outside the configured directory");
    }
    return { record, file };
  }

  async connectedDevices() {
    const result = await this.runCli(["status"]);
    if (result.exitCode !== 0) {
      return {
        devices: [],
        error: conciseError(result, "ADB device status discovery failed"),
      };
    }
    try {
      const records = result.stdout.split("\n").map((value) => value.trim()).filter(Boolean)
        .map((value) => normalizeDeviceStatus(JSON.parse(value)));
      const devices = [...new Map(records.map((device) => [device.serial, device])).values()];
      return { devices, error: null };
    } catch (error) {
      return {
        devices: [],
        error: `ADB device status discovery returned invalid data: ${error.message}`,
      };
    }
  }

  async validateSuite(suiteId) {
    const { file } = this.suiteRecord(suiteId);
    const result = await this.runCli(["suite", "--file", file, "--dry-run"]);
    if (result.exitCode !== 0) {
      throw new ControlCenterError(422, conciseError(result, "Suite validation failed"));
    }
    return { output: result.stdout.trim() };
  }

  async startSuite(suiteId, requestedDevices) {
    if (this.activeJob() || this.startingJob) {
      throw new ControlCenterError(409, "Another suite is already running");
    }
    this.startingJob = true;
    try {
      const { record, file } = this.suiteRecord(suiteId);
      const deviceSelection = await this.deviceSelection(requestedDevices);
      const startedAt = Date.now();
      const reportId = [
        safeFileComponent(record.name),
        new Date(startedAt).toISOString().replaceAll(/[:.]/g, "-"),
        randomUUID().slice(0, 8),
      ].join("-");
      const outputDirectory = join(this.reportDirectory, reportId);
      const job = {
        id: randomUUID(),
        suiteId,
        suiteName: record.name,
        devices: deviceSelection.devices,
        reportId,
        outputDirectory,
        status: "running",
        startedAt,
        finishedAt: null,
        exitCode: null,
        log: "",
        summary: null,
        error: null,
      };
      this.jobs.set(job.id, job);
      this.pruneJobs();
      const command = [
        "suite",
        "--file", file,
        "--serial", deviceSelection.argument,
        "--output", outputDirectory,
      ];
      void this.runCli(command, (channel, chunk) => {
        appendJobLog(job, channel, chunk);
      }).then((result) => {
        job.exitCode = result.exitCode;
        job.finishedAt = Date.now();
        job.status = result.exitCode === 0 ? "completed" : "failed";
        if (result.exitCode !== 0) job.error = conciseError(result, "Suite execution failed");
        const summaryFile = join(outputDirectory, "suite-summary.json");
        if (existsSync(summaryFile)) {
          job.summary = readJsonFile(summaryFile, "Suite summary");
        }
      }).catch((error) => {
        job.exitCode = -1;
        job.finishedAt = Date.now();
        job.status = "failed";
        job.error = error.message;
        appendJobLog(job, "stderr", `${error.message}\n`);
      });
      return publicJob(job);
    } finally {
      this.startingJob = false;
    }
  }

  async stopDevices(requestedDevices) {
    if (this.activeJob() || this.startingJob) {
      throw new ControlCenterError(409, "Wait for the active suite to finish before stopping devices");
    }
    const deviceSelection = await this.deviceSelection(requestedDevices);
    const result = await this.runCli(["stop", "--serial", deviceSelection.argument]);
    if (result.exitCode !== 0) {
      throw new ControlCenterError(502, conciseError(result, "Stopping simulations failed"));
    }
    const reportStartedAt = Date.now();
    const reportId = [
      "Fleet-stop",
      new Date(reportStartedAt).toISOString().replaceAll(/[:.]/g, "-"),
      randomUUID().slice(0, 8),
    ].join("-");
    const outputDirectory = join(this.reportDirectory, reportId);
    const deviceReportDirectory = join(outputDirectory, "devices");
    mkdirSync(deviceReportDirectory, { recursive: true });
    const reportResult = await this.runCli([
      "report",
      "--serial", deviceSelection.argument,
      "--output", deviceReportDirectory,
    ]);
    const summary = createFleetSummary({
      devices: deviceSelection.devices,
      deviceReportDirectory,
      reportStartedAt,
      reportFinishedAt: Date.now(),
      reportResult,
    });
    summary.junitFile = "fleet-results.junit.xml";
    summary.junitSchemaVersion = 1;
    writeFileSync(
      join(outputDirectory, "fleet-summary.json"),
      `${JSON.stringify(summary, null, 2)}\n`,
    );
    writeFileSync(
      join(outputDirectory, summary.junitFile),
      fleetSummaryToJUnit(summary),
    );
    const warning = reportResult.exitCode !== 0
      ? conciseError(reportResult, "Report collection failed")
      : summary.outcome === "failed"
        ? "One or more device reports could not be retained"
        : null;
    return {
      devices: deviceSelection.devices,
      output: [result.stdout.trim(), reportResult.stdout.trim()].filter(Boolean).join("\n"),
      reportId,
      reportName: summary.title,
      reportOutcome: summary.outcome,
      warning,
    };
  }

  async prepareDevices(requestedDevices) {
    if (this.activeJob() || this.startingJob) {
      throw new ControlCenterError(409, "Wait for the active suite to finish before preparing devices");
    }
    const deviceSelection = await this.deviceSelection(requestedDevices);
    const commandResults = [];
    for (const serial of deviceSelection.devices) {
      const result = await this.runCli(["prepare", "--serial", serial]);
      commandResults.push({ serial, result });
    }

    const discovered = await this.connectedDevices();
    const statusBySerial = new Map(
      discovered.error ? [] : discovered.devices.map((device) => [device.serial, device]),
    );
    const results = commandResults.map(({ serial, result }) => {
      if (result.exitCode !== 0) {
        return {
          serial,
          outcome: "failed",
          error: conciseError(result, "Device preparation failed"),
        };
      }
      const status = statusBySerial.get(serial);
      if (!status?.available) {
        return {
          serial,
          outcome: "unverified",
          error: status?.error ?? discovered.error ?? "Device status was unavailable after preparation",
        };
      }
      if (!status.mockLocationReady) {
        return {
          serial,
          outcome: "unverified",
          error: "Android still reports that GPS Locator is not the mock-location app",
        };
      }
      return { serial, outcome: "prepared" };
    });
    const completedCount = results.filter((result) => result.outcome === "prepared").length;
    const failedCount = results.filter((result) => result.outcome === "failed").length;
    const outcome = completedCount === results.length
      ? "completed"
      : failedCount === results.length
        ? "failed"
        : "incomplete";
    return {
      devices: deviceSelection.devices,
      outcome,
      completedCount,
      failedCount,
      unverifiedCount: results.length - completedCount - failedCount,
      results,
      output: results.map((result) => [
        `${result.serial}: ${result.outcome}`,
        result.error ? ` (${result.error})` : "",
      ].join("")).join("\n"),
    };
  }

  async setLocation(requestedDevices, input) {
    if (this.activeJob() || this.startingJob) {
      throw new ControlCenterError(409, "Wait for the active suite to finish before setting a location");
    }
    if (!input || typeof input !== "object" || Array.isArray(input)) {
      throw new ControlCenterError(400, "Location details are required");
    }
    if (typeof input.name !== "string" || input.name.trim().length > 120) {
      throw new ControlCenterError(400, "Location name must be between 1 and 120 characters");
    }
    let location;
    try {
      location = validateStaticScenario({
        type: "static",
        name: input.name,
        latitude: input.latitude,
        longitude: input.longitude,
        timeZone: input.timeZone == null || input.timeZone === "" ? null : input.timeZone,
      });
    } catch (error) {
      throw new ControlCenterError(400, error.message);
    }
    const deviceSelection = await this.deviceSelection(requestedDevices);
    const command = [
      "set",
      "--serial", deviceSelection.argument,
      "--lat", String(location.latitude),
      "--lon", String(location.longitude),
      "--name", location.name,
    ];
    if (location.timeZone) command.push("--time-zone", location.timeZone);
    const result = await this.runCli(command);
    if (result.exitCode !== 0) {
      throw new ControlCenterError(502, conciseError(result, "Setting the fleet location failed"));
    }
    return {
      devices: deviceSelection.devices,
      location,
      output: result.stdout.trim(),
    };
  }

  async deviceSelection(requestedDevices) {
    if (!Array.isArray(requestedDevices) || requestedDevices.length === 0) {
      throw new ControlCenterError(400, "Select at least one connected device");
    }
    if (requestedDevices.length > 100 || requestedDevices.some((serial) => (
      typeof serial !== "string" || serial.trim() === "" || serial.includes(",")
    ))) {
      throw new ControlCenterError(400, "Device selection is invalid");
    }
    const normalized = [...new Set(requestedDevices.map((serial) => serial.trim()))];
    const discovered = await this.connectedDevices();
    if (discovered.error) throw new ControlCenterError(503, discovered.error);
    const connectedSerials = discovered.devices.map((device) => device.serial);
    const missing = normalized.filter((serial) => !connectedSerials.includes(serial));
    if (missing.length > 0) {
      throw new ControlCenterError(409, `Devices are no longer connected: ${missing.join(", ")}`);
    }
    const allSelected = normalized.length === connectedSerials.length &&
      connectedSerials.every((serial) => normalized.includes(serial));
    return {
      devices: normalized,
      argument: allSelected ? "all" : normalized.join(","),
    };
  }

  activeJob() {
    return [...this.jobs.values()].find((job) => job.status === "running") ?? null;
  }

  job(jobId) {
    const job = this.jobs.get(jobId);
    if (!job) throw new ControlCenterError(404, "Run job not found");
    return publicJob(job);
  }

  recentRuns() {
    if (!existsSync(this.reportDirectory)) return [];
    const runs = [];
    for (const entry of readdirSync(this.reportDirectory, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const summaryFile = summaryFileFor(join(this.reportDirectory, entry.name));
      if (!summaryFile || statSync(summaryFile).size > 5_000_000) continue;
      try {
        const summary = readJsonFile(summaryFile, "Run summary");
        ensureFleetJUnit(join(this.reportDirectory, entry.name), summary);
        runs.push(summaryRecord(entry.name, summary, join(this.reportDirectory, entry.name)));
      } catch {
        // An incomplete report directory is ignored until its summary is valid.
      }
    }
    return runs.sort((left, right) => right.finishedAt - left.finishedAt).slice(0, 20);
  }

  runSummary(reportId) {
    if (typeof reportId !== "string" || !/^[a-zA-Z0-9._-]{1,220}$/.test(reportId)) {
      throw new ControlCenterError(400, "Invalid report ID");
    }
    const report = this.recentRuns().find((candidate) => candidate.id === reportId);
    if (!report) throw new ControlCenterError(404, "Run report not found");
    const summaryFile = summaryFileFor(join(this.reportDirectory, reportId));
    if (!summaryFile) throw new ControlCenterError(404, "Run summary not found");
    return readJsonFile(summaryFile, "Run summary");
  }

  runArtifact(reportId, artifactKind) {
    if (typeof reportId !== "string" || !/^[a-zA-Z0-9._-]{1,220}$/.test(reportId)) {
      throw new ControlCenterError(400, "Invalid report ID");
    }
    if (!["summary", "junit"].includes(artifactKind)) {
      throw new ControlCenterError(400, "Invalid artifact kind");
    }
    const run = this.recentRuns().find((candidate) => candidate.id === reportId);
    if (!run) throw new ControlCenterError(404, "Run report not found");
    const directory = join(this.reportDirectory, reportId);
    const file = artifactKind === "summary"
      ? summaryFileFor(directory)
      : junitFileFor(directory);
    if (!file || !existsSync(file) || statSync(file).size > 10_000_000) {
      throw new ControlCenterError(404, "Run artifact not found");
    }
    const extension = artifactKind === "summary" ? "json" : "xml";
    return {
      file,
      contentType: artifactKind === "summary" ? "application/json" : "application/xml",
      downloadName: `${safeFileComponent(run.name)}-${artifactKind}.${extension}`,
    };
  }

  pruneJobs() {
    const finished = [...this.jobs.values()]
      .filter((job) => job.status !== "running")
      .sort((left, right) => (right.finishedAt ?? 0) - (left.finishedAt ?? 0));
    for (const job of finished.slice(20)) this.jobs.delete(job.id);
  }
}

function normalizeDeviceStatus(record) {
  if (!record || Array.isArray(record) || typeof record !== "object") {
    throw new Error("device record must be an object");
  }
  if (typeof record.serial !== "string" || record.serial.trim() === "" ||
      record.serial.length > 200 || record.serial.includes(",")) {
    throw new Error("device record has an invalid serial");
  }
  const serial = record.serial.trim();
  if (record.available !== true) {
    const unavailableStatus = {
      serial,
      available: false,
      error: typeof record.error === "string" && record.error.trim()
        ? record.error.trim().slice(0, 500)
        : "The GPS Lab app did not return device status",
    };
    return { ...unavailableStatus, ...assessDeviceStatus(unavailableStatus) };
  }
  if (!isDeviceStatusPayload(record)) {
    throw new Error(`${serial} has an invalid device status payload`);
  }
  const enterprise = record.enterprise;
  const normalized = {
    serial,
    available: true,
    schemaVersion: 1,
    applicationId: "com.gpsupdater.app",
    appVersion: record.appVersion.slice(0, 80),
    versionCode: record.versionCode,
    debug: record.debug,
    tier: record.tier.slice(0, 40),
    deviceId: record.deviceId.slice(0, 200),
    mockLocationReady: record.mockLocationReady,
    simulationActive: record.simulationActive,
    enterprise: {
      organization: enterprise.organization.slice(0, 160),
      expiresAt: enterprise.expiresAt,
      active: enterprise.active,
      deviceBound: enterprise.deviceBound,
    },
  };
  return { ...normalized, ...assessDeviceStatus(normalized) };
}

export function createControlCenter(options = {}) {
  const host = options.host ?? "127.0.0.1";
  const port = options.port ?? 43_821;
  if (!isLoopbackHost(host)) {
    throw new Error("The control center may only listen on localhost or a loopback address");
  }
  if (!Number.isInteger(port) || port < 0 || port > 65_535) {
    throw new Error("Port must be an integer between 0 and 65535");
  }
  const service = options.service ?? new ControlCenterService(options);
  const controlToken = randomBytes(32).toString("base64url");
  const server = createServer((request, response) => {
    void handleRequest({ request, response, service, controlToken }).catch((error) => {
      const status = error instanceof ControlCenterError ? error.status : 500;
      sendJson(response, status, {
        error: status === 500 ? "Control center request failed" : error.message,
      });
    });
  });
  return {
    server,
    service,
    async start() {
      await new Promise((resolveStart, rejectStart) => {
        server.once("error", rejectStart);
        server.listen(port, host, () => {
          server.off("error", rejectStart);
          resolveStart();
        });
      });
      const address = server.address();
      const displayHost = host === "::1" ? "[::1]" : host;
      return {
        host,
        port: address.port,
        url: `http://${displayHost}:${address.port}/`,
      };
    },
    async close() {
      if (!server.listening) return;
      await new Promise((resolveClose, rejectClose) => {
        server.close((error) => error ? rejectClose(error) : resolveClose());
      });
    },
  };
}

async function handleRequest({ request, response, service, controlToken }) {
  assertLocalHostHeader(request.headers.host);
  const url = new URL(request.url ?? "/", "http://localhost");
  if (request.method === "GET" && url.pathname === "/api/bootstrap") {
    sendJson(response, 200, { ...(await service.bootstrap()), controlToken });
    return;
  }
  if (request.method === "GET" && url.pathname.startsWith("/api/jobs/")) {
    sendJson(response, 200, service.job(decodeURIComponent(url.pathname.slice(10))));
    return;
  }
  const artifactMatch = url.pathname.match(/^\/api\/runs\/([^/]+)\/artifacts\/(summary|junit)$/);
  if (request.method === "GET" && artifactMatch) {
    sendDownload(
      response,
      service.runArtifact(decodeURIComponent(artifactMatch[1]), artifactMatch[2]),
    );
    return;
  }
  if (request.method === "GET" && url.pathname.startsWith("/api/runs/")) {
    sendJson(response, 200, service.runSummary(decodeURIComponent(url.pathname.slice(10))));
    return;
  }
  if (request.method === "POST" && url.pathname.startsWith("/api/")) {
    assertMutationRequest(request, controlToken);
    const body = await readJsonBody(request);
    if (url.pathname === "/api/suites/validate") {
      sendJson(response, 200, await service.validateSuite(body.suiteId));
      return;
    }
    if (url.pathname === "/api/suites/run") {
      sendJson(response, 202, await service.startSuite(body.suiteId, body.deviceSerials));
      return;
    }
    if (url.pathname === "/api/devices/stop") {
      sendJson(response, 200, await service.stopDevices(body.deviceSerials));
      return;
    }
    if (url.pathname === "/api/devices/prepare") {
      sendJson(response, 200, await service.prepareDevices(body.deviceSerials));
      return;
    }
    if (url.pathname === "/api/location/set") {
      sendJson(response, 200, await service.setLocation(body.deviceSerials, body.location));
      return;
    }
  }
  if (request.method === "GET") {
    const staticAsset = staticAssetFor(url.pathname);
    if (staticAsset) {
      sendFile(response, staticAsset.file, staticAsset.contentType);
      return;
    }
  }
  sendJson(response, 404, { error: "Not found" });
}

function staticAssetFor(pathname) {
  if (pathname === "/") return { file: join(staticDirectory, "index.html"), contentType: "text/html; charset=utf-8" };
  if (pathname === "/app.js") return { file: join(staticDirectory, "app.js"), contentType: "text/javascript; charset=utf-8" };
  if (pathname === "/styles.css") return { file: join(staticDirectory, "styles.css"), contentType: "text/css; charset=utf-8" };
  if (pathname === "/assets/product-map.png") return { file: productImage, contentType: "image/png" };
  return null;
}

function sendFile(response, file, contentType) {
  if (!existsSync(file)) throw new ControlCenterError(404, "Asset not found");
  const content = readFileSync(file);
  applySecurityHeaders(response);
  response.writeHead(200, {
    "Content-Type": contentType,
    "Cache-Control": "no-store",
  });
  response.end(content);
}

function sendDownload(response, artifact) {
  const content = readFileSync(artifact.file);
  applySecurityHeaders(response);
  response.writeHead(200, {
    "Content-Type": `${artifact.contentType}; charset=utf-8`,
    "Content-Disposition": `attachment; filename="${artifact.downloadName}"`,
    "Content-Length": content.length,
    "Cache-Control": "no-store",
  });
  response.end(content);
}

function sendJson(response, status, value) {
  if (response.headersSent) return;
  applySecurityHeaders(response);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  response.end(`${JSON.stringify(value)}\n`);
}

function applySecurityHeaders(response) {
  response.setHeader(
    "Content-Security-Policy",
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; " +
      "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
  );
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Referrer-Policy", "no-referrer");
  response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
  response.setHeader("X-Frame-Options", "DENY");
}

function assertLocalHostHeader(hostHeader) {
  if (typeof hostHeader !== "string" || !(
    /^localhost(?::\d+)?$/i.test(hostHeader) ||
    /^127\.0\.0\.1(?::\d+)?$/.test(hostHeader) ||
    /^\[::1\](?::\d+)?$/.test(hostHeader)
  )) {
    throw new ControlCenterError(403, "The control center accepts loopback hostnames only");
  }
}

function assertMutationRequest(request, expectedToken) {
  const contentType = request.headers["content-type"] ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new ControlCenterError(415, "Mutating requests require JSON");
  }
  const suppliedToken = request.headers["x-gps-control-token"];
  if (typeof suppliedToken !== "string" || !sameSecret(suppliedToken, expectedToken)) {
    throw new ControlCenterError(403, "Invalid control token");
  }
  const origin = request.headers.origin;
  if (origin) {
    let originHost;
    try {
      originHost = new URL(origin).host;
    } catch {
      throw new ControlCenterError(403, "Invalid request origin");
    }
    if (originHost !== request.headers.host) {
      throw new ControlCenterError(403, "Cross-origin control requests are not allowed");
    }
  }
}

function sameSecret(left, right) {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && timingSafeEqual(leftBuffer, rightBuffer);
}

async function readJsonBody(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > maximumBodyBytes) throw new ControlCenterError(413, "Request body is too large");
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw new ControlCenterError(400, "Request body must be valid JSON");
  }
}

export function createCliRunner(options) {
  const environment = options.env ?? process.env;
  return (argumentsList, onOutput = () => {}) => new Promise((resolveRun) => {
    const child = spawn(process.execPath, [options.cliFile, ...argumentsList], {
      cwd: options.cwd,
      env: environment,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let processError = null;
    child.stdout.on("data", (chunk) => {
      const text = chunk.toString("utf8");
      stdout = cappedText(stdout + text, maximumProcessOutputCharacters);
      onOutput("stdout", text);
    });
    child.stderr.on("data", (chunk) => {
      const text = chunk.toString("utf8");
      stderr = cappedText(stderr + text, maximumProcessOutputCharacters);
      onOutput("stderr", text);
    });
    child.on("error", (error) => {
      processError = error;
    });
    child.on("close", (code, signal) => {
      if (processError) stderr = cappedText(`${stderr}${processError.message}\n`, maximumProcessOutputCharacters);
      resolveRun({
        exitCode: typeof code === "number" ? code : -1,
        signal,
        stdout,
        stderr,
      });
    });
  });
}

function publicJob(job) {
  return {
    id: job.id,
    suiteId: job.suiteId,
    suiteName: job.suiteName,
    devices: job.devices,
    reportId: job.reportId,
    status: job.status,
    startedAt: job.startedAt,
    finishedAt: job.finishedAt,
    exitCode: job.exitCode,
    log: job.log,
    summary: job.summary,
    error: job.error,
  };
}

function appendJobLog(job, channel, chunk) {
  const prefix = channel === "stderr" ? "[error] " : "";
  job.log = cappedText(`${job.log}${prefix}${chunk}`, maximumLogCharacters);
}

function cappedText(value, maximum) {
  return value.length <= maximum ? value : value.slice(value.length - maximum);
}

function conciseError(result, fallback) {
  const detail = (result.stderr || result.stdout).trim().split("\n").filter(Boolean).at(-1);
  return detail || fallback;
}

function summaryRecord(id, summary, directory) {
  const failedSteps = (summary.steps ?? []).flatMap((step) => step.devices ?? [])
    .filter((device) => device.outcome === "failed").length;
  const failedReports = (summary.deviceReports ?? []).filter((report) => report.outcome === "failed").length;
  const failedEnvironments = (summary.deviceEnvironments ?? []).filter((environment) => (
    ["capture_failed", "failed"].includes(environment.restorationOutcome)
  )).length;
  const failedEvents = (summary.events ?? []).filter((event) => event.outcome === "failed").length;
  const kind = summary.kind ?? "suite";
  const name = kind === "fleet-command"
    ? summary.title ?? "Fleet command"
    : summary.suite?.name ?? "Unknown suite";
  const itemCount = kind === "fleet-command"
    ? (Array.isArray(summary.events) ? summary.events.length : 0)
    : (Array.isArray(summary.steps) ? summary.steps.length : 0);
  return {
    id,
    kind,
    name,
    suiteName: name,
    outcome: summary.outcome ?? "unknown",
    startedAt: Number(summary.startedAt) || 0,
    finishedAt: Number(summary.finishedAt) || 0,
    deviceCount: Array.isArray(summary.devices) ? summary.devices.length : 0,
    itemCount,
    stepCount: itemCount,
    failureCount: failedSteps + failedReports + failedEnvironments + failedEvents,
    artifacts: {
      summary: Boolean(summaryFileFor(directory)),
      junit: Boolean(junitFileFor(directory)),
    },
  };
}

function createFleetSummary({
  devices,
  deviceReportDirectory,
  reportStartedAt,
  reportFinishedAt,
  reportResult,
}) {
  const deviceReports = [];
  const events = [];
  for (const serial of devices) {
    const fileName = `${safeFileComponent(serial)}-report.json`;
    const reportFile = join(deviceReportDirectory, fileName);
    if (!existsSync(reportFile)) {
      deviceReports.push({ serial, outcome: "failed", error: "Device report file is missing" });
      continue;
    }
    try {
      const report = readJsonFile(reportFile, `Device report ${serial}`);
      const latestEvent = Array.isArray(report.events)
        ? report.events.filter((event) => event && typeof event === "object")
          .sort((left, right) => Number(right.startedAt) - Number(left.startedAt))[0]
        : null;
      deviceReports.push({
        serial,
        outcome: latestEvent ? "pulled" : "failed",
        file: `devices/${fileName}`,
        ...(latestEvent ? {} : { error: "Device report contains no simulation events" }),
      });
      if (latestEvent) events.push(fleetEvent(serial, latestEvent, `devices/${fileName}`));
    } catch (error) {
      deviceReports.push({ serial, outcome: "failed", error: error.message });
    }
  }
  const names = [...new Set(events.map((event) => event.name).filter(Boolean))];
  const title = names.length === 1 ? names[0] : names.length > 1 ? "Fleet stop report" : "Fleet command";
  const failed = reportResult.exitCode !== 0 || deviceReports.some((report) => report.outcome === "failed");
  const eventStartTimes = events.map((event) => Number(event.startedAt)).filter(Number.isFinite);
  return {
    schemaVersion: 1,
    kind: "fleet-command",
    title,
    command: "stop-and-collect",
    outcome: failed ? "failed" : "completed",
    startedAt: eventStartTimes.length > 0 ? Math.min(...eventStartTimes) : reportStartedAt,
    finishedAt: reportFinishedAt,
    devices,
    events,
    deviceReports,
    reportError: reportResult.exitCode === 0 ? null : conciseError(reportResult, "Report collection failed"),
  };
}

function fleetEvent(serial, event, reportFile) {
  return {
    serial,
    reportFile,
    id: event.id ?? null,
    name: typeof event.name === "string" ? event.name : "Unnamed run",
    mode: event.mode ?? "unknown",
    outcome: event.outcome ?? "unknown",
    startedAt: event.startedAt ?? null,
    finishedAt: event.finishedAt ?? null,
    durationMs: event.durationMs ?? null,
    startLatitude: event.startLatitude ?? null,
    startLongitude: event.startLongitude ?? null,
    endLatitude: event.endLatitude ?? null,
    endLongitude: event.endLongitude ?? null,
    routeId: event.routeId ?? null,
    routeName: event.routeName ?? null,
    timeZoneId: event.timeZoneId ?? null,
    utcOffsetSeconds: event.utcOffsetSeconds ?? null,
  };
}

function summaryFileFor(directory) {
  for (const fileName of ["suite-summary.json", "fleet-summary.json"]) {
    const file = join(directory, fileName);
    if (existsSync(file)) return file;
  }
  return null;
}

function junitFileFor(directory) {
  for (const fileName of ["suite-results.junit.xml", "fleet-results.junit.xml"]) {
    const file = join(directory, fileName);
    if (existsSync(file)) return file;
  }
  return null;
}

function ensureFleetJUnit(directory, summary) {
  if (summary.kind !== "fleet-command") return;
  try {
    const existing = junitFileFor(directory);
    const fileName = existing ? basename(existing) : summary.junitFile ?? "fleet-results.junit.xml";
    const requiresGeneration = !existing || summary.junitSchemaVersion !== 1;
    if (requiresGeneration) writeFileSync(join(directory, fileName), fleetSummaryToJUnit(summary));
    if (summary.junitFile === fileName && summary.junitSchemaVersion === 1) return;
    summary.junitFile = fileName;
    summary.junitSchemaVersion = 1;
    writeFileSync(
      join(directory, "fleet-summary.json"),
      `${JSON.stringify(summary, null, 2)}\n`,
    );
  } catch {
    // Read-only report directories remain viewable even when migration cannot write JUnit.
  }
}

function walkJsonFiles(directory) {
  const files = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      if (files.length >= 500) return;
      const file = join(current, entry.name);
      if (entry.isDirectory()) visit(file);
      else if (entry.isFile() && entry.name.toLowerCase().endsWith(".json")) files.push(file);
    }
  };
  visit(directory);
  return files;
}

function readJsonFile(file, label) {
  try {
    return JSON.parse(readFileSync(file, "utf8"));
  } catch (error) {
    throw new Error(`${label} could not be read: ${error.message}`);
  }
}

function isWithin(parent, candidate) {
  const path = relative(parent, candidate);
  return path !== ".." && !path.startsWith(`..${sep}`) && !path.startsWith(sep);
}

function safeFileComponent(value) {
  return String(value).trim().replaceAll(/[^a-zA-Z0-9._-]+/g, "-")
    .replaceAll(/^-+|-+$/g, "") || "suite";
}

function isLoopbackHost(host) {
  return ["localhost", "127.0.0.1", "::1"].includes(String(host).toLowerCase());
}

function parseCommandLine(argumentsList) {
  const values = new Map();
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index];
    if (!argument.startsWith("--")) throw new Error(`Unexpected argument: ${argument}`);
    const key = argument.slice(2);
    const next = argumentsList[index + 1];
    if (!next || next.startsWith("--")) values.set(key, true);
    else {
      values.set(key, next);
      index += 1;
    }
  }
  const allowed = new Set(["help", "host", "port", "suite-dir", "report-dir", "no-open"]);
  for (const key of values.keys()) {
    if (!allowed.has(key)) throw new Error(`Unknown option: --${key}`);
  }
  return values;
}

function openBrowser(url) {
  const command = process.platform === "darwin" ? "open" : process.platform === "win32" ? "cmd" : "xdg-open";
  const argumentsList = process.platform === "win32" ? ["/c", "start", "", url] : [url];
  const child = spawn(command, argumentsList, { detached: true, stdio: "ignore" });
  child.unref();
}

function usage() {
  console.log(`GPS Lab Enterprise Control Center

Usage:
  node control-center.mjs [--host 127.0.0.1] [--port 43821]
    [--suite-dir suites] [--report-dir control-center-reports] [--no-open]

The server only accepts loopback hosts. Use --no-open when running headless.`);
}

if (process.argv[1] && resolve(process.argv[1]) === moduleFile) {
  try {
    const argumentsMap = parseCommandLine(process.argv.slice(2));
    if (argumentsMap.has("help")) {
      usage();
      process.exit(0);
    }
    const host = argumentsMap.get("host") ?? "127.0.0.1";
    const portValue = argumentsMap.get("port") ?? "43821";
    const port = Number(portValue);
    const controlCenter = createControlCenter({
      host,
      port,
      suiteDirectory: argumentsMap.get("suite-dir") ?? defaultSuiteDirectory,
      reportDirectory: argumentsMap.get("report-dir") ?? defaultReportDirectory,
    });
    const address = await controlCenter.start();
    console.log(`GPS Lab control center: ${address.url}`);
    console.log(`Suites: ${controlCenter.service.suiteDirectory}`);
    console.log(`Reports: ${controlCenter.service.reportDirectory}`);
    if (!argumentsMap.has("no-open")) openBrowser(address.url);
    const close = async () => {
      await controlCenter.close();
      process.exit(0);
    };
    process.once("SIGINT", close);
    process.once("SIGTERM", close);
  } catch (error) {
    console.error(`gps-control-center: ${error.message}`);
    process.exit(1);
  }
}
