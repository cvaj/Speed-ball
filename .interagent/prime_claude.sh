#!/bin/bash
# prime_claude.sh — Send a short bootstrap/re-prime message into the running
# Claude tmux session so it re-enters the doorbell protocol.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
BROKER="${INTERAGENT_DIR}/broker.mjs"
SESSION="claude-${PROJECT_NAME}"

REASON="manual-prime"
NEXT_TASK=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reason)
            REASON="${2:-}"
            shift 2
            ;;
        --next-task)
            NEXT_TASK="${2:-}"
            shift 2
            ;;
        *)
            echo "[ERROR] Unknown argument: $1"
            echo "Usage: $0 [--reason <value>] [--next-task <task-id>]"
            exit 1
            ;;
    esac
done

if ! tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "[ERROR] tmux session '$SESSION' is not running."
    echo "        Start Claude: ./.interagent/start_claude.sh"
    exit 1
fi
if [[ ! -f "$BROKER" ]]; then
    echo "[ERROR] Broker not found: $BROKER"
    exit 1
fi

STATUS_JSON="$($BROKER status)"
mapfile -t TRACKING_STATE < <(
    STATUS_JSON="$STATUS_JSON" node <<'NODE'
const status = JSON.parse(process.env.STATUS_JSON);
const gate = status.current_task_gate || { required: false, valid: true, workstream_id: null, task_kind: null, next_step: null, issues: [] };
console.log(gate.required ? "1" : "0");
console.log(gate.valid ? "1" : "0");
console.log(gate.workstream_id || "");
console.log(gate.task_kind || "");
console.log(gate.next_step || "");
console.log(Array.isArray(gate.issues) ? gate.issues.join(",") : "");
NODE
)

TRACKING_REQUIRED="${TRACKING_STATE[0]:-0}"
TRACKING_VALID="${TRACKING_STATE[1]:-1}"
WORKSTREAM_ID="${TRACKING_STATE[2]:-}"
TASK_KIND="${TRACKING_STATE[3]:-}"
NEXT_STEP_TEXT="${TRACKING_STATE[4]:-}"
TRACKING_ISSUES="${TRACKING_STATE[5]:-}"

mapfile -t SESSION_STATE < <(
    STATUS_JSON="$STATUS_JSON" node <<'NODE'
const status = JSON.parse(process.env.STATUS_JSON);
const session = status.session || {};
console.log(session.current_task_id || "");
console.log(session.phase || "");
console.log(session.implementer || "");
console.log(session.active_owner || "");
console.log(session.awaiting || "");
console.log(session.review_in_flight_for_task || "");
console.log(session.sidecar_review_in_flight_for_task || "");
NODE
)

CURRENT_TASK_ID="${SESSION_STATE[0]:-}"
CURRENT_PHASE="${SESSION_STATE[1]:-}"
CURRENT_IMPLEMENTER="${SESSION_STATE[2]:-}"
CURRENT_ACTIVE_OWNER="${SESSION_STATE[3]:-}"
CURRENT_AWAITING="${SESSION_STATE[4]:-}"
REVIEW_IN_FLIGHT_TASK="${SESSION_STATE[5]:-}"
SIDECAR_REVIEW_TASK="${SESSION_STATE[6]:-}"
ACTIVE_REVIEW_TASK="${SIDECAR_REVIEW_TASK:-${REVIEW_IN_FLIGHT_TASK:-}}"
CURRENT_TASK_RUNNABLE=0
if [[ "$TRACKING_REQUIRED" == "1" && "$TRACKING_VALID" == "1" && "$CURRENT_TASK_ID" != "" && "$CURRENT_IMPLEMENTER" == "claude" && "$CURRENT_ACTIVE_OWNER" == "claude" && "$CURRENT_AWAITING" == "claude" ]]; then
    CURRENT_TASK_RUNNABLE=1
fi

BOOTSTRAP="Read .interagent/docs/INTERAGENT_PROTOCOL.md in this repo and use it as your active protocol. Re-read .interagent/session.json, .interagent/implementation_queue.json, and ./.interagent/broker.mjs next-ready-task. Reason: ${REASON}."
if [[ "$TRACKING_REQUIRED" == "1" ]]; then
    BOOTSTRAP+=" Active task: ${CURRENT_TASK_ID:-MISSING}. Active workstream: ${WORKSTREAM_ID:-MISSING}. Task kind: ${TASK_KIND:-MISSING}. Next step: ${NEXT_STEP_TEXT:-MISSING}."
    if [[ "$TRACKING_VALID" != "1" ]]; then
        BOOTSTRAP+=" Tracking gate is UNSATISFIED (${TRACKING_ISSUES:-missing-state}); do not continue implementation work until the tracked task state is fixed."
    fi
fi
if [[ -n "$ACTIVE_REVIEW_TASK" ]]; then
    BOOTSTRAP+=" Codex review is still in flight for task: ${ACTIVE_REVIEW_TASK}. Treat that as a waiting lane, not an idle lane."
fi
if [[ -n "$NEXT_TASK" ]]; then
    BOOTSTRAP+=" Next ready task: ${NEXT_TASK}. This manual prime means a Codex review is already blocking another step and this queued task is the next ready parallel lane in the same workstream. Start it now unless the tracked workstream/write-scope rules block it."
elif [[ "$CURRENT_TASK_RUNNABLE" == "1" ]]; then
    BOOTSTRAP+=" No separate ready task is queued. Continue the active implementation task ${CURRENT_TASK_ID} using the tracked next step above and the bound runbook/checklist context."
else
    BOOTSTRAP+=" If no ready task is available and you do not currently own the active tracked task, stay doorbell-ready at the prompt."
fi

tmux send-keys -t "$SESSION" -l "$BOOTSTRAP"
sleep 0.3
tmux send-keys -t "$SESSION" Enter
sleep 0.3
tmux send-keys -t "$SESSION" Enter

echo "[sent] Re-primed Claude session '$SESSION' (reason: $REASON${NEXT_TASK:+, next task: $NEXT_TASK})"
