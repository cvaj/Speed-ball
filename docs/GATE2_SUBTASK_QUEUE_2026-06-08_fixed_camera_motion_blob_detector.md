# Gate 2 Subtask Queue: Fixed-Camera Motion Blob Detector

Date: 2026-06-08
Workstream: `fixed-camera-motion-blob-detector`
Gate 1 plan: `docs/IMPL_PLAN_2026-06-08_fixed_camera_motion_blob_detector.md`

## Scope

This queue replaces the recorded-HFR color-first detector path after the bounded
impact window has already been decoded. It does not change speech recognition,
impact-audio triggering, HFR recording, window selection, decode bounds, proof
retention, or Camera2 session ownership.

The new production detector starts from fixed-camera motion:

```text
background = median(luma of all bounded-window frames)
foreground(frame) = abs(luma(frame) - background) >= threshold
```

Frame-to-frame differencing is not a detector primitive. Color is optional
ranking/isolation evidence after motion is found; it is not a readiness gate for
recorded-HFR Run Mode.

## Carry-Forward Findings From Gate 1

Claude approved Gate 1 with these Gate 2 obligations:

- C1: pin the actual ball-isolation algorithm, parent-area thresholds, and
  tests proving a frame-spanning mass no-reads while a separated ball yields one
  track.
- C2: make the speed band scale-aware. A hard hit-speed reject applies only to
  trustworthy same-plane calibration. Depth-corrected scale can warn/rank but
  must not hard-reject solely on the hit floor.
- C3: use a clean positive fixture where the ball is physically separated from
  the thrower. The existing tennis window remains a `BALL_NOT_ISOLATED` or
  `AMBIGUOUS_TRACK` regression.
- C4: do not claim production accuracy until a radar/known-toss ground-truth
  pass exists.

## Chosen Detector Algorithm

### Subtask 1 - Motion Detector Domain Types

Add pure Kotlin detector types under `app/src/main/java/com/speedball/app/measurement/`:

- `RecordedHfrMotionBallDetector`
- `RecordedHfrMotionDetectorConfig`
- `RecordedHfrMotionDetectionOutcome`
- `RecordedHfrMotionDetectorReason`
- `MotionForegroundFrame`
- `MotionBallCandidateMetrics`

Add no-read reasons to `VisualEstimateNoReadReason`:

- `NO_FOREGROUND_MOTION`
- `FOREGROUND_AMBIGUOUS`
- `BALL_NOT_ISOLATED`
- `GLOBAL_CAMERA_MOTION`
- `GLOBAL_LIGHTING_CHANGE`

Acceptance:

- invalid detector config returns `RESOURCE_LIMIT_EXCEEDED`;
- new no-read reasons map to actionable UI labels in
  `MeasurementResultUiState`;
- KDoc explains that motion foreground is not automatically a ball.

Tests:

- enum/UI mapping test for every new reason;
- invalid config error-path test.

### Subtask 2 - Bounded Window Retention For Median Background

Wire a new optional `motionDetectorConfig` into
`RecordedHfrStreamingEstimateConfig`.

When `motionDetectorConfig != null`:

- retain only the bounded decoded window frames already selected by the sound
  trigger;
- enforce existing `maxScannedFrames`;
- cap retained ARGB frames to the same bounded window, then release them after
  motion candidates are emitted;
- do not fall back to color-first `BlobDetector`;
- fail loud on configuration conflict if both `motionDetectorConfig` and
  `physicalDetectorConfig` are supplied. Silent precedence would hide a wiring
  bug.

Acceptance:

- no whole-clip decode or full-stream retention is introduced;
- the memory envelope remains bounded by `maxScannedFrames * 640 * 360`;
- cancellation before and after frame retention returns
  `RESOURCE_LIMIT_EXCEEDED`;
- proof thumbnails still come from the same decoded frames and survive success
  and no-read.

Tests:

- streaming test proves motion detector scans the bounded window and closes the
  source;
- conflict/invalid config fails loud;
- cancellation releases/returns no-read;
- source-validity black-window proof still runs before insufficient detections.

### Subtask 3 - Median Background And Foreground Masks

Implement luma-only median background and per-frame foreground masks.

Initial numeric gates:

- `minWindowFramesForMedian = 7`
- `minUsableDetections = 4`
- `lumaDifferenceThreshold = 18`
- `minForegroundAreaPx = 20`
- `maxForegroundAreaFractionPerFrame = 0.45`
- `maxMedianForegroundAreaFraction = 0.35`
- `openRadiusPx = 1` (`3x3` erosion/dilation)
- `closeRadiusPx = 2` (`5x5` dilation/erosion)
- no broad dilation beyond the close pass

The detector must operate at the already-selected working resolution, normally
`640x360`.

Acceptance:

- static same-color scene yields `NO_FOREGROUND_MOTION`;
- global foreground excess yields `GLOBAL_LIGHTING_CHANGE` or
  `FOREGROUND_AMBIGUOUS`;
- all morphology is deterministic and bounded by frame dimensions and operation
  caps.

Tests:

- compact moving disk succeeds as foreground;
- color-changing disk succeeds because detection uses luma/motion first;
- static same-color background is ignored;
- full-frame lighting jump no-reads;
- operation/candidate caps fail loud.

### Subtask 4 - Ball Isolation From Foreground

The first production isolation algorithm is conservative connected components
only. It must be genuinely color-independent.

Direct foreground component candidate rules:

- component area must be in `[20 px, 0.025 * frameArea]`;
- component bounding-box long side must be `<= 0.22 * max(frameWidth, frameHeight)`;
- component bounding-box short side must be `<= 0.20 * min(frameWidth, frameHeight)`;
- component short side must be at least `4 px`;
- component compactness must be at least `0.20`;
- principal-axis ratio may be up to `8.0` to allow motion blur capsules;
- parent area ratio is `1.0` for direct components.

Oversized parent rules:

- any component above the area or bbox caps is not a ball candidate;
- oversized parents are never rescued in this slice;
- selected ball color may be recorded as diagnostics/ranking evidence for
  already-isolated direct components, but it cannot carve a ball candidate out
  of an oversized parent;
- a frame with only oversized parents records `BALL_NOT_ISOLATED`.

This queue deliberately does not rely on leading-edge extraction as the first
implementation because Gate 1 identified the track/edge chicken-and-egg risk.
Leading-edge, watershed, or selected-color core rescue can be later reviewed
optimizations after this conservative direct-component path is proven with clean
positive and ambiguity fixtures.

Acceptance:

- a frame-spanning body/arm/hand mass does not become a mass-centroid ball;
- a separated ball component yields one candidate per usable frame;
- selected color cannot rescue an oversized parent in this slice;
- absent color never blocks separated motion-ball detection;
- the component envelope is a coarse not-giant-mass pre-filter, not the only
  ball discriminator. Shape, size consistency, smooth path, residual, and
  same-plane speed gates remain required before mph can display.

Tests:

- saved tennis mass fixture or synthetic equivalent yields
  `BALL_NOT_ISOLATED`, not mph;
- separated ball plus hand/bat mass selects the separated ball;
- oversized parent with no separated direct component no-reads even when a color
  sample is configured;
- medium non-ball mover regression: a smooth straight non-ball object around
  `100 px` long at `640x360` must no-read through shape/size consistency or
  detector-stage caps rather than produce mph.

### Subtask 5 - Track Selection And Physics Discriminators

Feed only isolated motion-ball candidates into `VisualEstimateCandidateReducer`.

Keep RANSAC/straight-window selection but add detector metadata validation:

- selected path must have at least `4` samples;
- selected samples must span at most `12` frames and normally use `4..10`
  detections;
- normalized line residual must stay at or below `0.06`;
- slope-turn cost must stay at or below `0.60`;
- adjacent centroid step ratio must stay in `[0.35, 2.85]`, excluding
  edge-touching entry/exit samples;
- candidate area ratio across the selected interior track must stay `<= 3.0`;
- short-side ratio across the selected interior track must stay `<= 2.5`;
- selected candidates from oversized parents are impossible in this slice;
- selected track with frame-level `FOREGROUND_AMBIGUOUS` or
  `GLOBAL_CAMERA_MOTION` evidence fails loud.

Scale-independent reducer pixel gates remain only preselection heuristics. The
user-facing hit-speed floor is applied later in mph after calibration.

Tests:

- smooth straight high-speed candidate track succeeds through reducer;
- sawtooth/jagged path no-reads;
- area/shape collapse no-reads except edge-touching entry/exit;
- two plausible paths choose the smoother faster ball track;
- over-budget RANSAC/candidate inputs fail before unbounded work.

### Subtask 6 - Scale Basis And Depth Correction

Add scale mode support in `VisualEstimatePipelineConfig`:

```kotlin
sealed interface VisualEstimateScaleMode {
    data object SamePlane : VisualEstimateScaleMode
    data class DepthCorrected(
        val ballPlaneDepthFeet: Double,
        val calibrationPlaneDepthFeet: Double,
    ) : VisualEstimateScaleMode
}
```

Same-plane mode:

- uses existing `MeasurementCalibrationState.pixelsPerFoot()`;
- basis remains `DISTANCE_CALIBRATION`;
- eligible for hard minimum hit-speed reject.

Depth-corrected mode:

```text
correctedPixelsPerFoot =
  calibratedPixelsPerFoot * (calibrationPlaneDepthFeet / ballPlaneDepthFeet)
```

Guards:

- both depths must be finite and positive;
- correction factor must be finite and in `[0.10, 10.0]`;
- factors outside `[0.50, 2.0]` are allowed but add a strong LOW-confidence
  warning;
- basis becomes `DEPTH_CORRECTED_DISTANCE_CALIBRATION`;
- confidence is capped at `LOW`;
- diagnostics include fronto-parallel and unmeasured toward/away assumptions;
- depth-corrected mode must not hard-reject only because mph is below the
  same-plane hit floor.

Tests:

- same-plane ratio is `1.0`;
- ball depth `1 ft`, calibration depth `7 ft` multiplies pixels-per-foot by
  `7.0` and mph by `1/7`;
- offset helper computes calibration depth correctly if UI uses offset mode;
- invalid/non-finite/zero depths fail `BAD_CALIBRATION`;
- out-of-range correction factor fails `BAD_CALIBRATION`;
- depth-corrected success is LOW confidence with assumptions/warnings;
- `minEstimateMilesPerHour = 35.0` hard-rejects a same-plane 15 mph track but
  does not hard-reject the same samples solely under depth-corrected mode.

### Subtask 7 - Scale-Aware Speed Band

Replace the current unconditional `minEstimateMilesPerHour` behavior with:

```kotlin
enum class MinimumSpeedGatePolicy {
    SAME_PLANE_ONLY,
    ALL_TRUSTED_SCALE,
    DISABLED,
}
```

Initial app policy:

- recorded-HFR motion detector uses `minEstimateMilesPerHour = 35.0`;
- policy is `SAME_PLANE_ONLY`;
- old direct/import estimate paths keep their current behavior unless explicitly
  routed through the new policy.

Acceptance:

- same-plane slow foreground no-reads as `AMBIGUOUS_TRACK` with a clear
  below-hit-floor message;
- depth-corrected slow foreground may be LOW confidence or no-read for other
  gates, but not because the same-plane hit floor was blindly applied;
- diagnostics/warnings disclose whether the speed floor was enforced or skipped.

Tests:

- same-plane 15 mph track with 35 mph floor no-reads;
- same samples under depth-corrected scale skip the hard speed floor and carry a
  warning;
- invalid speed-floor config still no-reads as non-finite config.

### Subtask 8 - UI And Run-Mode Readiness

Update Setup/Run mode behavior:

- Run Mode recorded-HFR readiness requires camera permission, HFR availability,
  valid distance calibration, and valid depth fields only when depth-corrected
  mode is enabled;
- Run Mode recorded-HFR readiness does not require color or ROI;
- Color picker remains visible as an optional discriminator;
- ROI is removed from the recorded-HFR motion detector gate. If retained in UI
  for legacy/import/direct paths, label it as not required for recorded-HFR
  motion detection.

Specific current seams that must be changed:

- `MainActivity.kt:650-668` `shootNotReadyStatus()` must stop calling
  `phase14ColorNotReadyStatus()` and
  `buildImportColor(..., requireRegionOfInterest = false).readiness(...)` for
  the recorded-HFR motion path;
