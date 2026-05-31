#!/bin/bash
# normalize_claude_session.sh — dismiss known non-work Claude TUI prompts so
# mailbox doorbells land in a ready session.

set -euo pipefail

INTERAGENT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_DIR="$(dirname "$INTERAGENT_DIR")"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
SESSION="claude-${PROJECT_NAME}"

REASON="manual-normalize"
QUIET=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reason)
            REASON="${2:-}"
            shift 2
            ;;
        --quiet)
            QUIET=1
            shift
            ;;
        *)
            echo "[ERROR] Unknown argument: $1"
            echo "Usage: $0 [--reason <value>] [--quiet]"
            exit 1
            ;;
    esac
done

if ! tmux has-session -t "$SESSION" 2>/dev/null; then
    if [[ "$QUIET" != "1" ]]; then
        echo "[ERROR] tmux session '$SESSION' is not running."
    fi
    exit 1
fi

PANE_SNAPSHOT="$(tmux capture-pane -t "$SESSION" -p 2>/dev/null || true)"
KNOWN_OPTIONAL_PROMPT=0
if printf '%s' "$PANE_SNAPSHOT" | grep -Fq "How is Claude doing this session? (optional)"; then
    KNOWN_OPTIONAL_PROMPT=1
fi

if [[ "$KNOWN_OPTIONAL_PROMPT" != "1" ]]; then
    if [[ "$QUIET" != "1" ]]; then
        echo "[ok] Claude session '$SESSION' does not need normalization"
    fi
    exit 0
fi

tmux send-keys -t "$SESSION" 0
sleep 0.3

if [[ "$QUIET" != "1" ]]; then
    echo "[sent] Dismissed known optional Claude prompt in '$SESSION' (reason: $REASON)"
fi
