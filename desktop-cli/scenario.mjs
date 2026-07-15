import { randomUUID } from "node:crypto";

export function buildRouteDocument(scenario) {
  if (!scenario || scenario.type !== "route") throw new Error("Scenario type must be route");
  if (!Array.isArray(scenario.waypoints) || scenario.waypoints.length < 2) {
    throw new Error("A route requires at least two waypoints");
  }
  const waypoints = scenario.waypoints.map((waypoint, index) => ({
    id: waypoint.id == null ? randomUUID() : requiredText(waypoint.id, "Waypoint id"),
    name: requiredText(waypoint.name ?? `Point ${index + 1}`, "Waypoint name"),
    latitude: coordinate(waypoint.latitude, -90, 90, "latitude"),
    longitude: coordinate(waypoint.longitude, -180, 180, "longitude"),
    stopDurationSeconds: boundedInteger(
      waypoint.stopDurationSeconds ?? 0,
      0,
      86_400,
      "stopDurationSeconds"
    )
  }));
  const distinctCoordinates = new Set(
    waypoints.map((waypoint) => `${waypoint.latitude},${waypoint.longitude}`)
  );
  if (distinctCoordinates.size < 2) throw new Error("A route requires two distinct coordinates");
  return JSON.stringify({
    id: scenario.id == null ? randomUUID() : requiredText(scenario.id, "Route id"),
    name: requiredText(scenario.name, "Route name"),
    speedKph: boundedNumber(scenario.speedKph ?? 30, 0.5, 500, "speedKph"),
    loop: optionalBoolean(scenario.loop, false, "loop"),
    createdAt: boundedInteger(
      scenario.createdAt ?? Date.now(),
      0,
      Number.MAX_SAFE_INTEGER,
      "createdAt"
    ),
    waypoints
  });
}

export function validateStaticScenario(scenario) {
  if (!scenario || scenario.type !== "static") throw new Error("Scenario type must be static");
  return {
    name: requiredText(scenario.name, "Scenario name"),
    latitude: coordinate(scenario.latitude, -90, 90, "latitude"),
    longitude: coordinate(scenario.longitude, -180, 180, "longitude"),
    timeZone: optionalTimeZone(scenario.timeZone),
  };
}

export function compileScenario(scenario) {
  if (scenario?.schemaVersion != null && scenario.schemaVersion !== 1) {
    throw new Error("Scenario schemaVersion must be 1");
  }
  if (scenario?.type === "static") {
    const location = validateStaticScenario(scenario);
    return { type: "static", name: location.name, timeZone: location.timeZone, location };
  }
  if (scenario?.type === "route") {
    const document = buildRouteDocument(scenario);
    return {
      type: "route",
      name: requiredText(scenario.name, "Route name"),
      timeZone: optionalTimeZone(scenario.timeZone),
      document,
    };
  }
  throw new Error("Scenario type must be static or route");
}

function requiredText(value, label) {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${label} is required`);
  return value.trim();
}

function finiteNumber(value, label) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`${label} must be a finite number`);
  return number;
}

function boundedNumber(value, minimum, maximum, label) {
  const number = finiteNumber(value, label);
  if (number < minimum || number > maximum) {
    throw new Error(`${label} must be between ${minimum} and ${maximum}`);
  }
  return number;
}

function boundedInteger(value, minimum, maximum, label) {
  const number = boundedNumber(value, minimum, maximum, label);
  if (!Number.isInteger(number)) throw new Error(`${label} must be an integer`);
  return number;
}

function optionalBoolean(value, defaultValue, label) {
  if (value == null) return defaultValue;
  if (typeof value !== "boolean") throw new Error(`${label} must be a boolean`);
  return value;
}

function optionalTimeZone(value) {
  if (value == null) return null;
  const timeZone = requiredText(value, "timeZone");
  if (timeZone.length > 100) throw new Error("timeZone must be at most 100 characters");
  try {
    return new Intl.DateTimeFormat("en-US", { timeZone }).resolvedOptions().timeZone;
  } catch {
    throw new Error("timeZone must be a valid IANA time zone");
  }
}

function coordinate(value, minimum, maximum, label) {
  const number = finiteNumber(value, label);
  if (number < minimum || number > maximum) throw new Error(`${label} is out of range`);
  return number;
}
