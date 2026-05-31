#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const INTERAGENT_DIR = fs.realpathSync.native(path.dirname(__filename));
const PROJECT_ROOT = fs.realpathSync.native(path.join(INTERAGENT_DIR, ".."));
const REQUESTS_DIR = path.join(INTERAGENT_DIR, "requests");
const RESPONSES_DIR = path.join(INTERAGENT_DIR, "responses");
const PROGRESS_DIR = path.join(INTERAGENT_DIR, "progress");
const SESSION_PATH = path.join(INTERAGENT_DIR, "session.json");
const QUEUE_PATH = path.join(INTERAGENT_DIR, "implementation_queue.json");

const POINTERS = {
  claude_request: path.join(INTERAGENT_DIR, "claude_request.md"),
  claude_response: path.join(INTERAGENT_DIR, "claude_response.md"),
  codex_request: path.join(INTERAGENT_DIR, "codex_request.md"),
  codex_response: path.join(INTERAGENT_DIR, "codex_response.md"),
};

const VALID_SESSION_STATUSES = new Set([
  "in_progress",
  "implementing",
  "review_requested",
  "reviewing",
  "changes_requested",
  "fixing",
  "awaiting_concurrence",
  "waiting_for_response",
  "waiting_for_user",
  "blocked",
  "closed",
]);

const VALID_AGENT_ROLES = new Set(["claude", "codex"]);
const VALID_TURN_OWNERS = new Set(["claude", "codex", "user"]);

const VALID_TASK_STATUSES = new Set([
  "ready",
  "in_progress",
  "in_review",
  "ready_for_review",
  "blocked",
  "paused",
  "done",
  "cancelled",
]);

const VALID_PHASES = new Set([
  "implementation",
  "review_loop",
  "interrupted",
  "closed",
]);

const VALID_TASK_KINDS = new Set([
  "implementation",
  "bugfix",
  "review",
  "investigation",
]);

function nowIso() {
  return new Date().toISOString();
}

function fail(message, code = 1) {
  console.error(`[broker] ${message}`);
  process.exit(code);
}

function parseArgs(argv) {
  const positionals = [];
  const options = {};

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      positionals.push(token);
      continue;
    }

    const key = token.slice(2);
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      options[key] = true;
      continue;
    }

    options[key] = next;
    index += 1;
  }

  return { positionals, options };
}

function slugify(value) {
  return String(value || "mailbox-round")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "") || "mailbox-round";
}

function canonicalizeExistingPath(targetPath) {
  return fs.realpathSync.native(targetPath);
}

