#!/bin/bash
# stop_codex.sh — cleanly shut down the persistent Codex tmux session.
# Also removes a legacy supervisor session if one exists from an older protocol.
#
# Usage: ./.interagent/stop_codex.sh

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"

SESSION="codex-${PROJECT_NAME}"
SUPERVISOR_SESSION="codex-supervisor-${PROJECT_NAME}"
LEGACY_PIPE="/tmp/codex_pipe_${PROJECT_NAME}"

echo "=== Codex Inter-Agent Shutdown ==="

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

if [[ -p "$LEGACY_PIPE" ]]; then
    rm -f "$LEGACY_PIPE"
    echo "[ok] Removed legacy FIFO: $LEGACY_PIPE"
fi

echo "[done] Codex inter-agent session stopped."
