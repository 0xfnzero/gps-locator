import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, isAbsolute, resolve } from "node:path";
import { compileScenario } from "./scenario.mjs";

export function loadScenarioSuite(file) {
  const suiteFile = resolve(file);
  const suiteDocument = readJson(suiteFile, "Suite");
  const suite = suiteDocument.value;
  if (suite?.schemaVersion !== 1) throw new Error("Suite schemaVersion must be 1");
  const name = requiredText(suite.name, "Suite name");
  if (!Array.isArray(suite.steps) || suite.steps.length === 0) {
    throw new Error("Suite requires at least one step");
  }
  if (suite.steps.length > 100) throw new Error("Suite supports at most 100 steps");

  const identifiers = new Set();
  const steps = suite.steps.map((step, index) => {
    if (!step || typeof step !== "object" || Array.isArray(step)) {
      throw new Error(`Suite step ${index + 1} must be an object`);
    }
    const id = requiredText(step.id, `Suite step ${index + 1} id`);
    if (identifiers.has(id)) throw new Error(`Duplicate suite step id: ${id}`);
    identifiers.add(id);
    const scenarioReference = requiredText(step.scenario, `Suite step ${id} scenario`);
    if (isAbsolute(scenarioReference)) {
      throw new Error(`Suite step ${id} scenario must use a relative path`);
    }
    const scenarioFile = resolve(dirname(suiteFile), scenarioReference);
    const scenarioDocument = readJson(scenarioFile, `Scenario ${id}`);
    const compiled = compileScenario(scenarioDocument.value);
    const holdSeconds = boundedInteger(step.holdSeconds ?? 0, 0, 3_600, `Suite step ${id} holdSeconds`);
    const stopAfter = optionalBoolean(step.stopAfter, true, `Suite step ${id} stopAfter`);
    return {
      id,
      scenarioReference,
      scenarioFile,
      scenarioSha256: scenarioDocument.sha256,
      holdSeconds,
      stopAfter,
      compiled,
    };
  });

  return {
    schemaVersion: 1,
    name,
    file: suiteFile,
    sha256: suiteDocument.sha256,
    steps,
  };
}

function readJson(file, label) {
  let content;
  try {
    content = readFileSync(file, "utf8");
  } catch (error) {
    throw new Error(`${label} file could not be read: ${file} (${error.message})`);
  }
  try {
    return {
      value: JSON.parse(content),
      sha256: createHash("sha256").update(content).digest("hex"),
    };
  } catch (error) {
    throw new Error(`${label} is not valid JSON: ${file} (${error.message})`);
  }
}

function requiredText(value, label) {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${label} is required`);
  return value.trim();
}

function boundedInteger(value, minimum, maximum, label) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < minimum || number > maximum) {
    throw new Error(`${label} must be an integer between ${minimum} and ${maximum}`);
  }
  return number;
}

function optionalBoolean(value, defaultValue, label) {
  if (value == null) return defaultValue;
  if (typeof value !== "boolean") throw new Error(`${label} must be a boolean`);
  return value;
}
