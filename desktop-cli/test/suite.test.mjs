import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, test } from "node:test";
import { loadScenarioSuite } from "../suite.mjs";

const temporaryDirectories = [];

afterEach(() => {
  while (temporaryDirectories.length > 0) {
    rmSync(temporaryDirectories.pop(), { recursive: true, force: true });
  }
});

test("loadScenarioSuite resolves and compiles every relative scenario", () => {
  const fixture = createSuiteFixture();
  const suite = loadScenarioSuite(fixture.suite);

  assert.equal(suite.name, "Global release smoke");
  assert.equal(suite.steps.length, 2);
  assert.equal(suite.steps[0].compiled.type, "static");
  assert.equal(suite.steps[1].compiled.type, "route");
  assert.equal(suite.steps[1].holdSeconds, 0);
  assert.equal(suite.steps[1].stopAfter, true);
  assert.match(suite.sha256, /^[a-f0-9]{64}$/);
  assert.match(suite.steps[0].scenarioSha256, /^[a-f0-9]{64}$/);
});

test("loadScenarioSuite rejects duplicate ids and nonportable absolute paths", () => {
  const fixture = createSuiteFixture();
  const document = JSON.parse(fixture.document);
  document.steps[1].id = document.steps[0].id;
  writeFileSync(fixture.suite, JSON.stringify(document));
  assert.throws(() => loadScenarioSuite(fixture.suite), /Duplicate suite step id/);

  document.steps[1].id = "delivery";
  document.steps[1].scenario = fixture.route;
  writeFileSync(fixture.suite, JSON.stringify(document));
  assert.throws(() => loadScenarioSuite(fixture.suite), /must use a relative path/);
});

test("loadScenarioSuite validates all scenario content", () => {
  const fixture = createSuiteFixture();
  writeFileSync(fixture.route, JSON.stringify({
    type: "route",
    name: "Invalid route",
    loop: "false",
    waypoints: [
      { latitude: 1, longitude: 2 },
      { latitude: 3, longitude: 4 },
    ],
  }));

  assert.throws(() => loadScenarioSuite(fixture.suite), /loop must be a boolean/);
});

function createSuiteFixture() {
  const directory = mkdtempSync(join(tmpdir(), "gps-suite-test-"));
  temporaryDirectories.push(directory);
  const point = join(directory, "point.json");
  const route = join(directory, "route.json");
  const suite = join(directory, "suite.json");
  writeFileSync(point, JSON.stringify({
    type: "static",
    name: "US login",
    latitude: 40.75,
    longitude: -73.98,
  }));
  writeFileSync(route, JSON.stringify({
    type: "route",
    name: "Delivery",
    loop: false,
    waypoints: [
      { latitude: 31.23, longitude: 121.47 },
      { latitude: 31.24, longitude: 121.48 },
    ],
  }));
  const document = JSON.stringify({
    schemaVersion: 1,
    name: "Global release smoke",
    steps: [
      { id: "login", scenario: "point.json", holdSeconds: 0 },
      { id: "delivery", scenario: "route.json", holdSeconds: 0 },
    ],
  });
  writeFileSync(suite, document);
  return { directory, point, route, suite, document };
}
