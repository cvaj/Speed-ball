# Product Completion Phases - 2026-06-01

## Current State

Speed-ball is not product-complete.

The implemented app has the pure measurement math, trajectory physics, Camera2
high-speed capture diagnostics, decode and timestamp investigation paths,
direct-source proof contracts, the first workflow/result-state foundation, and
the S10+ personal visual-estimate workflow foundation.

The strict production measurement path still must fail loud because no S10+
source has proven a measurement-ready frame/timestamp sequence. The S10+
personal estimate path is separate: it can show labeled estimate results only
from reviewed estimate timing bases, with confidence and assumption disclosure.

Earlier S10+ Phase 11/12 source proof showed:

- sensor callbacks arrive at the requested 120 fps cadence;
- direct `SurfaceTexture`/GL readbacks are real same-update frames, but arrive
  at about 30 fps in the current companion session shape;
- only 6 direct frames were consumed where the proof contract requires at least
  12 whole-stream-clean direct frames;
- readback itself is not the dominant cost;
- scratch cleanup succeeds;
- no strict timing token, strict mph, launch angle, trajectory, or strict
  production result is allowed from those no-go source routes.

Phase 13 and Phase 14 then added the separate personal-estimate path and usable
setup/capture workflow. Phase 15 added estimate-only import, saved summaries,
and redacted export evidence. A real ball-in-frame S10+ estimate proof remains
a physical-input validation gate for Phase 16 hardening.

## Remaining Phase Count

There is **1 remaining product-completion phase** for v1:

1. Phase 16 - production hardening, device matrix, and release readiness.

This is the active completion list. Empty broker state does not mean empty
product work; it only means the next phase must be explicitly planned and sent
through the required review gates.

## Phase 12 - Measurement Source Unblock

### Goal

Produce a real, measurement-ready source path, or an explicit source-route no-go
decision that is backed by a complete variant matrix and forces a supported
next route before later production work.

The phase is complete only when at least one real source can emit an inseparable
frame/timestamp/proof input that is eligible for the measurement pipeline, or
when the documented result proves the current target hardware cannot support v1
live measurement without a different source strategy.

### Scope

- Re-test direct `SurfaceTexture`/GL consumption strategies and at least one
  consumer-buffering strategy that could remove the 30 fps consumed-cadence cap.
- Keep current record/decode reconciliation no-read on S10+
  near-duplicate/count-mismatch evidence, but keep that evidence in the no-go
  decision tree rather than silently treating all decoder routes as impossible.
- Keep the direct proof contract whole-stream strict; do not filter a degraded
  stream into a passing subset.
- Preserve `SENSOR_TIMESTAMP` as the timing authority when available.
- Keep all encoded companion files app-private, bounded, deleted, and never read
  as measurement inputs.

### Candidate Subtasks

1. Audit the current direct capture loop for avoidable callback serialization,
   queue starvation, frame listener thread mistakes, and teardown timing that
   could throttle consumed frames.
2. Add isolated proof variants for session surface shape, target ordering,
   consumer buffering, and consumption: preview-only direct proof, companion plus
   direct proof, constrained high-speed `ImageReader` acceptance/rejection
   evidence, standard Camera2 `ImageReader` or decoupled GL/readback probe if
   constrained `ImageReader` is rejected, direct target size variants, and any
   supported Camera2 high-speed target ordering variants.
3. Add a direct-cadence comparison matrix so device proof records requested fps,
   sensor callback cadence, direct consumed cadence, frame callback count,
   capture callback count, readback latency, release timings, and final gate.
4. Turn the `captureCallbacks : frameAvailableCallbacks :
   consumedDirectFrames` ratio into a decision rule for the next remedy.
5. If the current S10+ path remains capped at about 30 fps, test an alternate
   verified device or supported source route instead of weakening proof rules.
6. Update device evidence docs with only measured evidence, including
   `DEVICE_CAPABILITIES.md` no-go rows when applicable.

### Proof Gate

- JVM tests cover each new failure reason, variant selection rule, and no-token
  branch.
- Device proof records the exact source variant and final gate.
- A no-go requires consumer-buffering evidence, decode-route evidence, and a
  per-variant callback-ratio matrix.
- Success requires at least 12 whole-stream-clean direct frames with timestamp,
  cadence, membership, and pixel-signature proof.
- Failure remains a typed no-read with no path, raw pixels, mph, angle, or
  trajectory values.

## Phase 13 - Live Measurement Integration

### Goal

Connect the verified real source from Phase 12 to the existing Phase 8/10
measurement pipeline.

### Scope

- Convert proven real frames into `TimedFrameSequence` data only through the
  vetted bound-source input.
