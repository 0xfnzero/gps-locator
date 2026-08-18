const state = {
  controlToken: null,
  devices: [],
  selectedDevices: new Set(),
  suites: [],
  recentRuns: [],
  activeJobId: null,
  pollTimer: null,
  busy: false,
};

const elements = {
  refreshButton: document.querySelector("#refreshButton"),
  lastRefresh: document.querySelector("#lastRefresh"),
  deviceCount: document.querySelector("#deviceCount"),
  deviceError: document.querySelector("#deviceError"),
  deviceList: document.querySelector("#deviceList"),
  copyDeviceIdsButton: document.querySelector("#copyDeviceIdsButton"),
  prepareButton: document.querySelector("#prepareButton"),
  stopButton: document.querySelector("#stopButton"),
  quickLocationForm: document.querySelector("#quickLocationForm"),
  quickLocationName: document.querySelector("#quickLocationName"),
  quickLatitude: document.querySelector("#quickLatitude"),
  quickLongitude: document.querySelector("#quickLongitude"),
  quickTimeZone: document.querySelector("#quickTimeZone"),
  quickLocationTarget: document.querySelector("#quickLocationTarget"),
  setLocationButton: document.querySelector("#setLocationButton"),
  suiteSummary: document.querySelector("#suiteSummary"),
  suiteList: document.querySelector("#suiteList"),
  runTableBody: document.querySelector("#runTableBody"),
  runsEmpty: document.querySelector("#runsEmpty"),
  activityOutcome: document.querySelector("#activityOutcome"),
  activitySummary: document.querySelector("#activitySummary"),
  runLog: document.querySelector("#runLog"),
  toast: document.querySelector("#toast"),
};

elements.refreshButton.addEventListener("click", () => void refresh());
elements.copyDeviceIdsButton.addEventListener("click", () => void copyDeviceIds());
elements.prepareButton.addEventListener("click", () => void prepareDevices());
elements.stopButton.addEventListener("click", () => void stopSimulations());
elements.quickLocationForm.addEventListener("submit", (event) => {
  event.preventDefault();
  void setQuickLocation();
});

void refresh();

async function refresh(options = {}) {
  setBusy(true);
  try {
    const bootstrap = await api("/api/bootstrap");
    state.controlToken = bootstrap.controlToken;
    state.devices = bootstrap.devices;
    state.suites = bootstrap.suites;
    state.recentRuns = bootstrap.recentRuns;
    reconcileDeviceSelection();
    renderDevices(bootstrap.deviceError);
    renderSuites();
    renderRecentRuns();
    elements.lastRefresh.textContent = `Refreshed ${formatTime(Date.now())}`;
    if (bootstrap.activeJob) watchJob(bootstrap.activeJob);
    if (!options.quiet) showToast("Devices and suites refreshed");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setBusy(false);
  }
}

function reconcileDeviceSelection() {
  const connected = new Set(state.devices.map((device) => device.serial));
  state.selectedDevices = new Set([...state.selectedDevices].filter((serial) => connected.has(serial)));
  if (state.selectedDevices.size === 0) {
    for (const device of state.devices) state.selectedDevices.add(device.serial);
  }
}

function renderDevices(error) {
  elements.deviceCount.textContent = String(state.devices.length);
  elements.deviceError.classList.toggle("hidden", !error);
  elements.deviceError.textContent = error ?? "";
  elements.deviceList.replaceChildren();
  if (state.devices.length === 0) {
    const empty = element("p", "device-empty", "No authorized Android devices connected");
    elements.deviceList.append(empty);
  } else {
    const allRow = deviceCheckbox("all-devices", "All connected devices", (
      state.selectedDevices.size === state.devices.length
    ));
    allRow.input.addEventListener("change", () => {
      state.selectedDevices.clear();
      if (allRow.input.checked) {
        for (const device of state.devices) state.selectedDevices.add(device.serial);
      }
      renderDevices(error);
      renderSuites();
    });
    elements.deviceList.append(allRow.row);
    for (const device of state.devices) {
      const item = deviceCheckbox(
        `device-${device.serial}`,
        device.serial,
        state.selectedDevices.has(device.serial),
        device,
      );
      item.input.addEventListener("change", () => {
        if (item.input.checked) state.selectedDevices.add(device.serial);
        else state.selectedDevices.delete(device.serial);
        renderDevices(error);
        renderSuites();
      });
      elements.deviceList.append(item.row);
    }
  }
  const commandDisabled = state.busy || state.selectedDevices.size === 0 || Boolean(state.activeJobId);
  elements.copyDeviceIdsButton.disabled = copyableSelectedDevices().length === 0;
  elements.prepareButton.disabled = commandDisabled;
  elements.stopButton.disabled = commandDisabled;
  renderQuickLocation();
}

