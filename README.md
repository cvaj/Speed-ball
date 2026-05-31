# Speed-ball

A native Android app that measures **softball exit velocity, launch/flight angle, and
trajectory** from the phone's camera — live (point-from-a-tripod, swing, instant on-screen
report) and from imported recordings (high-accuracy / verification).

Detection is **neon-color centroid tracking**: you tap the ball once to sample its color,
and the app tracks that blob across a short high-speed burst, fits its velocity, and
computes launch angle and a drag-corrected trajectory.

## Status

- **De-risk complete.** A standalone Camera2 high-speed prototype (`prototype/hs-probe/`)
  proved the Galaxy S10+ delivers **true ~242 fps at 1080p to a third-party app**. See
  `docs/HIGH_SPEED_FINDINGS.md`.
- **Architecture decided:** fully native Android (Kotlin + Jetpack Compose + Camera2
  high-speed + OpenCV). vision-camera/CameraX is out (no high-speed support).
- **Now:** detailed architecture & implementation plan under adversarial review before code.

## Docs

| Doc | What |
|---|---|
| `docs/DEVICE_CAPABILITIES.md` | Verified S10+ camera abilities + S22+ expectations; quality & thermal analysis |
| `docs/HIGH_SPEED_FINDINGS.md` | The de-risk prototype results (measured, reproducible) |
| `docs/ARCHITECTURE_AND_IMPLEMENTATION_PLAN.md` | The detailed architecture + phased implementation plan (the review target) |
| `docs/CODEBASE_SOURCE_OF_TRUTH.md` | Start here before planning or code changes |
| `docs/HOW_THE_APPLICATION_WORKS.md` | Plain-language measurement and fail-loud behavior |
| `docs/FUNCTIONAL_TEST_REGISTRY.md` | Required functional proof for each capability |

## Engineering protocol

This repo uses an adversarial inter-agent review model:

- `CLAUDE.md` and `AGENTS.md` define the mandatory agent rules.
- `.interagent/` contains the Claude/Codex mailbox + tmux doorbell protocol.
- Every feature/bug fix requires plan review, detailed subtask review, implementation review,
  robust tests, domain docs, and public API documentation where applicable.
- `docs/REVIEW_CHECKLIST.md` and `docs/SECURITY_CHECKLIST.md` are mandatory review aids.

## Target devices

- Samsung Galaxy **S10+** (SM-G975U, Exynos 9820) — primary, verified
- Samsung Galaxy **S22+** (SM-S906, Snapdragon 8 Gen 1 / Exynos 2200) — secondary, to verify

## Repo layout (planned)

```
app/            native Android app (Kotlin, Compose)
core/           pure-Kotlin math (velocity fit, calibration, units, trajectory) + JVM tests
prototype/      proven Camera2 high-speed probe (reference)
docs/           capability docs + architecture/implementation plan
```
