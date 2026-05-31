---
name: interagent-review
description: >
  Inter-agent review and implementation handoff between Claude Code and Codex CLI
  for Speed-ball via detached tmux sessions and .interagent mailbox files.
---

# Interagent Review For Speed-ball

Use this skill whenever sending implementation, review, investigation, or second-opinion work between Claude and Codex.

Read `.interagent/docs/INTERAGENT_PROTOCOL.md` before acting. The doorbell is only a pointer; the full request is in the mailbox file.

Session names are derived from the repo basename:

- Codex: `codex-speed-ball`
- Claude: `claude-speed-ball`

Mandatory gates for bug fixes and features:

1. Plan review.
2. Detailed subtask review.
3. Implementation review by mutual exhaustion.

Reviewer posture:

- Read the Review Seed first.
- Inspect code/diff before the narrative.
- Run pattern-class scans.
- Include `docs/REVIEW_CHECKLIST.md`.
- End with exactly one verdict line.
