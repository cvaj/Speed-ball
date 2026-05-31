# CLAUDE.md - Speed-ball Development Rules

This file is mandatory operating context for Claude Code in `speed-ball`. Read it before making changes. If this file conflicts with `AGENTS.md`, follow the stricter rule and flag the divergence.

## Product Contract

Speed-ball is a native Android softball exit-velocity app. It measures exit velocity, launch/flight angle, and drag-corrected trajectory from high-speed phone camera footage.

Non-negotiable technical decisions:

- Native Android first: Kotlin, Jetpack Compose, Camera2 constrained high-speed, OpenCV.
- No CameraX or React Native vision-camera for live high-speed capture.
- S10+ 120 fps path is record-then-decode; S10+ 240 fps requires GPU/preview frame access because MediaRecorder drops to roughly 120 fps.
- Frame timing must come from real `SENSOR_TIMESTAMP` values where available.
- Detection is neon-color centroid tracking, not a neural network.
- Measurements must fail loud. If calibration, timestamps, detections, or fits are invalid, show "No read" instead of a plausible wrong speed.

## Startup Sequence

Every session:

1. Run `git status --short --branch`.
2. Run `git log --oneline -5` if commits exist.
3. Check for active `docs/PROGRESS_*.md` and `.interagent/progress/*.md` files relevant to current work.
4. Read `docs/CODEBASE_SOURCE_OF_TRUTH.md`.
5. Read the domain docs named by that source-of-truth file before editing code.

## Interagent Review Is Mandatory

All bug fixes and new features must use the adversarial interagent protocol in `.interagent/docs/INTERAGENT_PROTOCOL.md`.

Implementation work has two gates before code:

1. Gate 1: plain-language explanation plus `docs/IMPL_PLAN_YYYY-MM-DD_<topic>.md`.
2. Gate 2: detailed subtask queue with proof obligations and test/doc requirements.

Only after both gates are approved by mutual exhaustion may implementation begin. Every implementation review response must include `docs/REVIEW_CHECKLIST.md`.

## Testing Philosophy

Tests prove behavior, not implementation guesses.

Required test priorities:

1. Golden-value tests for pure math: velocity fit, outlier rejection, calibration, units, and trajectory.
2. Invariant tests: speed is rotation-invariant in image pixels; drag range is less than no-drag range; no-drag trajectory matches closed form.
3. Pipeline tests with real data: frames or fixture detections -> centroid/detection -> fit -> mph/angle/result.
4. Error-path tests: fewer than three detections, bad timestamps, invalid calibration scale, non-finite math, missing frame/timestamp pairing.
5. UI/device tests where behavior depends on Android surfaces, Camera2, permissions, lifecycle, or Compose state.

Do not mock application logic. Math, detection, calibration, frame/timestamp reconciliation, and trajectory code must run for real in tests. Infrastructure stubs are acceptable only for Android system boundaries that cannot run on the JVM.

## Measurement Fail-Loud Rules

Never silently continue when a speed result would be wrong.

- Missing or non-monotonic frame timestamps abort the measurement.
- Fewer than three valid detections abort the measurement.
- Invalid `pixelsPerFoot`, non-finite fit values, or excessive residuals abort the measurement.
- Decoded frame count and timestamp count mismatches must be reconciled explicitly. If not proven safe, abort or route to the correct capture path.
- Detection ambiguity, multiple plausible blobs, or out-of-ROI tracks must lower confidence or abort; do not report a clean speed from bad evidence.
- UI must show actionable reasons for "No read".

## Documentation Requirements

Docs travel with code in the same change.

- Update `docs/HOW_THE_APPLICATION_WORKS.md` when user-visible behavior or measurement rules change.
- Update `docs/FUNCTIONAL_TEST_REGISTRY.md` when capabilities or test coverage change.
- Update `docs/DATA_FLOW.md` when frame/capture/detection/calibration/result flow changes.
- Update `docs/DEVICE_CAPABILITIES.md` when device evidence changes.
- Update `docs/HIGH_SPEED_FINDINGS.md` only with measured, reproducible evidence.
- Add KDoc/TSDoc-equivalent comments for public Kotlin APIs and non-obvious algorithms.

## Security And Privacy

Run `docs/SECURITY_CHECKLIST.md` on every changed file before review and before commit.

Special Speed-ball concerns:

- Camera permission and media import are sensitive boundaries.
- Do not log private media paths except in developer-only diagnostics.
- Do not commit videos, APKs, keystores, `.env`, `local.properties`, or credentials.
- Validate imported video URIs and metadata before decoding.
- Bound CPU/GPU processing and burst duration to avoid thermal/resource abuse.

## Git And Commit Directives

- One logical change per commit.
- Never commit with vague messages like `fix`, `update`, `wip`, or `changes`.
- Do not add `Co-Authored-By` lines.
- Do not use `--no-verify` unless the user explicitly instructs it.
- Do not force push.
- Before every commit, run the relevant tests and `pnpm docs:check` / `bash scripts/check.sh` as applicable.
- Commit code with its tests and docs; never code-only for behavior changes.

## Release Directives

Before release/version bumps:

1. Verify interagent review closure by mutual exhaustion.
2. Run the full feasible local check suite and record any device-only checks not run.
3. Update README/help/release notes if user-visible behavior changed.
4. Bump version surfaces consistently.
5. Commit, tag, push, and verify remote refs only when user-authorized.
