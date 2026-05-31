# Inter-Agent Test Registry

> **Purpose:** This registry tracks protocol and automation behavior for the Claude/Codex interagent system only.
>
> It is intentionally separate from `docs/FUNCTIONAL_TEST_REGISTRY.md`, which is reserved for application capabilities.
>
> If a behavior belongs to tmux session lifecycle, mailbox routing, broker state, alias-path collapse, or protocol hygiene, it belongs here rather than in the application registry.

## Current Coverage

| # | Capability | Source | Test File | Status |
| --- | --- | --- | --- | --- |
| IA-001 | **Codex doorbell-only startup:** starting Codex must bootstrap a fresh primary session into doorbell mode and must not launch watcher/supervisor sessions | `.interagent/start_codex.sh`, `.interagent/prime_codex.sh`, `.interagent/broker.mjs` | manual script smoke + reference scan | **PENDING** |
| IA-002 | **Inter-agent canonical repo paths:** any symlink alias of the same repo must resolve to the one canonical protocol root, so broker archiving and wait helpers accept alias mailbox paths and archive against the real repo state | `.interagent/broker.mjs`, `.interagent/wait_for_codex.sh`, `.interagent/wait_for_claude.sh`, `.interagent/send_to_codex.sh`, `.interagent/send_to_claude.sh`, `.interagent/start_codex.sh`, `.interagent/start_claude.sh` | `test/scripts/interagent-path-canonicalization.spec.ts` | **PASS** |
| IA-003 | **Claude doorbell-only startup:** starting Claude must bootstrap a fresh primary session into doorbell mode and must not launch watcher/supervisor sessions | `.interagent/start_claude.sh`, `.interagent/prime_claude.sh`, `.interagent/send_to_claude.sh`, `.interagent/stop_claude.sh`, `.interagent/normalize_claude_session.sh`, `.interagent/broker.mjs` | manual script smoke + reference scan | **PENDING** |
| IA-004 | **Generic tracked-task enforcement:** every tracked task must carry one valid workstream id, task kind, next step, tracking timestamp, and active progress log; mailbox request/response archiving must fail closed on stale tracked state or stale progress logs; queued review traffic must not overwrite an active review; and role transitions/ready-task selection must stay inside the active workstream until `track-task` changes it explicitly | `.interagent/broker.mjs`, `.interagent/prime_codex.sh`, `.interagent/docs/INTERAGENT_PROTOCOL.md` | broker command smoke + reference scan | **PASS** |
| IA-005 | **Mailbox pointer hygiene:** send helpers must refuse to auto-archive unread or mismatched response pointers under the current task, so an old response cannot be silently reclassified as part of a new tracked-task round | `.interagent/send_to_codex.sh`, `.interagent/send_to_claude.sh`, `.interagent/docs/INTERAGENT_PROTOCOL.md` | `test/scripts/interagent-mailbox-hygiene.spec.ts` | **PASS** |
| IA-006 | **Atomic review closeout:** once mutual exhaustion is reached, one broker command must be able to verify the final approvals, mark the task done, clear the in-flight review latch, and return session state to idle-ready without manual bookkeeping | `.interagent/broker.mjs`, `.interagent/docs/INTERAGENT_PROTOCOL.md` | `test/scripts/interagent-broker-role-switching.spec.ts` | **PASS** |
