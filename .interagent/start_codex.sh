#!/bin/bash
# start_codex.sh — Launch Codex CLI in a persistent detached tmux session.
#
# Architecture:
# - Codex runs as a full-screen TUI in tmux (`codex-<project>`)
# - `prime_codex.sh` sends the short bootstrap pointer into that TUI on fresh start
# - no watcher or supervisor session is launched; work arrives only through
#   explicit mailbox files plus explicit tmux doorbells
#
# Usage:
#   ./.interagent/start_codex.sh
#   tmux attach -t codex-speed-ball
#   Ctrl+B then D
#
# Shutdown:
#   ./.interagent/stop_codex.sh

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
BROKER="${INTERAGENT_DIR}/broker.mjs"
PRIME_SCRIPT="${INTERAGENT_DIR}/prime_codex.sh"

SESSION="codex-${PROJECT_NAME}"

echo "=== Codex Inter-Agent Startup ==="
echo "Project: $PROJECT_DIR"
echo "Session: $SESSION"
echo

if ! command -v tmux >/dev/null 2>&1; then
    echo "[ERROR] tmux is required but not installed."
    echo "        Install: sudo apt install tmux"
    exit 1
fi
if ! command -v codex >/dev/null 2>&1; then
    echo "[ERROR] 'codex' binary not found in PATH."
    exit 1
fi
if ! command -v node >/dev/null 2>&1; then
    echo "[ERROR] node is required for .interagent/broker.mjs but was not found."
    exit 1
fi
if [[ ! -f "$BROKER" ]]; then
    echo "[ERROR] Broker not found: $BROKER"
    exit 1
fi
if [[ ! -x "$PRIME_SCRIPT" ]]; then
    echo "[ERROR] Prime script not executable: $PRIME_SCRIPT"
    exit 1
fi

"$BROKER" ensure >/dev/null
echo "[ok] Ensured broker-backed .interagent state files and artifact directories"

FRESH_START=0
if tmux has-session -t "$SESSION" 2>/dev/null; then
    echo "[ok] tmux session '$SESSION' already running"
    echo "[ok] Leaving active Codex untouched; use explicit doorbells for new work"
else
    tmux new-session -d -s "$SESSION" -c "$PROJECT_DIR" codex
    echo "[new] Launched tmux session '$SESSION' running 'codex' in $PROJECT_DIR"
    FRESH_START=1

    sleep 1
    if ! tmux has-session -t "$SESSION" 2>/dev/null; then
        echo "[ERROR] tmux session died immediately after launch."
        echo "        Try running 'codex' manually in a terminal to see the error."
        exit 1
    fi
fi

if [[ "$FRESH_START" == "1" ]]; then
    # New repos can trigger Codex's project trust prompt before the normal TUI
    # input box exists. Prime only after accepting that prompt, otherwise the
    # bootstrap message is typed into the trust screen and the pane may exit.
    PANE_TEXT="$(tmux capture-pane -t "$SESSION" -p -S -80 2>/dev/null || true)"
    if printf '%s\n' "$PANE_TEXT" | grep -Fq "Do you trust the contents of this directory?"; then
        echo "[trust] Codex is asking to trust this repo; accepting for this project startup"
        tmux send-keys -t "$SESSION" 1 Enter
        sleep 1
        if ! tmux has-session -t "$SESSION" 2>/dev/null; then
            echo "[ERROR] tmux session exited after the Codex trust prompt."
            echo "        Try running 'codex' manually in $PROJECT_DIR to inspect the failure."
            exit 1
        fi
    fi
    "$PRIME_SCRIPT" --reason "fresh-start"
fi

echo
echo "Next steps:"
echo "  - Attach to watch Codex work:    tmux attach -t $SESSION"
echo "  - Detach from tmux:              Ctrl+B, then D"
echo "  - Send work to Codex:            ./.interagent/send_to_codex.sh"
echo "  - Send work to Claude:           ./.interagent/send_to_claude.sh"
echo "  - Start Claude doorbell session: ./.interagent/start_claude.sh"
echo "  - Shutdown:                      ./.interagent/stop_codex.sh"
