#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
BROKER_SOURCE="$ROOT/.interagent/broker.mjs"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

write_artifact() {
  local file="$1"
  local verdict="$2"
  mkdir -p "$(dirname "$file")"
  cat > "$file" <<EOF
# Smoke Artifact

PER .interagent/docs/INTERAGENT_PROTOCOL.md (READ IT)

Smoke artifact for close-review verification.

$verdict
EOF
}

write_state() {
  local interagent_dir="$1"
  local task_id="$2"
  local latest_request="$3"
  local latest_response="$4"
  local timestamp="2026-06-01T00:00:00.000Z"
  cat > "$interagent_dir/session.json" <<EOF
{
  "schema_version": 1,
  "session_id": "smoke-interagent",
  "goal": "close-review smoke",
  "status": "awaiting_concurrence",
  "phase": "review_loop",
  "round": 1,
  "implementer": "codex",
  "reviewer": "claude",
  "active_owner": "codex",
  "awaiting": "codex",
  "current_task_id": "$task_id",
  "active_runbook_path": null,
  "active_progress_path": null,
  "runbook_gate_required": false,
  "active_workstream_id": null,
  "active_task_kind": null,
  "active_next_step": null,
  "active_tracking_updated_at": null,
  "active_progress_log_path": null,
  "active_context_paths": [],
  "tracking_gate_required": false,
  "review_in_flight_for_task": "$task_id",
  "sidecar_review_in_flight_for_task": null,
  "blocked_by": null,
  "last_verdict": "APPROVED",
  "latest_request_path": "$latest_request",
  "latest_response_path": "$latest_response",
  "resume_task_id_after_interrupt": null,
  "resume_role_after_interrupt": "idle",
  "ready_queue_count": 0,
  "user_interrupt": {
    "pending": false,
    "handled_by": null,
    "return_to_role": null,
    "return_to_phase": null,
    "return_to_task_id": null
  },
  "timestamps": {
    "updated_at": "$timestamp",
    "last_request_at": "$timestamp",
    "last_response_at": "$timestamp"
  }
}
EOF
  cat > "$interagent_dir/implementation_queue.json" <<EOF
{
  "schema_version": 1,
  "session_id": "smoke-interagent",
  "goal": "close-review smoke",
  "task_order": ["$task_id"],
  "tasks": [
    {
      "id": "$task_id",
      "title": "$task_id",
      "status": "in_review",
      "owner": "codex",
      "priority": 1,
      "blocked_by": null,
      "parallelizable": false,
      "write_scope": [],
      "depends_on": [],
      "notes": "smoke fixture",
      "runbook_path": null,
      "progress_path": null,
      "runbook_required": false,
      "workstream_id": null,
      "task_kind": null,
      "next_step": null,
      "tracking_updated_at": null,
      "progress_log_path": null,
      "context_paths": [],
      "tracking_required": false
    }
  ]
}
EOF
}

make_fixture() {
  local name="$1"
  local dir="$TMP_ROOT/$name"
  local interagent_dir="$dir/.interagent"
  mkdir -p "$interagent_dir/requests" "$interagent_dir/responses" "$interagent_dir/progress"
  cp "$BROKER_SOURCE" "$interagent_dir/broker.mjs"
  touch \
    "$interagent_dir/claude_request.md" \
    "$interagent_dir/claude_response.md" \
    "$interagent_dir/codex_request.md" \
    "$interagent_dir/codex_response.md"
  printf '%s\n' "$dir"
}

run_close_expect_success() {
  local dir="$1"
  local output
  output="$(cd "$dir" && node .interagent/broker.mjs close-review --task-id "$2" --next-step "closed by smoke")"
  printf '%s\n' "$output" | grep -q '"task_status": "done"'
  printf '%s\n' "$output" | grep -q '"session_status": "closed"'
}