- Run HSV/blob detection on real captured frames.
- Preserve source timestamps through detection, flight-window selection,
  velocity fit, and trajectory projection.
- Surface only `MeasurementRunOutcome.Success` values, never diagnostic guesses.

### Candidate Subtasks

1. Build the production source adapter from the Phase 12 proof output to the
   measurement pipeline.
2. Add real-frame detector integration around the existing HSV/ROI/calibration
   state.
3. Add fail-loud integration tests for missing proof, stale proof, bad sequence
   identity, bad calibration, insufficient detections, excessive residual, and
   resource caps.
4. Add a device proof that a real capture can either produce a validated result
   from controlled fixture motion or fail loud with the exact expected reason.
5. Update `HOW_THE_APPLICATION_WORKS.md`, `DATA_FLOW.md`, and
   `FUNCTIONAL_TEST_REGISTRY.md`.

### Proof Gate

- No generic timing proof path can be used from production.
- A speed result can appear only from the verified source path.
- Bad evidence always produces no-read.

## Phase 14 - User Calibration, Color, Capture, and Result Workflow

### Goal

Turn the diagnostic-first app shell into the usable live capture workflow for a
person at a field.

### Scope

- Camera preview with calibration and color sampling interactions.
- ROI/tolerance controls and mask/track preview.
- Arm, capture, process, result, retry, and recalibrate flow.
- Actionable no-read copy for source, calibration, detection, timestamp, and
  fit failures.

### Candidate Subtasks

1. Build the Compose preview workflow around camera permission, mode selection,
   calibration, color sample, and capture controls.
2. Implement tap-to-sample color and calibration-point selection against a
   frozen or live preview frame.
3. Show bounded detector/track overlay diagnostics without exposing raw media
   paths or misleading speed values.
4. Add Compose-free reducer tests for every state transition and
   instrumentation/device checks for permission and lifecycle behavior.
5. Update user-facing workflow docs and functional-test registry rows.

### Proof Gate

- The app can be used without developer adb extras for the live path.
- UI never displays a speed, angle, confidence, trajectory, carry, apex, or hang
  time unless the measurement pipeline returned success.
- Lifecycle and permission failures release camera resources and remain no-read.

## Phase 15 - Import, Saved Results, and Exportable Evidence

### Goal

Complete the secondary import workflow and make validated results useful after
capture.

### Scope

- Import a user-selected video through Android storage APIs.
- Decode imported frames with bounded work and validated metadata.
- Run the same detector/math pipeline with explicit timestamp provenance.
- Save validated measurement results and export/share a small evidence summary.

### Candidate Subtasks

1. Implement secure video import URI handling and metadata validation.
2. Add timestamp-source handling for imported clips, including fail-loud
   rejection when the clip cannot provide trustworthy timing.
3. Reuse calibration/color/detection/measurement code instead of creating an
   import-only math path.
4. Add local result history with no raw video persistence unless explicitly
   designed and reviewed.
5. Add export/share for summary evidence, not private raw media by default.

### Proof Gate

- Imported clips cannot synthesize timing authority from unknown frame rates.
- Import failures are no-read with actionable reasons.
- Result storage/export contains no secrets, private media paths, or raw pixels.

## Phase 16 - Production Hardening, Device Matrix, and Release Readiness

### Goal

Make the completed v1 behavior stable enough to ship or hand to testers.

### Scope

- Full local check suite, docs checks, security checks, and device proof.
- Device matrix for supported and unsupported modes.
- Thermal/resource bounds for repeated captures.
- Release surfaces and handoff docs.

### Candidate Subtasks

1. Run and fix the full local suite: unit tests, Android unit tests, assemble,
   lint/check scripts, docs checks, and security checks.
2. Run device proof across the available paired phones and record exact
   supported modes, source verdicts, measurement verdicts, and failure reasons.
3. Harden camera lifecycle, resource release, repeated captures, cache cleanup,
   and no-read recovery.
4. Reconcile all docs: architecture, app behavior, data flow, capabilities,
   findings, functional registry, and security checklist.
5. Prepare release notes or tester handoff with exact supported workflows and
   known unsupported hardware paths.

### Proof Gate

- Clean worktree except intentional release artifacts.
- All applicable checks pass or have documented device-only limitations.
- Device evidence backs every claimed live mode.
- The app has no known path to display plausible wrong mph.

## Immediate Next Step

Start Phase 16 Gate 1 planning:

1. Write
   `docs/IMPL_PLAN_2026-06-01_phase-16_production_hardening_device_matrix_release_readiness.md`.
2. Send it for adversarial plan review.
3. After Gate 1 convergence, write the detailed Phase 16 subtask queue.
4. Send Gate 2 subtask review.
5. Implement Phase 16 only after both gates converge.
