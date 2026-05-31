#!/bin/bash
# wait_for_claude.sh - optional caller-side blocker until Claude writes a response.
#
# This helper is not an agent watcher and is not a work-discovery transport.
# Agents receive work only through explicit tmux doorbells. Use this script only
# when a human or calling script intentionally wants to block for a reply.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
RESPONSE_FILE="${INTERAGENT_DIR}/claude_response.md"
BROKER="${INTERAGENT_DIR}/broker.mjs"

TIMEOUT="${1:-0}" # 0 = wait forever
POLL_SLICE_SECONDS=2
TASK_ID_ARGS=()
if [[ -n "${INTERAGENT_TASK_ID:-}" ]]; then
    TASK_ID_ARGS=(--task-id "$INTERAGENT_TASK_ID")
fi

if [[ ! -f "$RESPONSE_FILE" ]]; then
    : > "$RESPONSE_FILE"
fi
if [[ ! -f "$BROKER" ]]; then
    echo "[ERROR] Broker not found: $BROKER"
    exit 1
fi

"$BROKER" ensure >/dev/null

TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"
if [[ "$TIMEOUT" == "0" ]]; then
    echo "[wait] $TIMESTAMP - polling ${RESPONSE_FILE} for Claude response (no timeout)"
else
    echo "[wait] $TIMESTAMP - polling ${RESPONSE_FILE} for Claude response (timeout: ${TIMEOUT}s)"
fi

DEADLINE=0
if [[ "$TIMEOUT" != "0" ]]; then
    DEADLINE=$(( $(date +%s) + TIMEOUT ))
fi

while [[ ! -s "$RESPONSE_FILE" ]]; do
    if [[ "$TIMEOUT" != "0" ]]; then
        NOW=$(date +%s)
        if (( NOW >= DEADLINE )); then
            DONE_TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"
            echo "[timeout] $DONE_TIMESTAMP - no response within ${TIMEOUT}s"
            exit 2
        fi
    fi
    sleep "$POLL_SLICE_SECONDS"
done

DONE_TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"
ARCHIVED_RESPONSE="$("$BROKER" archive-response --from claude --to codex --source "$RESPONSE_FILE" "${TASK_ID_ARGS[@]}")"
echo "[ok] $DONE_TIMESTAMP - response file updated and non-empty"
echo "[history] archived response: $ARCHIVED_RESPONSE"
echo "$RESPONSE_FILE"