function isWithinDirectory(targetPath, directoryPath) {
  const relative = path.relative(directoryPath, targetPath);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function relativeToInteragent(targetPath) {
  const resolvedTargetPath = fs.existsSync(targetPath)
    ? canonicalizeExistingPath(targetPath)
    : path.resolve(targetPath);
  return path.relative(INTERAGENT_DIR, resolvedTargetPath).replaceAll(path.sep, "/");
}

function relativeToProjectRoot(targetPath) {
  const resolvedTargetPath = fs.existsSync(targetPath)
    ? canonicalizeExistingPath(targetPath)
    : path.resolve(targetPath);
  return path.relative(PROJECT_ROOT, resolvedTargetPath).replaceAll(path.sep, "/");
}

function describeRepoFile(pathInput) {
  if (!pathInput) {
    return { exists: false, error: "missing_path", absolutePath: null, relativePath: null };
  }

  const candidate = path.resolve(PROJECT_ROOT, pathInput);
  const absolutePath = fs.existsSync(candidate) ? canonicalizeExistingPath(candidate) : candidate;
  if (!isWithinDirectory(absolutePath, PROJECT_ROOT)) {
    return { exists: false, error: "outside_repo", absolutePath, relativePath: null };
  }
  if (!fs.existsSync(absolutePath)) {
    return { exists: false, error: "missing_file", absolutePath, relativePath: relativeToProjectRoot(candidate) };
  }

  return {
    exists: true,
    error: null,
    absolutePath,
    relativePath: relativeToProjectRoot(absolutePath),
  };
}

function canonicalizeBoundRepoFile(pathInput, description) {
  const descriptor = describeRepoFile(pathInput);
  if (!descriptor.exists) {
    if (descriptor.error === "outside_repo") {
      fail(`Refusing ${description} outside canonical repo root: ${descriptor.absolutePath}`);
    }
    fail(`Missing ${description}: ${pathInput}`);
  }
  return descriptor.relativePath;
}

function ensureTrackedRepoFile(pathInput, description, defaultContents = "") {
  if (!pathInput) {
    fail(`Missing ${description}`);
  }

  const candidate = path.resolve(PROJECT_ROOT, pathInput);
  if (!isWithinDirectory(candidate, PROJECT_ROOT)) {
    fail(`Refusing ${description} outside canonical repo root: ${candidate}`);
  }

  ensureDir(path.dirname(candidate));
  ensureFile(candidate, defaultContents);
  return canonicalizeBoundRepoFile(candidate, description);
}

function touchRepoFile(relativePath, isoTimestamp = nowIso()) {
  const descriptor = describeRepoFile(relativePath);
  if (!descriptor.exists) {
    fail(`Missing tracked repo file: ${relativePath}`);
  }

  const timestamp = new Date(isoTimestamp);
  fs.utimesSync(descriptor.absolutePath, timestamp, timestamp);
  return new Date(fs.statSync(descriptor.absolutePath).mtimeMs).toISOString();
}

function readText(filePath) {
  return fs.readFileSync(filePath, "utf8");
}

function writeText(filePath, contents) {
  if (fs.existsSync(filePath) && fs.readFileSync(filePath, "utf8") === contents) {
    return;
  }
  fs.writeFileSync(filePath, contents, "utf8");
}

function writeJson(filePath, value) {
  writeText(filePath, `${JSON.stringify(value, null, 2)}\n`);
}

function readJson(filePath) {
  return JSON.parse(readText(filePath));
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function ensureFile(filePath, defaultContents = "") {
  if (!fs.existsSync(filePath)) {
    writeText(filePath, defaultContents);
  }
}

function isHtmlCommentStub(text) {
  const trimmed = text.trim();
  return trimmed.startsWith("<!--") && trimmed.endsWith("-->");
}

function hasRealContent(text) {
  const trimmed = text.trim();
  return trimmed !== "" && !isHtmlCommentStub(trimmed);
}

function pointerNameToPath(pointerName) {
  const filePath = POINTERS[pointerName];
  if (!filePath) {
    fail(`Unknown pointer '${pointerName}'. Expected one of: ${Object.keys(POINTERS).join(", ")}`);
  }
  return filePath;
}

function normalizeSessionDocFields(session) {
  if (!("active_runbook_path" in session)) {
    session.active_runbook_path = null;
  }
  if (!("active_progress_path" in session)) {
    session.active_progress_path = null;
  }
  if (!("runbook_gate_required" in session)) {
    session.runbook_gate_required = false;
  }
}

function normalizeTaskDocFields(task) {
  if (!("runbook_path" in task)) {
    task.runbook_path = null;
  }
  if (!("progress_path" in task)) {
    task.progress_path = null;
  }
  if (!("runbook_required" in task)) {
    task.runbook_required = false;
  }
}

function normalizeSessionTrackingFields(session) {
  if (!("active_workstream_id" in session)) {
    session.active_workstream_id = null;
  }
  if (!("active_task_kind" in session)) {
    session.active_task_kind = null;
  }
  if (!("active_next_step" in session)) {
    session.active_next_step = null;
  }
  if (!("active_tracking_updated_at" in session)) {
    session.active_tracking_updated_at = null;
  }
  if (!("active_progress_log_path" in session)) {
    session.active_progress_log_path = null;
  }
  if (!Array.isArray(session.active_context_paths)) {
    session.active_context_paths = [];
  }
  if (!("tracking_gate_required" in session)) {
    session.tracking_gate_required = false;
  }
  if (!("sidecar_review_in_flight_for_task" in session)) {
    session.sidecar_review_in_flight_for_task = null;
  }
}

function normalizeTaskTrackingFields(task) {
  if (!("workstream_id" in task)) {
    task.workstream_id = null;
  }
  if (!("task_kind" in task)) {
    task.task_kind = null;
  }
  if (!("next_step" in task)) {
    task.next_step = null;
  }
  if (!("tracking_updated_at" in task)) {
    task.tracking_updated_at = null;
  }
  if (!("progress_log_path" in task)) {
    task.progress_log_path = null;
  }
  if (!Array.isArray(task.context_paths)) {
    task.context_paths = [];
  }
  if (!("tracking_required" in task)) {
    task.tracking_required = false;
  }

  if (task.runbook_required && !task.tracking_required) {
    task.tracking_required = true;
    task.workstream_id = task.workstream_id || task.id;
    task.task_kind = task.task_kind || "implementation";
    task.next_step = task.next_step || "Continue the active tracked workstream.";
    task.tracking_updated_at = task.tracking_updated_at || nowIso();
    task.context_paths = [task.runbook_path, task.progress_path].filter(Boolean);
  }
  if (!task.progress_log_path && task.progress_path) {
    task.progress_log_path = task.progress_path;
  }
}

function normalizeContextPaths(pathsInput) {
  if (!Array.isArray(pathsInput)) {
    return [];
  }
  return pathsInput.filter(Boolean).map((entry) => relativeToProjectRoot(entry));
}

function parseContextPathsOption(value) {
  if (!value) {
    return [];
  }
  return String(value)
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => relativeToProjectRoot(entry));
}

function defaultProgressLogRelativePath(taskId) {
  return relativeToProjectRoot(path.join(PROGRESS_DIR, `${slugify(taskId)}.md`));
}

function progressLogDefaultContents(taskId, workstreamId, taskKind) {
  return [
    `# Interagent Progress - ${taskId}`,
    "",
    `- Workstream: \`${workstreamId}\``,
    `- Task Kind: \`${taskKind}\``,
    "",
  ].join("\n");
}

function describeTaskTrackingGate(task, session, { useSessionFallback = false } = {}) {
  const taskWorkstreamId = task?.workstream_id || null;
  const taskKind = task?.task_kind || null;
  const taskNextStep = task?.next_step || null;
  const taskTrackingUpdatedAt = task?.tracking_updated_at || null;
  const taskProgressLogPath = task?.progress_log_path || null;
  const taskContextPaths = Array.isArray(task?.context_paths) ? task.context_paths : [];
  const sessionWorkstreamId = useSessionFallback ? session.active_workstream_id || null : null;
  const sessionTaskKind = useSessionFallback ? session.active_task_kind || null : null;
  const sessionNextStep = useSessionFallback ? session.active_next_step || null : null;
  const sessionTrackingUpdatedAt = useSessionFallback ? session.active_tracking_updated_at || null : null;
  const sessionProgressLogPath = useSessionFallback ? session.active_progress_log_path || null : null;
  const sessionContextPaths = useSessionFallback && Array.isArray(session.active_context_paths)
    ? session.active_context_paths
    : [];
  const workstreamId = taskWorkstreamId || sessionWorkstreamId;
  const resolvedTaskKind = taskKind || sessionTaskKind;
  const nextStep = taskNextStep || sessionNextStep;
  const trackingUpdatedAt = taskTrackingUpdatedAt || sessionTrackingUpdatedAt;
  const progressLogPath = taskProgressLogPath || sessionProgressLogPath;
  const contextPaths = taskContextPaths.length > 0 ? taskContextPaths : sessionContextPaths;
  const required = Boolean(task?.tracking_required) || (useSessionFallback && Boolean(session.tracking_gate_required));
  const issues = [];

  if (required) {
    if (!workstreamId) {
      issues.push("missing_workstream_id");
    }
    if (!resolvedTaskKind) {
      issues.push("missing_task_kind");
    } else if (!VALID_TASK_KINDS.has(resolvedTaskKind)) {
      issues.push("invalid_task_kind");
    }
    if (!nextStep) {
      issues.push("missing_next_step");
    }
    if (!trackingUpdatedAt) {
      issues.push("missing_tracking_updated_at");
    }
    if (!progressLogPath) {
      issues.push("missing_progress_log_path");
    } else {
      const progressDescriptor = describeRepoFile(progressLogPath);
      if (!progressDescriptor.exists) {
        issues.push(progressDescriptor.error === "outside_repo" ? "progress_log_outside_repo" : "missing_progress_log");
      }
    }
    if (taskWorkstreamId && sessionWorkstreamId && taskWorkstreamId !== sessionWorkstreamId) {
      issues.push("workstream_id_mismatch");
    }
    if (taskKind && sessionTaskKind && taskKind !== sessionTaskKind) {
      issues.push("task_kind_mismatch");
    }
  }

  return {
    required,
    valid: required ? issues.length === 0 : true,
    workstream_id: workstreamId,
    task_kind: resolvedTaskKind,
    next_step: nextStep,
    tracking_updated_at: trackingUpdatedAt,
    progress_log_path: progressLogPath,
    context_paths: contextPaths,
    issues,
  };
}

function sameWorkstreamBinding(leftWorkstreamId, rightWorkstreamId) {
  return (leftWorkstreamId || null) === (rightWorkstreamId || null);
}

function inheritActiveTracking(task, session) {
  if (!session.tracking_gate_required) {
    return;
  }
  if (!session.active_workstream_id || !session.active_task_kind) {
    return;
  }
  if (task.tracking_required) {
    return;
  }

  task.tracking_required = true;
  task.workstream_id = session.active_workstream_id;
  task.task_kind = session.active_task_kind;
  task.next_step = session.active_next_step || task.next_step || "Continue the active tracked workstream.";
  task.tracking_updated_at = session.active_tracking_updated_at || task.tracking_updated_at || nowIso();
  task.progress_log_path = session.active_progress_log_path || task.progress_log_path || null;
  task.context_paths = Array.isArray(session.active_context_paths) ? [...session.active_context_paths] : [];
}

function enforceTaskTracking(task, session, { commandName } = {}) {
  const effectiveCommandName = commandName || "broker transition";
  inheritActiveTracking(task, session);

  const gate = describeTaskTrackingGate(task, session, { useSessionFallback: true });
  if (
    session.tracking_gate_required
    && !sameWorkstreamBinding(gate.workstream_id, session.active_workstream_id)
  ) {
    fail(
      `Refusing ${effectiveCommandName}: active workstream is \`${session.active_workstream_id}\`. Use track-task to switch workstreams explicitly.`,
    );
  }

  if (gate.required && !gate.valid) {
    fail(`Refusing ${effectiveCommandName}: tracking gate unsatisfied (${gate.issues.join(", ")})`);
  }

  return gate;
}

function assertTrackingFresh(task, session, commandName) {
  const gate = describeTaskTrackingGate(task, session, { useSessionFallback: true });
  if (!gate.required) {
    return;
  }
  if (!gate.valid) {
    fail(`Refusing ${commandName}: tracking gate unsatisfied (${gate.issues.join(", ")})`);
  }

  const trackingUpdatedAtMs = Date.parse(gate.tracking_updated_at || "");
  const updatedAtMs = Date.parse(session.timestamps?.updated_at || "");
  if (Number.isFinite(updatedAtMs) && Number.isFinite(trackingUpdatedAtMs) && trackingUpdatedAtMs < updatedAtMs) {
    fail(
      `Refusing ${commandName}: tracked task state for workstream \`${gate.workstream_id}\` must be updated after the last broker transition (${session.timestamps.updated_at}).`,
    );
  }

  const progressDescriptor = describeRepoFile(gate.progress_log_path);
  if (!progressDescriptor.exists) {
    fail(`Refusing ${commandName}: tracking gate unsatisfied (missing_progress_log)`);
  }

  const progressUpdatedAtMs = fs.statSync(progressDescriptor.absolutePath).mtimeMs;
  if (Number.isFinite(updatedAtMs) && Number.isFinite(progressUpdatedAtMs) && progressUpdatedAtMs < updatedAtMs) {
    fail(
      `Refusing ${commandName}: progress log for workstream \`${gate.workstream_id}\` must be updated after the last broker transition (${session.timestamps.updated_at}).`,
    );
  }
}

function bindTaskDocs(task, session, runbookPath, progressPath, { activate = true } = {}) {
  task.runbook_path = canonicalizeBoundRepoFile(runbookPath, `runbook for task '${task.id}'`);
  task.progress_path = ensureTrackedRepoFile(
    progressPath,
    `progress file for task '${task.id}'`,
    progressLogDefaultContents(task.id, task.workstream_id || task.id, task.task_kind || "implementation"),
  );
  task.progress_log_path = task.progress_path;
  task.runbook_required = true;
  task.context_paths = [task.runbook_path, task.progress_path];
  task.tracking_required = true;
  task.workstream_id = task.workstream_id || task.id;
  task.task_kind = task.task_kind || "implementation";
  task.next_step = task.next_step || "Continue the active tracked workstream.";
  task.tracking_updated_at = touchRepoFile(task.progress_log_path, nowIso());

  if (activate) {
    session.active_runbook_path = task.runbook_path;
    session.active_progress_path = task.progress_path;
    session.runbook_gate_required = true;
  }
}

function syncSessionDocsFromTask(session, task) {
  if (!task) {
    session.active_runbook_path = null;
    session.active_progress_path = null;
    session.runbook_gate_required = false;
    return;
  }
  session.active_runbook_path = task.runbook_path || null;
  session.active_progress_path = task.progress_path || null;
  session.runbook_gate_required = Boolean(task.runbook_required);
}

function syncSessionTrackingFromTask(session, task) {
  if (!task) {
    session.active_workstream_id = null;
    session.active_task_kind = null;
    session.active_next_step = null;
    session.active_tracking_updated_at = null;
    session.active_progress_log_path = null;
    session.active_context_paths = [];
    session.tracking_gate_required = false;
    return;
  }

  session.active_workstream_id = task.workstream_id || null;
  session.active_task_kind = task.task_kind || null;
  session.active_next_step = task.next_step || null;
  session.active_tracking_updated_at = task.tracking_updated_at || null;
  session.active_progress_log_path = task.progress_log_path || null;
  session.active_context_paths = Array.isArray(task.context_paths) ? [...task.context_paths] : [];
  session.tracking_gate_required = Boolean(task.tracking_required);
}

function defaultSession() {
  const projectName = path.basename(PROJECT_ROOT);
  const projectSlug = slugify(projectName);
  return {
    schema_version: 1,
    session_id: `${projectSlug}-interagent`,
    goal: `coordinate Claude/Codex collaboration for ${projectName}`,
    status: "closed",
    phase: "closed",
    round: 0,
    implementer: "codex",
    reviewer: "claude",
    active_owner: "user",
    awaiting: "user",
    current_task_id: null,
    active_runbook_path: null,
    active_progress_path: null,
    runbook_gate_required: false,
    active_workstream_id: null,
    active_task_kind: null,
    active_next_step: null,
    active_tracking_updated_at: null,
    active_progress_log_path: null,
    active_context_paths: [],
    tracking_gate_required: false,
    review_in_flight_for_task: null,
    sidecar_review_in_flight_for_task: null,
    blocked_by: null,
    last_verdict: null,
    latest_request_path: null,
    latest_response_path: null,
    resume_task_id_after_interrupt: null,
    resume_role_after_interrupt: "idle",
    ready_queue_count: 0,
    user_interrupt: {
      pending: false,
      handled_by: null,
      return_to_role: null,
      return_to_phase: null,
      return_to_task_id: null,
    },
    timestamps: {
      updated_at: nowIso(),
      last_request_at: null,
      last_response_at: null,
    },
  };
}

function defaultQueue() {
  const projectName = path.basename(PROJECT_ROOT);
  const projectSlug = slugify(projectName);
  return {
    schema_version: 1,
    session_id: `${projectSlug}-interagent`,
    goal: `coordinate Claude/Codex collaboration for ${projectName}`,
    task_order: [],
    tasks: [],
  };
}

function ensureBaseFiles() {
  let created = false;
  ensureDir(REQUESTS_DIR);
  ensureDir(RESPONSES_DIR);
  ensureDir(PROGRESS_DIR);

  for (const pointerPath of Object.values(POINTERS)) {
    if (!fs.existsSync(pointerPath)) {
      ensureFile(pointerPath);
      created = true;
    }
  }

  if (!fs.existsSync(SESSION_PATH)) {
    writeJson(SESSION_PATH, defaultSession());
    created = true;
  }
  if (!fs.existsSync(QUEUE_PATH)) {
    writeJson(QUEUE_PATH, defaultQueue());
    created = true;
  }
  return created;
}

function loadState() {
  ensureBaseFiles();
  const session = readJson(SESSION_PATH);
  const queue = readJson(QUEUE_PATH);

  normalizeSessionDocFields(session);
  normalizeSessionTrackingFields(session);
  for (const task of queue.tasks) {
    if (!Array.isArray(task.write_scope)) {
      task.write_scope = [];
    }
    if (!Array.isArray(task.depends_on)) {
      task.depends_on = [];
    }
    normalizeTaskDocFields(task);
    normalizeTaskTrackingFields(task);
  }

  session.ready_queue_count = countReadyTasks(queue);
  return { session, queue };
}

function saveState(session, queue, { touchTimestamp = true } = {}) {
  normalizeSessionDocFields(session);
  normalizeSessionTrackingFields(session);
  for (const task of queue.tasks) {
    normalizeTaskDocFields(task);
    normalizeTaskTrackingFields(task);
  }
  pruneQueue(queue, session);
  const currentTask = findTask(queue, session.current_task_id);
  syncSessionDocsFromTask(session, currentTask);
  syncSessionTrackingFromTask(session, currentTask);
  session.ready_queue_count = countReadyTasks(queue);
  if (touchTimestamp) {
    session.timestamps.updated_at = nowIso();
  }
  writeJson(SESSION_PATH, session);
  writeJson(QUEUE_PATH, queue);
}

function pruneQueue(queue, session) {
  const activeTaskIds = new Set([session.current_task_id, session.review_in_flight_for_task].filter(Boolean));
  queue.tasks = queue.tasks.filter((task) => {
    if (activeTaskIds.has(task.id)) {
      return true;
    }
    if (["done", "cancelled"].includes(task.status)) {
      return false;
    }
    if (
      session.status === "closed"
      && session.phase === "closed"
      && task.status === "in_review"
      && !task.tracking_required
      && task.blocked_by === null
      && typeof task.notes === "string"
      && task.notes.includes("Created from mailbox request archiving")
    ) {
      return false;
    }
    return true;
  });
  const remainingIds = new Set(queue.tasks.map((task) => task.id));
  queue.task_order = queue.task_order.filter((taskId) => remainingIds.has(taskId));
}

function countReadyTasks(queue) {
  return queue.tasks.filter((task) => task.status === "ready" && task.blocked_by === null).length;
}

function countReadyForReviewTasks(queue) {
  return queue.tasks.filter((task) => task.status === "ready_for_review" && task.blocked_by === null).length;
}

function normalizeAndSave() {
  ensureBaseFiles();
  const session = readJson(SESSION_PATH);
  const queue = readJson(QUEUE_PATH);

  if (!VALID_SESSION_STATUSES.has(session.status)) {
    fail(`Invalid session.status '${session.status}' in ${relativeToInteragent(SESSION_PATH)}`);
  }
  if (!VALID_PHASES.has(session.phase)) {
    fail(`Invalid phase '${session.phase}' in ${relativeToInteragent(SESSION_PATH)}`);
  }
  if (!VALID_AGENT_ROLES.has(session.implementer)) {
    fail(`Invalid implementer '${session.implementer}' in ${relativeToInteragent(SESSION_PATH)}`);
  }
  if (!VALID_AGENT_ROLES.has(session.reviewer)) {
    fail(`Invalid reviewer '${session.reviewer}' in ${relativeToInteragent(SESSION_PATH)}`);
  }
  if (session.implementer === session.reviewer) {
    fail(`implementer and reviewer must differ in ${relativeToInteragent(SESSION_PATH)}`);
  }
  if (session.active_owner !== null && !VALID_TURN_OWNERS.has(session.active_owner)) {
    fail(`Invalid active_owner '${session.active_owner}' in ${relativeToInteragent(SESSION_PATH)}`);
  }
  if (session.awaiting !== null && !VALID_TURN_OWNERS.has(session.awaiting)) {
    fail(`Invalid awaiting '${session.awaiting}' in ${relativeToInteragent(SESSION_PATH)}`);
  }
  normalizeSessionDocFields(session);
  normalizeSessionTrackingFields(session);

  for (const task of queue.tasks) {
    if (!VALID_TASK_STATUSES.has(task.status)) {
      fail(`Invalid task.status '${task.status}' for task '${task.id}'`);
    }
    if (!Array.isArray(task.write_scope)) {
      task.write_scope = [];
    }
    if (!Array.isArray(task.depends_on)) {
      task.depends_on = [];
    }
    normalizeTaskDocFields(task);
    normalizeTaskTrackingFields(task);
  }

  saveState(session, queue, { touchTimestamp: false });
}

function readPointer(pointerName) {
  return readText(pointerNameToPath(pointerName));
}

function findTask(queue, taskId) {
  return queue.tasks.find((task) => task.id === taskId) || null;
}

function updateTask(queue, taskId, patch) {
  const task = findTask(queue, taskId);
  if (!task) {
    return null;
  }
  Object.assign(task, patch);
  return task;
}

function ensureTask(queue, taskId, defaults = {}) {
  let task = findTask(queue, taskId);
  if (task) {
    // Existing tasks are returned as-is; callers must apply any required
    // owner/status transitions explicitly after lookup.
    return task;
  }

  if (!queue.task_order.includes(taskId)) {
    queue.task_order.push(taskId);
  }

  task = {
    id: taskId,
    title: defaults.title || taskId,
    status: defaults.status || "ready",
    owner: defaults.owner || null,
    priority: defaults.priority || queue.task_order.length,
    blocked_by: defaults.blocked_by || null,
    parallelizable: defaults.parallelizable ?? false,
    write_scope: defaults.write_scope || [],
    depends_on: defaults.depends_on || [],
    notes: defaults.notes || "Created by broker state transition",
    runbook_path: defaults.runbook_path || null,
    progress_path: defaults.progress_path || null,
    runbook_required: defaults.runbook_required ?? false,
    workstream_id: defaults.workstream_id || null,
    task_kind: defaults.task_kind || null,
    next_step: defaults.next_step || null,
    tracking_updated_at: defaults.tracking_updated_at || null,
    progress_log_path: defaults.progress_log_path || null,
    context_paths: defaults.context_paths || [],
    tracking_required: defaults.tracking_required ?? false,
  };
  queue.tasks.push(task);
  return task;
}

function appendTaskNote(task, note) {
  const existing = typeof task.notes === "string" && task.notes.trim() !== "" ? task.notes.trim() : "";
  const combined = existing ? `${existing}\n${note}` : note;
  const lines = combined.split("\n");
  task.notes = lines.slice(-20).join("\n");
}

function validateAgentRole(name, value) {
  if (!VALID_AGENT_ROLES.has(value)) {
    fail(`Invalid ${name} '${value}'. Expected one of: ${[...VALID_AGENT_ROLES].join(", ")}`);
  }
}

function validateDistinctRoles(implementer, reviewer) {
  validateAgentRole("implementer", implementer);
  validateAgentRole("reviewer", reviewer);
  if (implementer === reviewer) {
    fail("implementer and reviewer must differ");
  }
}

function validateTurnOwner(name, value, { allowNull = true } = {}) {
  if (value === null && allowNull) {
    return;
  }
  if (!VALID_TURN_OWNERS.has(value)) {
    fail(`Invalid ${name} '${value}'. Expected one of: ${[...VALID_TURN_OWNERS].join(", ")}${allowNull ? ", null" : ""}`);
  }
}

function validateAgentTurnOwner(name, value) {
  validateTurnOwner(name, value, { allowNull: false });
  if (value === "user") {
    fail(`Invalid ${name} '${value}'. Role transitions must hand control to an agent, not the user`);
  }
}

function statusForPhase(phase, fallback = "implementing") {
  if (phase === "implementation") {
    return "implementing";
  }
  if (phase === "review_loop") {
    return "review_requested";
  }
  if (phase === "closed") {
    return "closed";
  }
  return fallback;
}

function validateRoleTransitionPhase(commandName, phase) {
  if (!["implementation", "review_loop"].includes(phase)) {
    fail(`Invalid --phase value '${phase}' for ${commandName}. Expected one of: implementation, review_loop`);
  }
}

function resetRoundPointers(session) {
  session.latest_request_path = null;
  session.latest_response_path = null;
  session.last_verdict = null;
}

function assertRoleTransitionCanProceed(session, taskId, commandName, options = {}) {
  const allowClearBlocked = options["clear-blocked-by"] === true;
  const allowClearReviewInFlight = options["clear-review-in-flight"] === true;

  if (session.blocked_by !== null && session.status !== "closed" && !allowClearBlocked) {
    fail(
      `Refusing ${commandName}: session.blocked_by is '${session.blocked_by}'. Clear it explicitly with --clear-blocked-by if this takeover is intentional`,
    );
  }
  if (
    session.review_in_flight_for_task !== null &&
    session.review_in_flight_for_task !== taskId &&
    session.status !== "closed" &&
    !allowClearReviewInFlight
  ) {
    fail(
      `Refusing ${commandName}: review_in_flight_for_task is '${session.review_in_flight_for_task}'. Clear it explicitly with --clear-review-in-flight if this takeover is intentional`,
    );
  }
}

function nextSequenceNumber() {
  const dirs = [REQUESTS_DIR, RESPONSES_DIR];
  let max = 0;
  for (const dirPath of dirs) {
    for (const entry of fs.readdirSync(dirPath, { withFileTypes: true })) {
      if (!entry.isFile()) {
        continue;
      }
      const match = entry.name.match(/^(\d{3})-/);
      if (!match) {
        continue;
      }
      max = Math.max(max, Number(match[1]));
    }
  }
  return max + 1;
}

function latestSequenceFromSession(session) {
  const match = (session.latest_request_path || "").match(/(\d{3})-/);
  return match ? Number(match[1]) : null;
}

function parseRequestType(text) {
  const match = text.match(/\*\*Type:\*\*\s*([^\n]+)/i);
  return match ? match[1].trim().toLowerCase() : "";
}

function parseVerdict(text) {
  const lines = text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  const verdict = [...lines].reverse().find((line) =>
    ["APPROVED", "CHANGES_REQUESTED", "NEEDS_DISCUSSION", "IMPLEMENTED"].includes(line),
  );
  return verdict || null;
}

function readArchivedArtifact(relativePath, description) {
  if (!relativePath) {
    fail(`Refusing close-review: missing ${description} artifact`);
  }

  const artifactPath = path.join(INTERAGENT_DIR, relativePath);
  if (!fs.existsSync(artifactPath)) {
    fail(`Refusing close-review: missing ${description} artifact at ${relativePath}`);
  }

  const text = readText(artifactPath);
  const verdict = parseVerdict(text);
  if (!verdict) {
    fail(`Refusing close-review: could not find a verdict in ${description} artifact ${relativePath}`);
  }

  return {
    relativePath,
    artifactPath,
    text,
    verdict,
  };
}

function trackTaskCommand(options) {
  const taskId = options["task-id"];
  const workstreamId = options["workstream-id"];
  const taskKind = options["task-kind"];
  const nextStep = options["next-step"];
  if (!taskId || !workstreamId || !taskKind || !nextStep) {
    fail("track-task requires --task-id, --workstream-id, --task-kind, and --next-step");
  }
  if (!VALID_TASK_KINDS.has(taskKind)) {
    fail(`Invalid --task-kind value '${taskKind}'. Expected one of: ${[...VALID_TASK_KINDS].join(", ")}`);
  }

  const { session, queue } = loadState();
  const task = ensureTask(queue, taskId, {
    title: taskId,
    status: options.status || "in_progress",
    owner: options.owner || session.implementer,
    notes: "Created from track-task transition",
  });

  const preserveImplementationLane = shouldPreserveImplementationLaneForTrackTask(session, task, taskKind, options);
  const effectiveTaskKind = preserveImplementationLane ? (task.task_kind || session.active_task_kind || "implementation") : taskKind;
  const effectivePhase = preserveImplementationLane
    ? "implementation"
    : options.phase || (taskKind === "review" ? "review_loop" : session.phase);

  const transitionTimestamp = nowIso();
  const requestedProgressLogPath = options["progress-log-path"] || task.progress_log_path || defaultProgressLogRelativePath(taskId);
  task.tracking_required = true;
  task.workstream_id = workstreamId;
  task.task_kind = effectiveTaskKind;
  task.next_step = nextStep;
  task.progress_log_path = ensureTrackedRepoFile(
    requestedProgressLogPath,
    `progress log for task '${taskId}'`,
    progressLogDefaultContents(taskId, workstreamId, effectiveTaskKind),
  );
  task.tracking_updated_at = touchRepoFile(task.progress_log_path, transitionTimestamp);
  task.context_paths = parseContextPathsOption(options["context-paths"]);

  session.current_task_id = taskId;
  if (effectivePhase) {
    validateRoleTransitionPhase("track-task", effectivePhase);
    session.phase = effectivePhase;
    session.status = options.status || statusForPhase(effectivePhase, session.status);
    task.status = effectivePhase === "review_loop" ? "in_review" : "in_progress";
    session.review_in_flight_for_task = effectivePhase === "review_loop" ? taskId : null;
    session.sidecar_review_in_flight_for_task = null;
  }
  if (options.owner) {
    validateAgentRole("owner", options.owner);
    task.owner = options.owner;
  }
  appendTaskNote(
    task,
    `[${nowIso()}] Tracked workstream=${task.workstream_id}; kind=${task.task_kind}; next_step=${task.next_step}; progress_log=${task.progress_log_path}${preserveImplementationLane ? " (review interruption preserved as implementation lane)" : ""}`,
  );

  session.timestamps.updated_at = task.tracking_updated_at;
  saveState(session, queue, { touchTimestamp: false });
  console.log(JSON.stringify({
    task_id: task.id,
    workstream_id: task.workstream_id,
    task_kind: task.task_kind,
    next_step: task.next_step,
    tracking_updated_at: task.tracking_updated_at,
    progress_log_path: task.progress_log_path,
    current_task_id: session.current_task_id,
  }, null, 2));
}

function bindDocsCommand(options) {
  const taskId = options["task-id"];
  const runbookPath = options.runbook;
  const progressPath = options.progress;
  if (!taskId || !runbookPath || !progressPath) {
    fail("bind-docs requires --task-id, --runbook, and --progress");
  }

  const { session, queue } = loadState();
  const task = ensureTask(queue, taskId, {
    title: taskId,
    status: options.status || "in_progress",
    owner: options.owner || session.implementer,
    notes: "Created from bind-docs transition",
  });

  bindTaskDocs(task, session, runbookPath, progressPath);
  task.context_paths = [task.runbook_path, task.progress_path];
  const transitionTimestamp = task.tracking_updated_at;
  session.current_task_id = taskId;
  if (options.phase) {
    validateRoleTransitionPhase("bind-docs", options.phase);
    session.phase = options.phase;
    session.status = options.status || statusForPhase(options.phase, session.status);
    task.status = options.phase === "review_loop" ? "in_review" : "in_progress";
  }
  if (options.owner) {
    validateAgentRole("owner", options.owner);
    task.owner = options.owner;
  }
  appendTaskNote(task, `[${nowIso()}] Attached legacy context docs runbook=${task.runbook_path}; progress=${task.progress_path}`);

  session.timestamps.updated_at = transitionTimestamp;
  saveState(session, queue, { touchTimestamp: false });
  console.log(JSON.stringify({
    task_id: task.id,
    runbook_path: task.runbook_path,
    progress_path: task.progress_path,
    runbook_required: task.runbook_required,
    workstream_id: task.workstream_id,
    task_kind: task.task_kind,
    next_step: task.next_step,
    tracking_updated_at: task.tracking_updated_at,
    progress_log_path: task.progress_log_path,
    current_task_id: session.current_task_id,
  }, null, 2));
}

function responseStatusFromVerdict(verdict) {
  switch (verdict) {
    case "APPROVED":
      return "awaiting_concurrence";
    case "CHANGES_REQUESTED":
      return "changes_requested";
    case "NEEDS_DISCUSSION":
      return "waiting_for_user";
    case "IMPLEMENTED":
      return "implementing";
    default:
      return "waiting_for_response";
  }
}

function inferRequestStatus(requestType) {
  if (requestType.includes("implementation")) {
    return "implementing";
  }
  return "review_requested";
}

function inferPhase(requestType) {
  if (requestType.includes("implementation")) {
    return "implementation";
  }
  return "review_loop";
}

function isImplementationLikeTaskKind(taskKind) {
  return taskKind === "implementation" || taskKind === "bugfix";
}

function shouldDefaultToImplementationLane(session, task, options = {}) {
  if (options.phase || options["promote-review"]) {
    return false;
  }

  const taskKind = task?.task_kind || session.active_task_kind || null;
  if (!isImplementationLikeTaskKind(taskKind)) {
    return false;
  }

  const taskWorkstream = task?.workstream_id || session.active_workstream_id || null;
  return taskWorkstream === null || sameWorkstreamBinding(taskWorkstream, session.active_workstream_id);
}

function restoreImplementationLane(session, _sidecarTaskId) {
  // Preserve the implementation lane after a sidecar review round. The
  // response task id is still the authoritative implementation task id, so
  // restore it explicitly in case session.current_task_id drifted while the
  // review was in flight.
  session.current_task_id = _sidecarTaskId;
  session.active_owner = session.implementer;
  session.awaiting = session.implementer;
  session.phase = "implementation";
  session.status = "implementing";
  session.review_in_flight_for_task = null;
  session.blocked_by = null;
}

function shouldPreserveImplementationSessionForReviewRequest(session, task, requestType, options) {
  return shouldDefaultToImplementationLane(session, task, options)
    && !requestType.includes("implementation")
    && session.implementer === options.from
    && session.reviewer === options.to;
}

function shouldPreserveImplementationLaneForTrackTask(session, task, requestedTaskKind, options) {
  const explicitPromotion = Boolean(options["promote-review"]);
  const existingTaskKind = task?.task_kind || session.active_task_kind || null;
  return !explicitPromotion
    && requestedTaskKind === "review"
    && isImplementationLikeTaskKind(existingTaskKind)
    && (task?.workstream_id || session.active_workstream_id || null) === (options["workstream-id"] || null);
}
function latestRequestTypeFromSession(session) {
  if (!session.latest_request_path) {
    return "";
  }
  const artifactPath = path.join(INTERAGENT_DIR, session.latest_request_path);
  if (!fs.existsSync(artifactPath)) {
    return "";
  }
  return parseRequestType(readText(artifactPath));
}

function shouldPreserveImplementationSessionForReviewResponse(session, task, options) {
  const latestRequestType = latestRequestTypeFromSession(session);
  return shouldDefaultToImplementationLane(session, task, options)
    && session.implementer === options.to
    && session.reviewer === options.from
    && latestRequestType !== ""
    && !latestRequestType.includes("implementation");
}

function requestArtifactName(sequence, from, to, taskId) {
  return `${String(sequence).padStart(3, "0")}-${slugify(from)}-to-${slugify(to)}-${slugify(taskId)}.md`;
}

function responseArtifactName(sequence, from, to, taskId) {
  return `${String(sequence).padStart(3, "0")}-${slugify(from)}-to-${slugify(to)}-${slugify(taskId)}.md`;
}

function taskIdFromArtifactRelativePath(relativePath, queue) {
  if (!relativePath) {
    return null;
  }
  const fileName = path.basename(relativePath);
  const match = fileName.match(/^\d{3}-[^-]+-to-[^-]+-(.+)\.md$/);
  if (!match) {
    return null;
  }
  const slug = match[1];
  const task = queue.tasks.find((entry) => slugify(entry.id) === slug);
  return task ? task.id : null;
}

function inferTaskIdForResponse(session, queue, explicitTaskId) {
  return explicitTaskId || taskIdFromArtifactRelativePath(session.latest_request_path, queue) || session.current_task_id || "mailbox-round";
}

function archiveRequest(options) {
  const sourceInput = path.resolve(process.cwd(), options.source || "");
  if (!options.from || !options.to) {
    fail("archive-request requires --from and --to");
  }
  if (!fs.existsSync(sourceInput)) {
    fail(`Request source not found: ${sourceInput}`);
  }

  const source = canonicalizeExistingPath(sourceInput);
  if (!isWithinDirectory(source, PROJECT_ROOT)) {
    fail(`Refusing to archive request outside canonical repo root: ${source}`);
  }

  const text = readText(source);
  if (!hasRealContent(text)) {
    fail(`Request source is empty/stub: ${source}`);
  }

  const { session, queue } = loadState();
  const rawTaskId = options["task-id"] || session.current_task_id || "mailbox-round";
  const rawTask = findTask(queue, rawTaskId);
  if (
    !options["task-id"]
    && rawTask
    && ["done", "cancelled"].includes(rawTask.status)
  ) {
    fail(
      `Refusing archive-request: fallback current_task_id '${rawTaskId}' is in terminal state '${rawTask.status}'. `
      + `Pass --task-id explicitly to target the correct active task.`,
    );
  }
  const taskId = rawTaskId;
  const requestType = parseRequestType(text);
  const existingTask = rawTask;
  const preserveImplementationSession = shouldPreserveImplementationSessionForReviewRequest(
    session,
    existingTask,
    requestType,
    options,
  );
  const sequence = nextSequenceNumber();
  const artifactPath = path.join(REQUESTS_DIR, requestArtifactName(sequence, options.from, options.to, taskId));

  writeText(artifactPath, text);

  session.round = sequence;
  session.latest_request_path = relativeToInteragent(artifactPath);
  if (preserveImplementationSession) {
    restoreImplementationLane(session, taskId);
    session.sidecar_review_in_flight_for_task = taskId;
  } else {
    session.current_task_id = taskId;
    session.active_owner = options.to;
    session.awaiting = options.to;
    session.status = options.status || inferRequestStatus(requestType);
    session.phase = options.phase || inferPhase(requestType);
    session.review_in_flight_for_task = session.phase === "review_loop" ? taskId : session.review_in_flight_for_task;
    session.sidecar_review_in_flight_for_task = null;
  }
  session.timestamps.last_request_at = nowIso();

  const task = updateTask(queue, taskId, {
    owner: preserveImplementationSession ? session.implementer : options.from,
    status: preserveImplementationSession ? "in_progress" : session.phase === "review_loop" ? "in_review" : "in_progress",
    blocked_by: null,
  });
  const ensuredTask = task || (taskId !== "mailbox-round"
    ? ensureTask(queue, taskId, {
        title: taskId,
        status: preserveImplementationSession ? "in_progress" : session.phase === "review_loop" ? "in_review" : "in_progress",
        owner: preserveImplementationSession ? session.implementer : options.from,
        priority: queue.task_order.length,
        blocked_by: null,
        parallelizable: false,
        write_scope: [],
        depends_on: [],
        notes: "Created from mailbox request archiving",
      })
    : null);

  if (preserveImplementationSession && ensuredTask) {
    appendTaskNote(
      ensuredTask,
      `[${nowIso()}] Archived sidecar review request ${relativeToInteragent(artifactPath)}; implementation ownership remains with ${session.active_owner}.`,
    );
  }

  if (ensuredTask && (session.tracking_gate_required || ensuredTask.tracking_required)) {
    enforceTaskTracking(ensuredTask, session, { commandName: "archive-request" });
    assertTrackingFresh(ensuredTask, session, "archive-request");
  }

  saveState(session, queue);
  console.log(relativeToInteragent(artifactPath));
}

function archiveResponse(options) {
  const sourceInput = path.resolve(process.cwd(), options.source || "");
  if (!options.from || !options.to) {
    fail("archive-response requires --from and --to");
  }
  if (!fs.existsSync(sourceInput)) {
    fail(`Response source not found: ${sourceInput}`);
  }

  const source = canonicalizeExistingPath(sourceInput);
  if (!isWithinDirectory(source, PROJECT_ROOT)) {
    fail(`Refusing to archive response outside canonical repo root: ${source}`);
  }

  const text = readText(source);
  if (!hasRealContent(text)) {
    fail(`Response source is empty/stub: ${source}`);
  }

  const verdict = parseVerdict(text);
  if (!verdict) {
    fail(`Could not find a verdict line in: ${source}`);
  }

  const { session, queue } = loadState();
  const taskId = inferTaskIdForResponse(session, queue, options["task-id"]);
  const resolvedTask = findTask(queue, taskId);
  if (
    !options["task-id"]
    && resolvedTask
    && ["done", "cancelled"].includes(resolvedTask.status)
  ) {
    fail(
      `Refusing archive-response: inferred task '${taskId}' is in terminal state '${resolvedTask.status}'. `
      + `Pass --task-id explicitly to target the correct active task.`,
    );
  }
  const sequence = latestSequenceFromSession(session) ?? nextSequenceNumber();
  const artifactPath = path.join(RESPONSES_DIR, responseArtifactName(sequence, options.from, options.to, taskId));
  const task = updateTask(queue, taskId, {});
  const preserveImplementationSession = task
    ? shouldPreserveImplementationSessionForReviewResponse(session, task, options)
    : false;

  writeText(artifactPath, text);

  session.latest_response_path = relativeToInteragent(artifactPath);
  session.last_verdict = verdict;
  if (preserveImplementationSession) {
    restoreImplementationLane(session, taskId);
    session.sidecar_review_in_flight_for_task = null;
  } else {
    session.current_task_id = taskId;
    session.active_owner = options.to;
    session.awaiting = options.to;
    session.status = options.status || responseStatusFromVerdict(verdict);
    session.phase = session.status === "closed" ? "closed" : session.phase === "interrupted" ? "interrupted" : "review_loop";
    session.sidecar_review_in_flight_for_task = null;
  }
  session.timestamps.last_response_at = nowIso();

  if (task && (session.tracking_gate_required || task.tracking_required)) {
    enforceTaskTracking(task, session, { commandName: "archive-response" });
    assertTrackingFresh(task, session, "archive-response");
  }
  if (task) {
    if (preserveImplementationSession) {
      task.status = "in_progress";
      task.blocked_by = null;
      task.owner = session.implementer;
      appendTaskNote(
        task,
        `[${nowIso()}] Archived sidecar review response ${relativeToInteragent(artifactPath)} with verdict ${verdict}; implementation remains active.`,
      );
    } else if (verdict === "CHANGES_REQUESTED") {
      task.status = "blocked";
      task.blocked_by = `${slugify(options.from)}-response-round-${String(sequence).padStart(3, "0")}`;
    } else if (verdict === "IMPLEMENTED") {
      task.status = "in_progress";
      task.blocked_by = null;
      task.owner = options.from;
    } else if (verdict === "APPROVED") {
      task.status = "in_review";
      task.blocked_by = null;
    }
  }

  saveState(session, queue);
  console.log(relativeToInteragent(artifactPath));
}

function closeReview(options) {
  const { session, queue } = loadState();
  const taskId = options["task-id"] || session.current_task_id;
  if (!taskId) {
    fail("close-review requires --task-id or an active current_task_id");
  }
  if (session.review_in_flight_for_task !== taskId) {
    fail(
      `Refusing close-review: review_in_flight_for_task is '${session.review_in_flight_for_task ?? "null"}', not '${taskId}'`,
    );
  }

  const task = findTask(queue, taskId);
  if (!task) {
    fail(`Refusing close-review: task '${taskId}' not found`);
  }

  const latestRequest = readArchivedArtifact(session.latest_request_path, "latest request");
  const latestResponse = readArchivedArtifact(session.latest_response_path, "latest response");
  if (latestRequest.verdict !== "APPROVED" || latestResponse.verdict !== "APPROVED") {
    fail(
      `Refusing close-review: latest request/response verdicts must both be APPROVED (got request=${latestRequest.verdict}, response=${latestResponse.verdict})`,
    );
  }

  const taskSlug = `-${slugify(taskId)}.md`;
  if (!latestRequest.relativePath.endsWith(taskSlug) || !latestResponse.relativePath.endsWith(taskSlug)) {
    fail(
      `Refusing close-review: latest request/response artifacts do not both target task '${taskId}'`,
    );
  }

  if (session.tracking_gate_required || task.tracking_required) {
    enforceTaskTracking(task, session, { commandName: "close-review" });
  }

  const transitionTimestamp = nowIso();
  if (task.tracking_required) {
    task.next_step = options["next-step"] || "Review closed; broker returned to idle-ready state.";
    if (task.progress_log_path) {
      task.tracking_updated_at = touchRepoFile(task.progress_log_path, transitionTimestamp);
    } else {
      task.tracking_updated_at = transitionTimestamp;
    }
  }
  task.status = "done";
  task.blocked_by = null;
  appendTaskNote(
    task,
    `[${transitionTimestamp}] Review closed after mutual exhaustion; request=${latestRequest.relativePath}; response=${latestResponse.relativePath}`,
  );

  session.review_in_flight_for_task = null;
  session.sidecar_review_in_flight_for_task = null;
  session.blocked_by = null;

  const nextQueuedReview = computeNextQueuedReview(queue);
  const nextReadyTask = nextQueuedReview ? null : computeNextReadyTask(session, queue);
  if (nextQueuedReview) {
    nextQueuedReview.status = "in_review";
    nextQueuedReview.blocked_by = null;
    appendTaskNote(
      nextQueuedReview,
      `[${transitionTimestamp}] Promoted automatically after close-review completed previous task '${taskId}'.`,
    );
    session.current_task_id = nextQueuedReview.id;
    session.active_owner = session.reviewer;
    session.awaiting = session.reviewer;
    session.phase = "review_loop";
    session.status = "review_requested";
    session.review_in_flight_for_task = nextQueuedReview.id;
  } else if (nextReadyTask) {
    nextReadyTask.status = "in_progress";
    nextReadyTask.owner = session.implementer;
    appendTaskNote(
      nextReadyTask,
      `[${transitionTimestamp}] Activated automatically after close-review completed previous task '${taskId}'.`,
    );
    session.current_task_id = nextReadyTask.id;
    session.active_owner = session.implementer;
    session.awaiting = session.implementer;
    session.phase = "implementation";
    session.status = "implementing";
  } else {
    session.current_task_id = null;
    session.active_owner = null;
    session.awaiting = null;
    session.phase = "closed";
    session.status = "closed";
  }
  session.timestamps.updated_at = transitionTimestamp;

  saveState(session, queue, { touchTimestamp: false });
  console.log(JSON.stringify({
    task_id: taskId,
    closed_at: transitionTimestamp,
    latest_request_path: latestRequest.relativePath,
    latest_response_path: latestResponse.relativePath,
    task_status: task.status,
    session_status: session.status,
    session_phase: session.phase,
    next_task_id: nextReadyTask ? nextReadyTask.id : null,
    next_review_task_id: nextQueuedReview ? nextQueuedReview.id : null,
  }, null, 2));
}

function clearPointer(options) {
  const pointerName = options.pointer;
  if (!pointerName) {
    fail("clear-pointer requires --pointer");
  }

  const pointerPath = pointerNameToPath(pointerName);
  const text = readText(pointerPath);
  if (!hasRealContent(text)) {
    writeText(pointerPath, "");
    console.log(`${pointerName}:empty`);
    return;
  }

  const { session } = loadState();
  const latestRelativePath = pointerName.endsWith("response")
    ? session.latest_response_path
    : session.latest_request_path;

  if (!latestRelativePath) {
    fail(`Refusing to clear ${pointerName}: pointer has real content and no archived artifact is recorded`);
  }

  const latestPath = path.join(INTERAGENT_DIR, latestRelativePath);
  if (!fs.existsSync(latestPath)) {
    fail(`Refusing to clear ${pointerName}: archived artifact missing at ${latestRelativePath}`);
  }

  const archivedText = readText(latestPath);
  if (archivedText !== text) {
    fail(`Refusing to clear ${pointerName}: pointer content does not match latest archived artifact`);
  }

  writeText(pointerPath, "");
  console.log(`${pointerName}:cleared`);
}

function writeScopeConflicts(scopeA, scopeB) {
  const normalizedA = scopeA.map((value) => value.replaceAll("\\", "/"));
  const normalizedB = scopeB.map((value) => value.replaceAll("\\", "/"));
  return normalizedA.some((left) => normalizedB.some((right) => left.startsWith(right) || right.startsWith(left)));
}

function computeNextReadyTask(session, queue) {
  const activeTaskIds = new Set(
    [
      session.current_task_id,
      session.review_in_flight_for_task,
      session.sidecar_review_in_flight_for_task,
    ].filter(Boolean),
  );
  const activeTasks = queue.tasks.filter((task) => activeTaskIds.has(task.id));
  const sidecarReviewPending = Boolean(session.sidecar_review_in_flight_for_task);
  const orderMap = new Map(queue.task_order.map((taskId, index) => [taskId, index]));

  const candidates = queue.tasks
    .filter((task) => task.status === "ready")
    .filter((task) => task.blocked_by === null)
    .filter((task) => describeTaskTrackingGate(task, session).valid)
    .filter((task) => sidecarReviewPending || !session.tracking_gate_required || sameWorkstreamBinding(
      task.workstream_id,
      session.active_workstream_id,
    ))
    .filter((task) => sidecarReviewPending || task.parallelizable || activeTasks.length === 0)
    .filter((task) => task.depends_on.every((dependencyId) => {
      const dependency = findTask(queue, dependencyId);
      return dependency ? dependency.status === "done" : true;
    }))
    .filter((task) => !activeTasks.some((activeTask) => writeScopeConflicts(task.write_scope, activeTask.write_scope)))
    .sort((left, right) => {
      if (left.priority !== right.priority) {
        return left.priority - right.priority;
      }
      return (orderMap.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (orderMap.get(right.id) ?? Number.MAX_SAFE_INTEGER);
    });

  return candidates[0] || null;
}

function computeNextQueuedReview(queue) {
  const orderMap = new Map(queue.task_order.map((taskId, index) => [taskId, index]));
  const candidates = queue.tasks
    .filter((task) => task.status === "ready_for_review")
    .filter((task) => task.blocked_by === null)
    .sort((left, right) => {
      if (left.priority !== right.priority) {
        return left.priority - right.priority;
      }
      return (orderMap.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (orderMap.get(right.id) ?? Number.MAX_SAFE_INTEGER);
    });

  return candidates[0] || null;
}

function activeReviewTaskId(session, queue) {
  if (session.review_in_flight_for_task) {
    return session.review_in_flight_for_task;
  }
  if (session.status === "closed" || session.phase !== "review_loop") {
    return null;
  }
  const currentTask = findTask(queue, session.current_task_id);
  if (currentTask && currentTask.status === "in_review" && currentTask.blocked_by === null) {
    return currentTask.id;
  }
  const activeReviewTask = queue.tasks.find((task) => task.status === "in_review" && task.blocked_by === null);
  return activeReviewTask ? activeReviewTask.id : null;
}

function hasActiveReview(options) {
  const reviewer = options.reviewer || null;
  if (reviewer) {
    validateAgentRole("reviewer", reviewer);
  }
  const { session, queue } = loadState();
  if (reviewer && reviewer !== session.reviewer) {
    console.log(JSON.stringify({
      reviewer,
      active: false,
      active_task_id: null,
      reason: "reviewer_not_current_session_reviewer",
    }, null, 2));
    return;
  }
  const activeTaskId = activeReviewTaskId(session, queue);
  console.log(JSON.stringify({
    reviewer: reviewer || session.reviewer,
    active: activeTaskId !== null,
    active_task_id: activeTaskId,
  }, null, 2));
}

function queueReview(options) {
  const taskId = options["task-id"];
  if (!taskId) {
    fail("queue-review requires --task-id");
  }
  const { session, queue } = loadState();
  const reviewer = options.reviewer || null;
  if (reviewer) {
    validateAgentRole("reviewer", reviewer);
    if (reviewer !== session.reviewer) {
      fail(`Refusing queue-review: reviewer '${reviewer}' is not the active session reviewer '${session.reviewer}'`);
    }
  }

  const activeTaskId = activeReviewTaskId(session, queue);
  const task = ensureTask(queue, taskId, {
    title: taskId,
    owner: options.owner || session.implementer,
    status: "ready_for_review",
    notes: "Created from queue-review transition",
  });
  if (["done", "cancelled"].includes(task.status)) {
    fail(`Refusing queue-review: task '${taskId}' is '${task.status}'`);
  }

  const transitionTimestamp = nowIso();
  if (activeTaskId && activeTaskId !== taskId) {
    task.status = "ready_for_review";
    task.owner = options.owner || task.owner || session.implementer;
    task.blocked_by = null;
    appendTaskNote(task, `[${transitionTimestamp}] Queued for review while '${activeTaskId}' is active.`);
    saveState(session, queue);
    console.log(JSON.stringify({
      task_id: taskId,
      status: task.status,
      reviewer: session.reviewer,
      active_review_task_id: activeTaskId,
      queued: true,
    }, null, 2));
    return;
  }

  task.status = "in_review";
  task.owner = options.owner || task.owner || session.implementer;
  task.blocked_by = null;
  appendTaskNote(task, `[${transitionTimestamp}] Activated for review via queue-review.`);
  session.current_task_id = taskId;
  session.active_owner = session.reviewer;
  session.awaiting = session.reviewer;
  session.phase = "review_loop";
  session.status = "review_requested";
  session.review_in_flight_for_task = taskId;
  session.sidecar_review_in_flight_for_task = null;
  session.blocked_by = null;
  session.timestamps.updated_at = transitionTimestamp;
  saveState(session, queue, { touchTimestamp: false });
  console.log(JSON.stringify({
    task_id: taskId,
    status: task.status,
    reviewer: session.reviewer,
    active_review_task_id: taskId,
    queued: false,
  }, null, 2));
}

function promoteNextReview(options) {
  const reviewer = options.reviewer || null;
  if (reviewer) {
    validateAgentRole("reviewer", reviewer);
  }
  const { session, queue } = loadState();
  if (reviewer && reviewer !== session.reviewer) {
    fail(`Refusing promote-next-review: reviewer '${reviewer}' is not the active session reviewer '${session.reviewer}'`);
  }
  const activeTaskId = activeReviewTaskId(session, queue);
  if (activeTaskId) {
    fail(`Refusing promote-next-review: review '${activeTaskId}' is already active`);
  }
  const nextQueuedReview = computeNextQueuedReview(queue);
  if (!nextQueuedReview) {
    console.log(JSON.stringify({
      promoted: false,
      task_id: null,
      reviewer: session.reviewer,
    }, null, 2));
    return;
  }

  const transitionTimestamp = nowIso();
  nextQueuedReview.status = "in_review";
  nextQueuedReview.blocked_by = null;
  appendTaskNote(nextQueuedReview, `[${transitionTimestamp}] Promoted from ready_for_review.`);
  session.current_task_id = nextQueuedReview.id;
  session.active_owner = session.reviewer;
  session.awaiting = session.reviewer;
  session.phase = "review_loop";
  session.status = "review_requested";
  session.review_in_flight_for_task = nextQueuedReview.id;
  session.sidecar_review_in_flight_for_task = null;
  session.blocked_by = null;
  session.timestamps.updated_at = transitionTimestamp;
  saveState(session, queue, { touchTimestamp: false });
  console.log(JSON.stringify({
    promoted: true,
    task_id: nextQueuedReview.id,
    reviewer: session.reviewer,
  }, null, 2));
}

function selectNextReadyTask() {
  const { session, queue } = loadState();
  const next = computeNextReadyTask(session, queue);
  const currentTask = findTask(queue, session.current_task_id);
  const response = {
    current_task_id: session.current_task_id,
    sidecar_review_in_flight_for_task: session.sidecar_review_in_flight_for_task,
    active_owner: session.active_owner,
    ready_queue_count: countReadyTasks(queue),
    ready_for_review_count: countReadyForReviewTasks(queue),
    current_task_gate: describeTaskTrackingGate(currentTask, session, { useSessionFallback: true }),
    selected_task: next,
    selected_task_gate: next ? describeTaskTrackingGate(next, session) : null,
  };

  console.log(JSON.stringify(response, null, 2));
}

function setInterrupt(mode, options) {
  const { session, queue } = loadState();

  if (mode === "start") {
    const previousPhase = session.phase;
    session.phase = "interrupted";
    session.user_interrupt = {
      pending: true,
      handled_by: options["handled-by"] || session.active_owner,
      return_to_role: options["return-role"] || session.resume_role_after_interrupt || "idle",
      return_to_phase: options["return-phase"] || previousPhase,
      return_to_task_id: options["return-task-id"] || session.current_task_id,
    };
    session.resume_role_after_interrupt = session.user_interrupt.return_to_role;
    session.resume_task_id_after_interrupt = session.user_interrupt.return_to_task_id;
  } else if (mode === "clear") {
    session.user_interrupt = {
      pending: false,
      handled_by: null,
      return_to_role: null,
      return_to_phase: null,
      return_to_task_id: null,
    };
    const restoredPhase = options["phase"] || "implementation";
    if (!VALID_PHASES.has(restoredPhase)) {
      fail(`Invalid --phase value '${restoredPhase}' for interrupt clear`);
    }
    session.phase = restoredPhase;
  } else {
    fail(`Unknown interrupt mode '${mode}'. Expected 'start' or 'clear'`);
  }

  saveState(session, queue);
  console.log(JSON.stringify(session.user_interrupt, null, 2));
}

function roleSwap(options) {
  const implementer = options.implementer;
  const reviewer = options.reviewer;
  const phase = options.phase || "implementation";
  const { session, queue } = loadState();
  const taskId = options["task-id"] || session.current_task_id || "mailbox-round";

  if (!implementer || !reviewer) {
    fail("role-swap requires --implementer and --reviewer");
  }
  validateDistinctRoles(implementer, reviewer);
  validateRoleTransitionPhase("role-swap", phase);

  const activeOwner = options["active-owner"] || implementer;
  const awaiting = options.awaiting || activeOwner;
  validateAgentTurnOwner("active-owner", activeOwner);
  validateAgentTurnOwner("awaiting", awaiting);

  const previousImplementer = session.implementer;
  const previousReviewer = session.reviewer;
  assertRoleTransitionCanProceed(session, taskId, "role-swap", options);

  session.implementer = implementer;
  session.reviewer = reviewer;
  session.current_task_id = taskId;
  session.active_owner = activeOwner;
  session.awaiting = awaiting;
  session.phase = phase;
  session.status = options.status || statusForPhase(phase, session.status);
  session.review_in_flight_for_task = phase === "review_loop" ? taskId : null;
  session.sidecar_review_in_flight_for_task = null;
  session.blocked_by = null;
  resetRoundPointers(session);

  const task = ensureTask(queue, taskId, {
    title: taskId,
    owner: implementer,
    status: phase === "review_loop" ? "in_review" : "in_progress",
    notes: "Created from role-swap transition",
  });
  if (["cancelled", "done"].includes(task.status)) {
    fail(`Refusing role-swap: task '${taskId}' is '${task.status}' and cannot be silently reopened`);
  }
  task.owner = implementer;
  task.status = phase === "review_loop" ? "in_review" : "in_progress";
  task.blocked_by = null;
  if (session.tracking_gate_required || task.tracking_required) {
    enforceTaskTracking(task, session, { commandName: "role-swap" });
  }
  appendTaskNote(
    task,
    `[${nowIso()}] Role swap: implementer ${previousImplementer} -> ${implementer}; reviewer ${previousReviewer} -> ${reviewer}`,
  );

  saveState(session, queue);
  console.log(
    JSON.stringify(
      {
        current_task_id: session.current_task_id,
        implementer: session.implementer,
        reviewer: session.reviewer,
        active_owner: session.active_owner,
        awaiting: session.awaiting,
        phase: session.phase,
        status: session.status,
      },
      null,
      2,
    ),
  );
}

function handoffTask(options) {
  const from = options.from;
  const to = options.to;
  if (!from || !to) {
    fail("handoff requires --from and --to");
  }
  validateAgentRole("from", from);
  validateAgentRole("to", to);
  if (from === to) {
    fail("handoff requires different --from and --to values");
  }

  const { session, queue } = loadState();
  const taskId = options["task-id"] || session.current_task_id || "mailbox-round";
  if (!options.phase && session.phase === "closed") {
    fail("handoff requires an explicit --phase when reactivating a closed session");
  }
  const phase = options.phase || session.phase;
  validateRoleTransitionPhase("handoff", phase);
  assertRoleTransitionCanProceed(session, taskId, "handoff", options);

  const currentTask = ensureTask(queue, taskId, {
    title: taskId,
    owner: from,
    status: phase === "review_loop" ? "in_review" : "in_progress",
    notes: "Created from handoff transition",
  });
  if (currentTask.owner !== from) {
    fail(`Refusing handoff: task '${taskId}' is owned by '${currentTask.owner}', not '${from}'`);
  }
  if (currentTask.blocked_by !== null || currentTask.status === "blocked") {
    fail(`Refusing handoff: task '${taskId}' is blocked by '${currentTask.blocked_by ?? "unknown blocker"}'`);
  }
  if (["paused", "done", "cancelled"].includes(currentTask.status)) {
    fail(`Refusing handoff: task '${taskId}' is '${currentTask.status}' and cannot be silently reopened`);
  }

  let newImplementer = options["new-implementer"] || session.implementer;
  let newReviewer = options["new-reviewer"] || session.reviewer;

  if (!options["new-implementer"] && session.implementer === from) {
    newImplementer = to;
  }
  if (!options["new-reviewer"] && session.implementer === from && session.reviewer === to) {
    newReviewer = from;
  } else if (!options["new-reviewer"] && newReviewer === newImplementer) {
    newReviewer = from;
  }

  validateDistinctRoles(newImplementer, newReviewer);

  const activeOwner = options["active-owner"] || to;
  const awaiting = options.awaiting || activeOwner;
  validateAgentTurnOwner("active-owner", activeOwner);
  validateAgentTurnOwner("awaiting", awaiting);

  session.implementer = newImplementer;
  session.reviewer = newReviewer;
  session.current_task_id = taskId;
  session.active_owner = activeOwner;
  session.awaiting = awaiting;
  session.phase = phase;
  session.status = options.status || statusForPhase(phase, session.status);
  session.review_in_flight_for_task = phase === "review_loop" ? taskId : null;
  session.sidecar_review_in_flight_for_task = null;
  session.blocked_by = null;
  resetRoundPointers(session);

  currentTask.owner = to;
  currentTask.status = phase === "review_loop" ? "in_review" : "in_progress";
  currentTask.blocked_by = null;
  if (session.tracking_gate_required || currentTask.tracking_required) {
    enforceTaskTracking(currentTask, session, { commandName: "handoff" });
  }
  appendTaskNote(
    currentTask,
    `[${nowIso()}] Handoff: ${from} -> ${to}; implementer=${newImplementer}; reviewer=${newReviewer}`,
  );

  saveState(session, queue);
  console.log(
    JSON.stringify(
      {
        current_task_id: session.current_task_id,
        implementer: session.implementer,
        reviewer: session.reviewer,
        active_owner: session.active_owner,
        awaiting: session.awaiting,
        phase: session.phase,
        status: session.status,
        sidecar_review_in_flight_for_task: session.sidecar_review_in_flight_for_task,
        handoff: { from, to },
      },
      null,
      2,
    ),
  );
}

function printStatus() {
  const { session, queue } = loadState();
  const currentTask = findTask(queue, session.current_task_id);
  const summary = {
    session,
    current_task_gate: describeTaskTrackingGate(currentTask, session, { useSessionFallback: true }),
    queue_summary: {
      ready_tasks: queue.tasks
        .filter((task) => task.status === "ready")
        .map((task) => ({ id: task.id, task_gate: describeTaskTrackingGate(task, session) })),
      ready_for_review_tasks: queue.tasks
        .filter((task) => task.status === "ready_for_review")
        .map((task) => ({ id: task.id, owner: task.owner, task_gate: describeTaskTrackingGate(task, session) })),
      active_tasks: queue.tasks
        .filter((task) => ["in_progress", "in_review", "ready_for_review", "blocked"].includes(task.status))
        .map((task) => ({
          id: task.id,
          status: task.status,
          owner: task.owner,
          blocked_by: task.blocked_by,
          task_gate: describeTaskTrackingGate(task, session, { useSessionFallback: task.id === session.current_task_id }),
        })),
    },
  };
  console.log(JSON.stringify(summary, null, 2));
}

function main() {
  const { positionals, options } = parseArgs(process.argv.slice(2));
  const command = positionals[0];

  switch (command) {
    case "ensure":
      ensureBaseFiles();
      normalizeAndSave();
      printStatus();
      return;
    case "status":
      printStatus();
      return;
    case "archive-request":
      archiveRequest(options);
      return;
    case "archive-response":
      archiveResponse(options);
      return;
    case "close-review":
      closeReview(options);
      return;
    case "clear-pointer":
      clearPointer(options);
      return;
    case "next-ready-task":
      selectNextReadyTask();
      return;
    case "has-active-review":
      hasActiveReview(options);
      return;
    case "queue-review":
      queueReview(options);
      return;
    case "promote-next-review":
      promoteNextReview(options);
      return;
    case "interrupt":
      setInterrupt(positionals[1], options);
      return;
    case "role-swap":
      roleSwap(options);
      return;
    case "handoff":
      handoffTask(options);
      return;
    case "bind-docs":
      bindDocsCommand(options);
      return;
    case "track-task":
      trackTaskCommand(options);
      return;
    default:
      fail(
        "Unknown command. Expected one of: ensure, status, archive-request, archive-response, close-review, clear-pointer, next-ready-task, has-active-review, queue-review, promote-next-review, interrupt, role-swap, handoff, bind-docs, track-task",
      );
  }
}

main();
