#!/bin/bash
# start_claude_watchdog.sh — opt-in idle watchdog for the Claude tmux session.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
WATCH_SCRIPT="${INTERAGENT_DIR}/watch_claude.sh"

SESSION="claude-${PROJECT_NAME}"
SUPERVISOR_SESSION="claude-supervisor-${PROJECT_NAME}"

echo "=== Claude Idle Watchdog Startup ==="
echo "Project:   $PROJECT_DIR"
echo "Claude:    $SESSION"
echo "Watchdog:  $SUPERVISOR_SESSION"
echo "Interval:  ${CLAUDE_SUPERVISOR_POLL_SECONDS:-60}s"
echo

if ! command -v tmux >/dev/null 2>&1; then
    echo "[ERROR] tmux is required but not installed."
    exit 1
fi
if [[ ! -x "$WATCH_SCRIPT" ]]; then
    echo "[ERROR] Watch script is missing or not executable: $WATCH_SCRIPT"
    exit 1
fi
if ! tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "[ERROR] Claude tmux session '$SESSION' is not running."
    echo "        Start Claude first: ./.interagent/start_claude.sh"
    exit 1
fi

if tmux has-session -t "$SUPERVISOR_SESSION" 2>/dev/null; then
    echo "[ok] Claude watchdog '$SUPERVISOR_SESSION' already running"
else
    tmux new-session -d -s "$SUPERVISOR_SESSION" -c "$PROJECT_DIR" "$WATCH_SCRIPT"
    echo "[new] Launched Claude watchdog '$SUPERVISOR_SESSION'"
fi

echo
echo "Next steps:"
echo "  - Watch watchdog logs:  tmux attach -t $SUPERVISOR_SESSION"
echo "  - Stop watchdog only:   tmux kill-session -t $SUPERVISOR_SESSION"
echo "  - Stop Claude stack:    ./.interagent/stop_claude.sh"
