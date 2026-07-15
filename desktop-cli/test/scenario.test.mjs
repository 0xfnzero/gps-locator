import assert from "node:assert/strict";
import { test } from "node:test";
import { buildRouteDocument, compileScenario, validateStaticScenario } from "../scenario.mjs";

test("buildRouteDocument creates the Android route schema", () => {
  const route = JSON.parse(buildRouteDocument({
    type: "route",
    name: "Delivery",
    speedKph: 25,
    loop: true,
    waypoints: [
      { name: "A", latitude: 10, longitude: 20 },
      { name: "B", latitude: 11, longitude: 21, stopDurationSeconds: 15 }
    ]
  }));

  assert.equal(route.name, "Delivery");
  assert.equal(route.loop, true);
  assert.equal(route.waypoints.length, 2);
  assert.equal(route.waypoints[1].stopDurationSeconds, 15);
});

test("validateStaticScenario rejects invalid coordinates", () => {
  assert.throws(() => validateStaticScenario({
    type: "static",
    name: "Invalid",
    latitude: 100,
    longitude: 0
  }), /latitude is out of range/);
});

test("buildRouteDocument rejects values the Android route model cannot run", () => {
  const route = {
    type: "route",
    name: "Invalid route",
    speedKph: 25,
    waypoints: [
      { latitude: 10, longitude: 20 },
      { latitude: 11, longitude: 21 }
    ]
  };

  assert.throws(() => buildRouteDocument({ ...route, speedKph: 501 }), /speedKph/);
  assert.throws(() => buildRouteDocument({
    ...route,
    waypoints: [
      route.waypoints[0],
      { ...route.waypoints[1], stopDurationSeconds: 86_401 }
    ]
  }), /stopDurationSeconds/);
  assert.throws(() => buildRouteDocument({
    ...route,
    waypoints: [route.waypoints[0], { ...route.waypoints[0] }]
  }), /distinct coordinates/);
  assert.throws(() => buildRouteDocument({ ...route, loop: "false" }), /loop must be a boolean/);
  assert.throws(() => buildRouteDocument({ ...route, id: 123 }), /Route id is required/);
});

test("compileScenario validates and preserves IANA time zones", () => {
  const staticScenario = compileScenario({
    type: "static",
    name: "New York login",
    latitude: 40.75,
    longitude: -73.98,
    timeZone: "America/New_York",
  });
  const routeScenario = compileScenario({
    type: "route",
    name: "Tokyo delivery",
    timeZone: "Asia/Tokyo",
    waypoints: [
      { latitude: 35.67, longitude: 139.65 },
      { latitude: 35.68, longitude: 139.66 },
    ],
  });

  assert.equal(staticScenario.timeZone, "America/New_York");
  assert.equal(routeScenario.timeZone, "Asia/Tokyo");
  assert.throws(
    () => compileScenario({
      type: "static",
      name: "Invalid time zone",
      latitude: 1,
      longitude: 2,
      timeZone: "Mars/Olympus_Mons",
    }),
    /valid IANA time zone/,
  );
});