- `MainActivity.kt:3315-3348` `buildRecordedEstimateConfig()` must not return
  `null` solely because no color sample exists when the recorded-HFR motion
  detector is active;
- `MainActivity.kt:3432-3437` `armDirectVisualEstimate()` /
  `phase14WorkflowState.canArm()` must be audited so Run Mode arming is not
  falsely blocked by the legacy color workflow;
- any retained color builder call must be optional diagnostics/ranking input for
  the motion detector, not a readiness requirement.

Add setup inputs:

- scale mode: Same plane / Depth-corrected estimate;
- ball-plane distance from camera in feet;
- calibration-plane distance from camera in feet, or offset behind/ahead if the
  existing UI can support it cleanly;
- preserve text-entry behavior: user text is stored as text and parsed only when
  applying/arming.

Acceptance:

- changing distance text overwrites current text and does not force decimal
  formatting while the user is editing;
- same-plane Run Mode ignores stale depth text;
- depth-corrected Run Mode blocks invalid depth text before capture;
- color can be absent while Run Mode still arms.

Tests:

- `CalibrationUiSourceTest` updates for no color/ROI recorded-HFR gate;
- distance text overwrite/source tests remain green;
- depth mode source tests for labels and parse guards.
- source test asserts Run Mode can arm with distance/level setup and no color
  sample for recorded-HFR motion mode.

### Subtask 9 - Reports, Proof, And Diagnostics

Report and logs must show:

- detector mode: `median-background-motion`;
- foreground frame count;
- isolated candidate frame count;
- `BALL_NOT_ISOLATED`/global-motion/global-lighting stage reason when relevant;
- selected sample count and residual;
- scale basis and confidence;
- depth inputs and correction factor when used;
- proof thumbnails with the selected motion-ball candidate overlay where a
  selected track exists, and source thumbnails on no-read.

Acceptance:

- no-read UI does not show stale mph;
- clearing a result keeps it cleared until a new capture attempt finishes;
- proof imagery exists for success and no-read source/detector failures.

Tests:

- measurement-result UI strings for new diagnostics;
- source-string or focused UI tests for stale result suppression if touched;
- proof-thumbnail selection alignment remains green.

### Subtask 10 - Saved Fixture And Device Proof

Saved/local fixtures:

- `TESTBALL.mp4`: extract frames and verify a compact moving-object track is
  detected. Do not assert mph because export timing is ambiguous.
- `.interagent/tmp/field-proof-recovery/extracted-1780862777355/impact-window-frames`:
  production detector must not emit mph from the giant mass. Expected result is
  `BALL_NOT_ISOLATED` because the first implementation does not rescue
  oversized parents.

Device proof after implementation:

- install debug APK on S10+;
- Setup Mode preview works for distance/scale/color setup;
- Run Mode arms without requiring color or ROI;
- say `shoot`, wait for Ready, create a loud pop marker, and move the ball
  through frame;
- logs show recorded-HFR bounded window, motion detector summary, terminal
  success or actionable no-read;
- no whole-clip decode, no heap crash, no silent hang.

Acceptance:

- proof artifacts are stored under `.interagent/tmp/`;
- docs distinguish JVM/saved-frame proof from physical ground-truth accuracy;
- C4 remains open until radar/known-toss evidence exists.

## Implementation Order

1. Close Gate 1 with reciprocal `APPROVED`.
2. Send this Gate 2 queue for adversarial review.
3. After Gate 2 mutual approval, implement subtasks 1 through 10 in order.
4. After each risky cluster, run focused tests and update
   `.interagent/progress/fixed-camera-motion-blob-detector.md`.
5. Send implementation review only after code, tests, docs, saved fixtures, and
   feasible device proof are complete.

## Required Docs And Checks

Update with implementation:

- `docs/HOW_THE_APPLICATION_WORKS.md`
- `docs/DATA_FLOW.md`
- `docs/FUNCTIONAL_TEST_REGISTRY.md`
- `docs/SECURITY_CHECKLIST.md`
- KDoc for new public detector/scale APIs

Minimum verification before implementation review:

- focused detector tests;
- focused streaming/pipeline tests;
- focused UI source tests;
- saved fixture runner/proof;
- `docs:check`;
- `security-check`;
- `git diff --check`;
- device install/proof if ADB is available.