function renderQuickLocation() {
  const selectedCount = state.selectedDevices.size;
  const unreadyCount = state.devices.filter((device) => (
    state.selectedDevices.has(device.serial) && !device.commandReady
  )).length;
  const target = `${selectedCount} ${selectedCount === 1 ? "device" : "devices"} selected`;
  elements.quickLocationTarget.textContent = unreadyCount > 0
    ? `${target} · ${unreadyCount} not ready`
    : target;
  const commandLocked = state.busy || Boolean(state.activeJobId);
  elements.setLocationButton.disabled = commandLocked || selectedCount === 0;
  for (const input of [
    elements.quickLocationName,
    elements.quickLatitude,
    elements.quickLongitude,
    elements.quickTimeZone,
  ]) {
    input.disabled = commandLocked;
  }
}

function deviceCheckbox(id, label, checked, device = null) {
  const row = element("label", "device-row");
  const input = document.createElement("input");
  input.type = "checkbox";
  input.id = id;
  input.checked = checked;
  input.disabled = state.busy || Boolean(state.activeJobId);
  const copy = element("span", "device-copy");
  copy.append(element("span", "device-serial", label));
  if (device) renderDeviceStatus(copy, device);
  row.append(input, copy);
  return { row, input };
}

function renderDeviceStatus(container, device) {
  const readiness = deviceReadiness(device);
  const statusLine = element("span", `device-readiness ${readiness.tone}`);
  statusLine.append(element("span", "readiness-dot"));
  statusLine.append(element("span", "", readiness.label));
  container.append(statusLine);
  if (!device.available) {
    container.append(element("span", "device-warning", device.error));
    return;
  }
  const build = [
    `v${device.appVersion} (${device.versionCode})`,
    titleCase(device.tier),
    device.debug ? "Debug" : null,
  ].filter(Boolean).join(" · ");
  container.append(element("span", "device-meta", build));
  if (device.deviceId) {
    const deviceId = element("span", "device-id", `ID ${shortDeviceId(device.deviceId)}`);
    deviceId.title = device.deviceId;
    container.append(deviceId);
  }
  if (device.enterprise.organization) {
    const license = [
      device.enterprise.organization,
      device.enterprise.deviceBound ? "Device-bound" : "Team license",
      licenseExpiry(device.enterprise.expiresAt),
    ].join(" · ");
    container.append(element("span", "device-license", license));
  }
}

function deviceReadiness(device) {
  const presentations = {
    ready: { label: "Ready", tone: "ready" },
    simulation_active: { label: "Simulation active", tone: "active" },
    setup_required: { label: "Setup required", tone: "error" },
    license_required: { label: "License required", tone: "error" },
    status_unavailable: { label: "Status unavailable", tone: "warning" },
  };
  return presentations[device.readiness] ?? presentations.status_unavailable;
}

function licenseExpiry(expiresAt) {
  if (expiresAt === 0) return "No expiry";
  return `Until ${new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  }).format(new Date(expiresAt))}`;
}

function titleCase(value) {
  return value ? `${value.charAt(0).toUpperCase()}${value.slice(1)}` : "Unknown tier";
}

