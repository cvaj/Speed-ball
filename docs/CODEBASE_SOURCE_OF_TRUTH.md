# Codebase Source Of Truth

This is the starting point for every Speed-ball task.

## Search Protocol

1. Read this file.
2. Read the relevant domain docs below.
3. Search docs before code.
4. Then inspect source/prototype files.

## Documentation Map

| Task | Read First |
|---|---|
| Architecture or roadmap | `docs/ARCHITECTURE_AND_IMPLEMENTATION_PLAN.md`, `docs/HOW_THE_APPLICATION_WORKS.md` |
| Camera2 high-speed capture | `docs/DEVICE_CAPABILITIES.md`, `docs/HIGH_SPEED_FINDINGS.md`, `prototype/hs-probe/FINDINGS.md` |
| Measurement math | `docs/HOW_THE_APPLICATION_WORKS.md`, `docs/FUNCTIONAL_TEST_REGISTRY.md` |
| Tests | `docs/FUNCTIONAL_TEST_REGISTRY.md`, `docs/REVIEW_CHECKLIST.md` |
| Security/privacy | `docs/SECURITY_CHECKLIST.md`, `CLAUDE.md` |
| Interagent review | `.interagent/docs/INTERAGENT_PROTOCOL.md`, `AGENTS.md`, `CLAUDE.md` |

## Current Architecture

- `prototype/hs-probe/` is the proven Camera2 high-speed reference.
- Planned app modules:
  - `:core` pure Kotlin/JVM math and model logic.
  - `:app` Android app: Camera2, OpenCV, Compose, import, results.
- `docs/ARCHITECTURE_AND_IMPLEMENTATION_PLAN.md` is the current implementation plan under review.

## Non-Negotiable Technical Facts

- S10+ third-party Camera2 high-speed capture is verified at true ~242 fps.
- S10+ MediaRecorder persistence caps around 120 fps for 240 requests.
- CameraX/vision-camera cannot be used for live high-speed capture.
- 120 fps record-then-decode is the first working path.
- 240 fps on S10+ needs a GPU/preview path.
