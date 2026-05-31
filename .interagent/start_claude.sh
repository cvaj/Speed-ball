#!/bin/bash
# start_claude.sh — Launch Claude Code in a persistent detached tmux session.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
BROKER="${INTERAGENT_DIR}/broker.mjs"

SESSION="claude-${PROJECT_NAME}"

echo "=== Claude Inter-Agent Startup ==="
echo "Project: $PROJECT_DIR"
echo "Session: $SESSION"
echo

if ! command -v tmux >/dev/null 2>&1; then
    echo "[ERROR] tmux is required but not installed."
    echo "        Install: sudo apt install tmux"
    exit 1
fi
if ! command -v claude >/dev/null 2>&1; then
    echo "[ERROR] 'claude' binary not found in PATH."
    exit 1
fi
if [[ ! -f "$BROKER" ]]; then
    echo "[ERROR] Broker not found: $BROKER"
    exit 1
fi

"$BROKER" ensure >/dev/null
echo "[ok] Ensured broker-backed .interagent state files and artifact directories"

if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "[ok] tmux session '$SESSION' already running"
    echo "[ok] Leaving active Claude untouched; use explicit doorbells for new work"
    echo "      Attach: tmux attach -t $SESSION"
    exit 0
fi

tmux new-session -d -s "$SESSION" -c "$PROJECT_DIR" claude --permission-mode bypassPermissions
echo "[new] Launched tmux session '$SESSION' running 'claude --permission-mode bypassPermissions' in $PROJECT_DIR"

sleep 2
if ! tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "[ERROR] tmux session died immediately after launch."
    echo "        Try running 'claude' manually in a terminal to see the error."
    exit 1
fi

BOOTSTRAP="Read .interagent/docs/INTERAGENT_PROTOCOL.md and use it as your active protocol. Stay doorbell-ready until an explicit tmux doorbell arrives. Do not poll the mailbox. When doorbelled, follow the consolidated review protocol: staged context for review loops, full read for non-review. Write claude_response.md and run respond_to_codex.sh."

tmux send-keys -t "$SESSION" -l "$BOOTSTRAP"
sleep 0.3
tmux send-keys -t "$SESSION" Enter
sleep 0.3
tmux send-keys -t "$SESSION" Enter

echo
echo "Next steps:"
echo "  - Attach to watch Claude work:   tmux attach -t $SESSION"
echo "  - Detach from tmux:              Ctrl+B, then D"
echo "  - Send work to Claude:           ./.interagent/send_to_claude.sh"
echo "  - Shutdown:                      ./.interagent/stop_claude.sh"
