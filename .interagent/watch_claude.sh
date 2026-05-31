#!/bin/bash
# watch_claude.sh — optional Claude idle nudge supervisor.
#
# This script is intentionally opt-in. It does not read or write mailbox files;
# it only sends a short tmux nudge to Claude when the pane has stayed unchanged
# long enough to look idle.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
NORMALIZE_SCRIPT="${INTERAGENT_DIR}/normalize_claude_session.sh"

SESSION="claude-${PROJECT_NAME}"
POLL_SECONDS="${CLAUDE_SUPERVISOR_POLL_SECONDS:-60}"
IDLE_SECONDS="${CLAUDE_SUPERVISOR_IDLE_SECONDS:-60}"
RENUDGE_SECONDS="${CLAUDE_SUPERVISOR_RENUDGE_SECONDS:-60}"
MAX_LOOPS="${CLAUDE_SUPERVISOR_MAX_LOOPS:-0}"

echo "[watch] Claude watchdog target=${SESSION}; poll=${POLL_SECONDS}s, idle=${IDLE_SECONDS}s, renudge=${RENUDGE_SECONDS}s"

if ! command -v tmux >/dev/null 2>&1; then
    echo "[ERROR] tmux is required but not installed."
    exit 1
fi
pane_hash() {
    printf '%s' "$1" | sha256sum | awk '{print $1}'
}

capture_pane() {
    tmux capture-pane -p -t "$SESSION" -S -120 2>/dev/null || true
}

attached_client_count() {
    local clients
    clients="$(tmux list-clients -t "$SESSION" 2>/dev/null || true)"
    if [[ -z "$clients" ]]; then
        echo 0
    else
        printf '%s\n' "$clients" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' '
    fi
}

now_seconds() {
    date +%s
}

send_nudge() {
    local idle_for="$1"
    local reason="idle-nudge:${idle_for}s"
    local message
    message="Claude, you are not to remain idle while Codex is reviewing. You are to continue on to the next task in queue if you've completed your current task as long as there is nothing current that is blocking parallel work. You've specifically been reminded never to remain idle. If you are currently working on a task, ignore this nudge. If you are idling, then get to working on the next task/slice that is available for you to work on. This is only a reminder and nothing else."

    tmux send-keys -t "$SESSION" -l "$message"
    sleep 0.3
    tmux send-keys -t "$SESSION" Enter
    echo "[watch] Sent Claude idle nudge (${reason})"
}

last_hash=""
last_change_at="$(now_seconds)"
last_nudge_at=0
loop_count=0

while true; do
    loop_count=$((loop_count + 1))

    if ! tmux has-session -t "$SESSION" 2>/dev/null; then
        echo "[watch] Claude tmux session '$SESSION' is not running"
    else
        pane="$(capture_pane)"
        hash="$(pane_hash "$pane")"
        attached_count="$(attached_client_count)"

        if [[ "$attached_count" == "0" ]] && printf '%s' "$pane" | grep -Fq "How is Claude doing this session? (optional)"; then
            "$NORMALIZE_SCRIPT" --reason watcher-known-optional-prompt --quiet || true
        fi

        now="$(now_seconds)"
        if [[ "$hash" != "$last_hash" ]]; then
            last_hash="$hash"
            last_change_at="$now"
        else
            idle_for=$((now - last_change_at))
            since_nudge=$((now - last_nudge_at))
            if (( idle_for >= IDLE_SECONDS && since_nudge >= RENUDGE_SECONDS )); then
                send_nudge "$idle_for"
                last_nudge_at="$now"
            fi
        fi
    fi

    if [[ "$MAX_LOOPS" != "0" && "$loop_count" -ge "$MAX_LOOPS" ]]; then
        break
    fi
    sleep "$POLL_SECONDS"
done
