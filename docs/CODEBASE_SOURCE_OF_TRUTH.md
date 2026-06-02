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
- Implemented modules:
  - `:core` pure Kotlin/JVM model, calibration, units, velocity fit, and
    fail-loud measurement outcome logic, plus trajectory physics.
  - `:app` Android Compose shell plus Camera2 capture diagnostics, Phase 5/6
    decode and timestamp investigation, Phase 7 preview timestamp proof, Phase
    9/11 direct-source proof, Phase 10 workflow/result-state foundation, and
    Phase 13 visual-estimate contracts/pipeline/UI plus direct live ARGB
  readback adapter foundation, Phase 15 user-selected import estimate,
    saved summary, and redacted export evidence foundation, and Phase 16 live
    Camera2 calibration feed controls plus S10+ safety/labeled-estimate smoke.
    OpenCV production integration, S10+ ground-truth real-ball accuracy proof,
    and certified production measurement results remain planned.
- `docs/ARCHITECTURE_AND_IMPLEMENTATION_PLAN.md` is the current implementation plan under review.

## Non-Negotiable Technical Facts

- S10+ third-party Camera2 high-speed capture is verified at true ~242 fps.
- S10+ MediaRecorder persistence caps around 120 fps for 240 requests.
- CameraX/vision-camera cannot be used for live high-speed capture.
- 120 fps record-then-decode is the first working recording path on S10+, but
  Phase 5/6/12 evidence makes the measured `1280x720 @ 120` S10+
  record-then-decode route a scoped no-go for measurement because decoded
  frames cannot be paired to surviving `SENSOR_TIMESTAMP` values safely; the
  measured count-independent value-match is `AMBIGUOUS_MATCH` with
  `matched=248/257`, `ambiguous=62`, and `longestCleanRun=3`.
- 240 fps on S10+ needs a GPU/preview path because MediaRecorder persistence
  drops to about 120 fps.
- S10+ visual estimate mode is a separate personal-estimate path, not strict
  proof-token measurement. It uses app-owned ordered frames and real per-frame
  timestamps when present; when timestamps are unavailable or unusable it may
  use visual centroid displacement plus a known per-frame interval as an
  estimate-only timing basis. When both signals exist, centroid displacement
  validates skipped/coalesced intervals. Confidence/no-read gates must disclose
  the timing basis, scale basis, calibrated-plane assumption, and the visual
  frame-delta absolute-scale assumption that uniformly dropped frames would bias
  speed high. Estimate mode may self-calibrate from known ball diameter only
  when distance calibration is absent and apparent short-axis ball diameter is
  detected in enough frames; that fallback must disclose the entered-ball-type
  and motion-blur scale assumptions. The S10+ decoder route remains closed for
  this mode.
- Phase 12 source no-go decisions require measured variant evidence, including
  consumer-buffering evidence; do not infer hardware no-go from the current
  single-`SurfaceTexture` consumer alone.
