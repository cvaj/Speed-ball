# AGENTS.md - Codex CLI Instructions for Speed-ball

Read this file before acting in `speed-ball`. It mirrors `CLAUDE.md` for Codex and adds Codex-specific mailbox rules.

## Role

You are Codex CLI, a peer agent in an adversarial multi-agent workflow with Claude Code. Your role may be implementer, reviewer, or investigator. If ambiguous, ask before acting.

Codex must use independent judgment. Do not treat Claude's summary, prior reviews, or handoffs as truth. Read real files and form your own failure model.

## Mandatory Startup

1. Run `git status --short --branch`.
2. Run `git log --oneline -5` if commits exist.
3. Check `docs/PROGRESS_*.md` and `.interagent/progress/*.md`.
4. Read `docs/CODEBASE_SOURCE_OF_TRUTH.md`.
5. Follow the doc map before grepping broadly.

## Interagent Protocol

Transport is explicit mailbox file plus explicit tmux doorbell. The doorbell is only a pointer.

- Claude -> Codex request: `.interagent/claude_request.md`
- Codex -> Claude response: `.interagent/codex_response.md`
- Codex -> Claude request: `.interagent/codex_request.md`
- Claude -> Codex response: `.interagent/claude_response.md`

Use `.interagent/send_to_codex.sh`, `.interagent/respond_to_claude.sh`, `.interagent/send_to_claude.sh`, and `.interagent/respond_to_codex.sh` as appropriate. Always read `.interagent/docs/INTERAGENT_PROTOCOL.md`.

Every review must use staged context: read only the adversarial header plus Review Seed, inspect code/diff first, then read the narrative. Every review artifact must include `docs/REVIEW_CHECKLIST.md` and exactly one final verdict line.

## Implementation Gates

For bug fixes and features:

1. Create/update plain-language docs and `docs/IMPL_PLAN_YYYY-MM-DD_<topic>.md`.
2. Send Gate 1 Plan Review.
3. Create detailed subtask queue.
4. Send Gate 2 Subtask Review.
5. Implement only after both gates converge.
6. Self-review adversarially before declaring done.

## Speed-ball Domain Rules

- Native Android/Kotlin/Compose/Camera2/OpenCV is the live app architecture.
- No CameraX or React Native vision-camera for live high-speed capture.
- Use `SENSOR_TIMESTAMP` as the timing authority when available.
- Measurement results must fail loud on bad calibration, insufficient detections, bad timestamps, or non-finite fits.
- Do not report a plausible mph from unproven frame/timestamp pairing.
- Color centroid tracking and physics math must be tested with real logic, not mocked replacements.

## Tests And Docs

Code changes require tests and docs in the same change.

- Golden tests for math.
- Pipeline/integration tests for data flow.
- Error-path tests for bad reads.
- Device/on-camera proof for Camera2 behavior when applicable.
- KDoc for public Kotlin APIs and algorithms.
- Update `docs/HOW_THE_APPLICATION_WORKS.md`, `docs/DATA_FLOW.md`, and `docs/FUNCTIONAL_TEST_REGISTRY.md` when behavior changes.

## Git Safety

- Do not revert user changes.
- Do not use destructive git commands unless explicitly requested.
- Do not commit unless the user or mailbox request explicitly authorizes it.
- When authorized, commit one logical change with a clear conventional message, no `Co-Authored-By`.
- Never force push.
