#!/bin/bash
# stop_claude.sh — cleanly shut down the persistent Claude tmux session.
# Also removes a legacy supervisor session if one exists from an older protocol.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"

SESSION="claude-${PROJECT_NAME}"
SUPERVISOR_SESSION="claude-supervisor-${PROJECT_NAME}"

echo "=== Claude Inter-Agent Shutdown ==="

if tmux has-session -t "$SESSION" 2>/dev/null; then
    tmux kill-session -t "$SESSION"
    echo "[ok] Killed tmux session '$SESSION'"
else
    echo "[ok] No tmux session '$SESSION' to kill"
fi

if tmux has-session -t "$SUPERVISOR_SESSION" 2>/dev/null; then
    tmux kill-session -t "$SUPERVISOR_SESSION"
    echo "[ok] Killed tmux session '$SUPERVISOR_SESSION'"
else
    echo "[ok] No tmux session '$SUPERVISOR_SESSION' to kill"
fi

echo "[done] Claude inter-agent session stopped."
