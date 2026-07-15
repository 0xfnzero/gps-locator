export function suiteSummaryToJUnit(summary) {
  const testCases = [];
  for (const step of summary.steps) {
    for (const device of step.devices) {
      testCases.push(stepTestCase(summary, step, device));
    }
  }
  for (const report of summary.deviceReports) {
    testCases.push(reportTestCase(summary, report));
  }
  for (const environment of summary.deviceEnvironments ?? []) {
    testCases.push(environmentTestCase(summary, environment));
  }

  const failures = testCases.filter((testCase) => testCase.failure).length;
  const durationSeconds = secondsBetween(summary.startedAt, summary.finishedAt);
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<testsuite name="${xml(summary.suite.name)}" tests="${testCases.length}" ` +
      `failures="${failures}" errors="0" skipped="0" time="${durationSeconds}" ` +
      `timestamp="${xml(new Date(summary.startedAt).toISOString())}">`,
    "  <properties>",
    property("gps-lab.schemaVersion", summary.schemaVersion),
    property("gps-lab.outcome", summary.outcome),
    property("gps-lab.suite.file", summary.suite.file),
    property("gps-lab.suite.sha256", summary.suite.sha256),
    "  </properties>",
    ...testCases.flatMap(renderTestCase),
    "</testsuite>",
    "",
  ];
  return lines.join("\n");
}

export function fleetSummaryToJUnit(summary) {
  const testCases = [];
  for (const event of summary.events ?? []) testCases.push(fleetEventTestCase(summary, event));
  for (const report of summary.deviceReports ?? []) testCases.push(reportTestCase(summary, report));

  const failures = testCases.filter((testCase) => testCase.failure).length;
  const commandDuration = Number(secondsBetween(summary.startedAt, summary.finishedAt));
  const caseDuration = testCases.reduce((total, testCase) => total + Number(testCase.time), 0);
  const durationSeconds = Math.max(commandDuration, caseDuration).toFixed(3);
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    `<testsuite name="${xml(summary.title)}" tests="${testCases.length}" ` +
      `failures="${failures}" errors="0" skipped="0" time="${durationSeconds}" ` +
      `timestamp="${xml(new Date(summary.startedAt).toISOString())}">`,
    "  <properties>",
    property("gps-lab.schemaVersion", summary.schemaVersion),
    property("gps-lab.kind", summary.kind),
    property("gps-lab.outcome", summary.outcome),
    property("gps-lab.command", summary.command),
    "  </properties>",
    ...testCases.flatMap(renderTestCase),
    "</testsuite>",
    "",
  ];
  return lines.join("\n");
}

function stepTestCase(summary, step, device) {
  return {
    classname: `gps-lab.${summary.suite.name}`,
    name: `${step.id} [${device.serial}]`,
    time: secondsBetween(step.startedAt, step.finishedAt),
    output: [
      `scenario=${step.scenario}`,
      `scenarioSha256=${step.scenarioSha256}`,
      `type=${step.type}`,
      `timeZone=${step.timeZone ?? "unchanged"}`,
      `outcome=${device.outcome}`,
    ].join("\n"),
    failure: device.outcome === "failed" ? device.error || "Scenario command failed" : null,
  };
}

function reportTestCase(summary, report) {
  const runName = summary.suite?.name ?? summary.title ?? "Fleet command";
  return {
    classname: `gps-lab.${runName}.reports`,
    name: `device report [${report.serial}]`,
    time: "0.000",
    output: report.file ? `file=${report.file}` : `outcome=${report.outcome}`,
    failure: report.outcome === "failed" ? report.error || "Device report pull failed" : null,
  };
}

function fleetEventTestCase(summary, event) {
  const finalized = ["stopped", "completed"].includes(event.outcome);
  return {
    classname: `gps-lab.${summary.title}.fleet`,
    name: `${event.name} [${event.serial}]`,
    time: secondsBetween(event.startedAt, event.finishedAt),
    output: [
      `mode=${event.mode}`,
      `outcome=${event.outcome}`,
      `coordinates=${event.startLatitude},${event.startLongitude}`,
      `timeZone=${event.timeZoneId ?? "unknown"}`,
      `reportFile=${event.reportFile}`,
    ].join("\n"),
    failure: finalized ? null : `Unexpected final simulation outcome: ${event.outcome ?? "missing"}`,
  };
}

function environmentTestCase(summary, environment) {
  const failed = ["capture_failed", "failed"].includes(environment.restorationOutcome);
  return {
    classname: `gps-lab.${summary.suite.name}.environment`,
    name: `device environment [${environment.serial}]`,
    time: "0.000",
    output: [
      `originalTimeZone=${environment.originalTimeZone ?? "unknown"}`,
      `requestedTimeZones=${environment.requestedTimeZones.join(",")}`,
      `currentTimeZone=${environment.currentTimeZone ?? "unknown"}`,
      `restorationOutcome=${environment.restorationOutcome}`,
    ].join("\n"),
    failure: failed ? environment.error || "Device environment restoration failed" : null,
  };
}

function renderTestCase(testCase) {
  const opening = `  <testcase classname="${xml(testCase.classname)}" ` +
    `name="${xml(testCase.name)}" time="${testCase.time}">`;
  const lines = [opening, `    <system-out>${xml(testCase.output)}</system-out>`];
  if (testCase.failure) {
    lines.push(
      `    <failure type="GpsLabFailure" message="${xml(testCase.failure)}">` +
        `${xml(testCase.failure)}</failure>`,
    );
  }
  lines.push("  </testcase>");
  return lines;
}

function property(name, value) {
  return `    <property name="${xml(name)}" value="${xml(value)}" />`;
}

function secondsBetween(startedAt, finishedAt) {
  return (Math.max(0, finishedAt - startedAt) / 1_000).toFixed(3);
}

function xml(value) {
  return String(value ?? "")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "\ufffd")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}