function shortDeviceId(value) {
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}...${value.slice(-6)}`;
}

function copyableSelectedDevices() {
  return state.devices.filter((device) => (
    state.selectedDevices.has(device.serial) && device.available && device.deviceId
  ));
}

async function copyDeviceIds() {
  const devices = copyableSelectedDevices();
  if (devices.length === 0) return;
  const value = devices.map((device) => `${device.serial}\t${device.deviceId}`).join("\n");
  try {
    await writeClipboard(value);
    showToast(`${devices.length} device ${devices.length === 1 ? "ID" : "IDs"} copied`);
  } catch {
    showToast("Device IDs could not be copied", true);
  }
}

function writeClipboard(value) {
  if (typeof navigator.clipboard?.writeText !== "function") {
    return Promise.reject(new Error("Clipboard access is unavailable"));
  }
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(
      () => reject(new Error("Clipboard access timed out")),
      1_500,
    );
    navigator.clipboard.writeText(value).then(
      () => {
        window.clearTimeout(timeout);
        resolve();
      },
      (error) => {
        window.clearTimeout(timeout);
        reject(error);
      },
    );
  });
}

function renderSuites() {
  elements.suiteList.replaceChildren();
  const validCount = state.suites.filter((suite) => suite.valid).length;
  elements.suiteSummary.textContent = `${validCount} ready / ${state.suites.length} total`;
  if (state.suites.length === 0) {
    elements.suiteList.append(element("p", "empty-copy", "No suite manifests found"));
    return;
  }
  for (const suite of state.suites) {
    const card = element("article", `suite-card${suite.valid ? "" : " invalid"}`);
    const detail = document.createElement("div");
    detail.append(element("h3", "", suite.name));
    detail.append(element("p", "suite-id", suite.id));
    if (suite.valid) {
      const meta = element("div", "suite-meta");
      meta.append(tag(`${suite.steps} ${suite.steps === 1 ? "step" : "steps"}`));
      for (const type of suite.types) meta.append(tag(type === "static" ? "Static location" : "Route"));
      if (suite.timeZones.length > 0) meta.append(tag(`${suite.timeZones.length} time zones`));
      detail.append(meta);
    } else {
      detail.append(element("p", "suite-error", suite.error));
    }
    const actions = element("div", "suite-actions");
    const validateButton = button("secondary-button", "\u2713", "Validate");
    validateButton.disabled = state.busy || !suite.valid || Boolean(state.activeJobId);
    validateButton.addEventListener("click", () => void validateSuite(suite));
    const runButton = button("primary-button", "\u25b6", "Run suite");
    runButton.disabled = state.busy || !suite.valid || state.selectedDevices.size === 0 || Boolean(state.activeJobId);
    runButton.addEventListener("click", () => void runSuite(suite));
    actions.append(validateButton, runButton);
    card.append(detail, actions);
    elements.suiteList.append(card);
  }
}

function renderRecentRuns() {
  elements.runTableBody.replaceChildren();
  elements.runsEmpty.classList.toggle("hidden", state.recentRuns.length > 0);
  for (const run of state.recentRuns) {
    const row = document.createElement("tr");
    const runName = run.name ?? run.suiteName;
    const itemCount = run.itemCount ?? run.stepCount;
    const name = element("td", "run-name", runName);
    name.title = runName;
    const outcomeCell = document.createElement("td");
    outcomeCell.append(outcomeBadge(run.outcome));
    row.append(
      name,
      outcomeCell,
      cell(String(run.deviceCount)),
      cell(String(itemCount)),
      cell(formatDate(run.finishedAt)),
    );
    const actionCell = document.createElement("td");
    const viewButton = button("text-button", "", "View");
    viewButton.addEventListener("click", () => void viewReport(run));
    actionCell.append(viewButton);
    row.append(actionCell);
    elements.runTableBody.append(row);
  }
}

async function validateSuite(suite) {
  setBusy(true);
  setActivity({
    title: suite.name,
    outcome: "running",
    facts: [["Action", "Validation"], ["Manifest", suite.id]],
    log: "Validating suite manifest and referenced scenarios...",
  });
  try {
    const result = await api("/api/suites/validate", {
      method: "POST",
      body: { suiteId: suite.id },
    });
    setActivity({
      title: suite.name,
      outcome: "completed",
      facts: [["Action", "Validation"], ["Steps", String(suite.steps)]],
      log: result.output || "Suite validation completed.",
    });
    showToast("Suite validation passed");
  } catch (error) {
    setActivity({
      title: suite.name,
      outcome: "failed",
      facts: [["Action", "Validation"]],
      log: error.message,
    });
    showToast(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function runSuite(suite) {
  setBusy(true);
  try {
    const job = await api("/api/suites/run", {
      method: "POST",
      body: {
        suiteId: suite.id,
        deviceSerials: [...state.selectedDevices],
      },
    });
    watchJob(job);
    showToast("Suite run started");
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setBusy(false);
  }
}

function watchJob(job) {
  state.activeJobId = job.status === "running" ? job.id : null;
  renderJob(job);
  renderDevices(null);
  renderSuites();
  window.clearTimeout(state.pollTimer);
  if (job.status === "running") {
    state.pollTimer = window.setTimeout(async () => {
      try {
        const updated = await api(`/api/jobs/${encodeURIComponent(job.id)}`);
        watchJob(updated);
      } catch (error) {
        showToast(error.message, true);
        state.activeJobId = null;
        renderDevices(null);
        renderSuites();
      }
    }, 700);
  } else {
    state.activeJobId = null;
    void refresh({ quiet: true });
  }
}

function renderJob(job) {
  const facts = [
    ["Devices", job.devices.join(", ")],
    ["Started", formatDate(job.startedAt)],
  ];
  if (job.finishedAt) facts.push(["Finished", formatDate(job.finishedAt)]);
  if (job.summary) facts.push(["Result", job.summary.outcome]);
  setActivity({
    title: job.suiteName,
    outcome: job.status,
    facts,
    log: job.log || "Waiting for suite output...",
    scrollToEnd: true,
  });
}

async function stopSimulations() {
  setBusy(true);
  try {
    const result = await api("/api/devices/stop", {
      method: "POST",
      body: { deviceSerials: [...state.selectedDevices] },
    });
    const reportCompleted = result.reportOutcome === "completed";
    setActivity({
      title: result.reportName ?? "Fleet command",
      outcome: reportCompleted ? "completed" : "failed",
      facts: [
        ["Action", "Stop and collect"],
        ["Devices", result.devices.join(", ")],
        ["Report", result.reportId],
      ],
      log: [result.output, result.warning].filter(Boolean).join("\n") || "Stop command accepted.",
    });
    await refresh({ quiet: true });
    showToast(
      reportCompleted ? "Simulations stopped and reports retained" : "Simulations stopped; report collection was incomplete",
      !reportCompleted,
    );
  } catch (error) {
    showToast(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function prepareDevices() {
  setBusy(true);
  try {
    const result = await api("/api/devices/prepare", {
      method: "POST",
      body: { deviceSerials: [...state.selectedDevices] },
    });
    const completed = result.outcome === "completed";
    setActivity({
      title: "Device preparation",
      outcome: completed ? "completed" : "failed",
      facts: [
        ["Action", "Prepare devices"],
        ["Devices", result.devices.join(", ")],
        ["Prepared", String(result.completedCount)],
        ["Failed", String(result.failedCount + result.unverifiedCount)],
      ],
      log: result.output || "Device preparation completed.",
    });
    await refresh({ quiet: true });
    showToast(
      completed ? "Selected devices are ready" : "Some devices could not be prepared",
      !completed,
    );
  } catch (error) {
    setActivity({
      title: "Device preparation",
      outcome: "failed",
      facts: [["Action", "Prepare devices"]],
      log: error.message,
    });
    showToast(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function setQuickLocation() {
  setBusy(true);
  const requestedLocation = {
    name: elements.quickLocationName.value.trim(),
    latitude: elements.quickLatitude.value,
    longitude: elements.quickLongitude.value,
    timeZone: elements.quickTimeZone.value.trim() || null,
  };
  setActivity({
    title: requestedLocation.name,
    outcome: "running",
    facts: [
      ["Action", "Set location"],
      ["Devices", [...state.selectedDevices].join(", ")],
    ],
    log: "Sending validated location to the selected devices...",
  });
  try {
    const result = await api("/api/location/set", {
      method: "POST",
      body: {
        deviceSerials: [...state.selectedDevices],
        location: requestedLocation,
      },
    });
    const coordinates = `${result.location.latitude}, ${result.location.longitude}`;
    setActivity({
      title: result.location.name,
      outcome: "completed",
      facts: [
        ["Devices", result.devices.join(", ")],
        ["Coordinates", coordinates],
        ["Time zone", result.location.timeZone ?? "Unchanged"],
      ],
      log: result.output || "Location command accepted.",
    });
    showToast("Location sent to selected devices");
  } catch (error) {
    setActivity({
      title: requestedLocation.name || "Quick location",
      outcome: "failed",
      facts: [["Action", "Set location"]],
      log: error.message,
    });
    showToast(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function viewReport(run) {
  try {
    const summary = await api(`/api/runs/${encodeURIComponent(run.id)}`);
    const failures = run.failureCount === 0 ? "None" : String(run.failureCount);
    const runName = run.name ?? run.suiteName;
    const itemCount = run.itemCount ?? run.stepCount;
    const artifactBase = `/api/runs/${encodeURIComponent(run.id)}/artifacts`;
    const artifacts = [];
    if (run.artifacts?.summary) {
      artifacts.push({ label: "Download JSON", href: `${artifactBase}/summary` });
    }
    if (run.artifacts?.junit) {
      artifacts.push({ label: "Download JUnit", href: `${artifactBase}/junit` });
    }
    setActivity({
      title: runName,
      outcome: run.outcome,
      facts: [
        ["Devices", String(run.deviceCount)],
        ["Cases", String(itemCount)],
        ["Failures", failures],
        ["Finished", formatDate(run.finishedAt)],
      ],
      log: JSON.stringify(summary, null, 2),
      artifacts,
    });
  } catch (error) {
    showToast(error.message, true);
  }
}

function setActivity({ title, outcome, facts, log, scrollToEnd = false, artifacts = [] }) {
  elements.activityOutcome.replaceWith(outcomeBadge(outcome, "activityOutcome"));
  elements.activityOutcome = document.querySelector("#activityOutcome");
  elements.activitySummary.replaceChildren();
  elements.activitySummary.append(element("h3", "activity-title", title));
  const factList = element("dl", "activity-facts");
  for (const [label, value] of facts) {
    factList.append(element("dt", "", label), element("dd", "", value));
  }
  elements.activitySummary.append(factList);
  if (artifacts.length > 0) {
    const links = element("div", "artifact-links");
    for (const artifact of artifacts) {
      const link = element("a", "artifact-link", artifact.label);
      link.href = artifact.href;
      link.download = "";
      links.append(link);
    }
    elements.activitySummary.append(links);
  }
  elements.runLog.textContent = log;
  elements.runLog.scrollTop = scrollToEnd ? elements.runLog.scrollHeight : 0;
}

function outcomeBadge(outcome, id = "") {
  const normalized = ["completed", "failed", "running"].includes(outcome) ? outcome : "neutral";
  const label = normalized === "neutral" ? capitalize(outcome || "Idle") : capitalize(normalized);
  const badge = element("span", `outcome-badge ${normalized}`, label);
  if (id) badge.id = id;
  return badge;
}

function tag(text) {
  return element("span", "suite-tag", text);
}

function button(className, icon, label) {
  const value = document.createElement("button");
  value.type = "button";
  value.className = className;
  if (icon) {
    const iconElement = element("span", "", icon);
    iconElement.setAttribute("aria-hidden", "true");
    value.append(iconElement);
  }
  value.append(document.createTextNode(label));
  return value;
}

function cell(text) {
  return element("td", "", text);
}

function element(tagName, className = "", text = "") {
  const value = document.createElement(tagName);
  if (className) value.className = className;
  if (text) value.textContent = text;
  return value;
}

function setBusy(busy) {
  state.busy = busy;
  elements.refreshButton.disabled = busy;
  renderDevices(elements.deviceError.classList.contains("hidden") ? null : elements.deviceError.textContent);
  renderSuites();
}

async function api(path, options = {}) {
  const headers = { Accept: "application/json" };
  const request = { method: options.method ?? "GET", headers };
  if (options.body != null) {
    headers["Content-Type"] = "application/json";
    headers["X-GPS-Control-Token"] = state.controlToken ?? "";
    request.body = JSON.stringify(options.body);
  }
  const response = await fetch(path, request);
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.error || `Request failed (${response.status})`);
  return result;
}

let toastTimer;
function showToast(message, error = false) {
  window.clearTimeout(toastTimer);
  elements.toast.textContent = message;
  elements.toast.classList.toggle("error", error);
  elements.toast.classList.remove("hidden");
  toastTimer = window.setTimeout(() => elements.toast.classList.add("hidden"), 2600);
}

function formatDate(value) {
  if (!value) return "-";
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(new Date(value));
}

function formatTime(value) {
  return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function capitalize(value) {
  const text = String(value);
  return text ? `${text[0].toUpperCase()}${text.slice(1)}` : text;
}