run_close_expect_refusal() {
  local dir="$1"
  local task_id="$2"
  local expected="$3"
  local output
  set +e
  output="$(cd "$dir" && node .interagent/broker.mjs close-review --task-id "$task_id" --next-step "must refuse" 2>&1)"
  local status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    echo "[interagent-close-review-smoke] expected refusal but close-review succeeded for $task_id" >&2
    printf '%s\n' "$output" >&2
    return 1
  fi
  printf '%s\n' "$output" | grep -q "$expected"
}

case_ordinary_approved_closes() {
  local dir
  dir="$(make_fixture ordinary-approved)"
  local ia="$dir/.interagent"
  write_artifact "$ia/requests/001-codex-to-claude-ordinary-task.md" "APPROVED"
  write_artifact "$ia/responses/001-claude-to-codex-ordinary-task.md" "APPROVED"
  write_state "$ia" "ordinary-task" "requests/001-codex-to-claude-ordinary-task.md" "responses/001-claude-to-codex-ordinary-task.md"
  run_close_expect_success "$dir" "ordinary-task"
}

case_implemented_reciprocal_approved_closes() {
  local dir
  dir="$(make_fixture implemented-approved)"
  local ia="$dir/.interagent"
  write_artifact "$ia/requests/001-codex-to-claude-implemented-task.md" "IMPLEMENTED"
  write_artifact "$ia/responses/001-claude-to-codex-implemented-task.md" "APPROVED"
  write_artifact "$ia/responses/001-codex-to-claude-implemented-task.md" "APPROVED"
  write_state "$ia" "implemented-task" "requests/001-codex-to-claude-implemented-task.md" "responses/001-claude-to-codex-implemented-task.md"
  run_close_expect_success "$dir" "implemented-task"
}

case_non_approved_reciprocal_refuses() {
  local dir
  dir="$(make_fixture non-approved-reciprocal)"
  local ia="$dir/.interagent"
  write_artifact "$ia/requests/001-codex-to-claude-implemented-task.md" "IMPLEMENTED"
  write_artifact "$ia/responses/001-claude-to-codex-implemented-task.md" "APPROVED"
  write_artifact "$ia/responses/001-codex-to-claude-implemented-task.md" "CHANGES_REQUESTED"
  write_state "$ia" "implemented-task" "requests/001-codex-to-claude-implemented-task.md" "responses/001-claude-to-codex-implemented-task.md"
  run_close_expect_refusal "$dir" "implemented-task" "reciprocal response verdicts must both be APPROVED"
}

case_missing_reciprocal_refuses() {
  local dir
  dir="$(make_fixture missing-reciprocal)"
  local ia="$dir/.interagent"
  write_artifact "$ia/requests/001-codex-to-claude-implemented-task.md" "IMPLEMENTED"
  write_artifact "$ia/responses/001-claude-to-codex-implemented-task.md" "APPROVED"
  write_state "$ia" "implemented-task" "requests/001-codex-to-claude-implemented-task.md" "responses/001-claude-to-codex-implemented-task.md"
  run_close_expect_refusal "$dir" "implemented-task" "requires both reciprocal APPROVED response artifacts"
}

case_wrong_task_artifact_refuses() {
  local dir
  dir="$(make_fixture wrong-task)"
  local ia="$dir/.interagent"
  write_artifact "$ia/requests/001-codex-to-claude-implemented-task.md" "IMPLEMENTED"
  write_artifact "$ia/responses/001-claude-to-codex-other-task.md" "APPROVED"
  write_artifact "$ia/responses/001-claude-to-codex-implemented-task.md" "APPROVED"
  write_artifact "$ia/responses/001-codex-to-claude-implemented-task.md" "APPROVED"
  write_state "$ia" "implemented-task" "requests/001-codex-to-claude-implemented-task.md" "responses/001-claude-to-codex-other-task.md"
  run_close_expect_refusal "$dir" "implemented-task" "latest response artifact does not target task"
}

case_ordinary_approved_closes
case_implemented_reciprocal_approved_closes
case_non_approved_reciprocal_refuses
case_missing_reciprocal_refuses
case_wrong_task_artifact_refuses

echo "[interagent-close-review-smoke] OK"
