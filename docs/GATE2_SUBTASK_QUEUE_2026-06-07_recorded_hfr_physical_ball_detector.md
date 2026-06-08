# Gate 2 Subtask Queue - Recorded-HFR Physical Ball Detector

Date: 2026-06-07
Workstream: `recorded-hfr-physical-ball-detector`
Gate 1 plan: `docs/IMPL_PLAN_2026-06-07_recorded_hfr_physical_ball_detector.md`

## Gate 2 Decision

Implement a recorded-HFR-only opt-in detector stage that turns a bounded
sound-triggered decode window into motion-isolated physical ball candidates.
The stage must not count raw HSV connected components as ball candidates, and
it must not merge the moving ball into static same-color background.

Production usually decodes only the impact window, not a longer pre-impact
context clip. Therefore per-window temporal persistence/activity is the primary
background separator. Any pre-impact context, when available, is a bonus only.

No production code may be written until this Gate 2 queue converges.

## Numeric Detector Contract

The implementation may tune names, but it must preserve these reviewed
semantics and starting thresholds unless code review proves a better value.

### Window And Resource Bounds

- Scope: recorded-HFR streaming estimate only.
- Input size: existing recorded-HFR working frame, normally `640x360`.
- Input count: bounded decoded impact window, normally `<= 32` frames.
- Retained full pixels: none after threshold/proof extraction.
- Retained detector state:
  - one bit-packed threshold mask per decoded frame;
  - one bounded per-pixel threshold-presence counter for the window;
  - compact per-frame component/candidate summaries;
  - bounded proof thumbnails already used by the existing path.
- Pre-merge caps still run before physical-candidate assembly:
  - `FrameProcessingBounds.maxThresholdPixels`;
  - `FrameProcessingBounds.maxComponentsPerFrame`;
  - `FrameProcessingBounds.maxOperationsPerFrame`;
  - decode/candidate cancellation/deadline checks.
- Candidate reducer caps still run after physical-candidate assembly through
  `CandidateReductionBudget`.

### Temporal Background And Motion Isolation

- Production use assumes the phone/camera is stationary on a tripod or stable
  mount during the short recorded-HFR window. Handheld jitter must not silently
  become ball motion.
- Build a threshold mask for every decoded frame with the configured HSV
  threshold and ROI/full-frame fallback.
- Count per-pixel threshold presence across the bounded window.
- Mark a pixel persistent/static when it is thresholded in at least `80%` of
  frames, with a minimum of `4` contributing frames.
- Build a temporal activity mask from threshold pixels that change relative to
  either neighbor frame. Dilate activity by `8 px` before intersecting it with
  the current threshold mask so the moving ball interior is preserved.
- A frame's moving candidate mask is:
  `currentThreshold && dilatedTemporalActivity`, excluding only persistent
  pixels that have no nearby temporal activity.
- If fewer than `4` frames exist, or fewer than `4` frames have moving candidate
  area, fail loud with `INSUFFICIENT_DETECTIONS`.
- If the motion mask removes more than `85%` of threshold pixels in every frame
  that otherwise contains a candidate, fail loud with
  `BACKGROUND_SEPARATION_AMBIGUOUS`.
- If moving candidate area is less than `12%` of threshold area for the best
  candidate frame and no coherent track can be formed, fail loud with
  `BACKGROUND_SEPARATION_AMBIGUOUS`.
- Estimate bounded global motion from the dominant frame-to-frame shift of the
  persistent threshold mask. Search shifts in `[-8 px, +8 px]` for x/y and
  choose the shift that maximizes overlap. If the median absolute dominant
  shift exceeds `1.5 px/frame` or any adjacent shift exceeds `4 px`, fail loud
  with `GLOBAL_MOTION_AMBIGUOUS` before treating shifted background edges as
  candidate motion.

### Fragment Merge Within Motion-Isolated Regions

- Connected components are found on the moving candidate mask, not the whole
  same-color frame mask.
- Components in the same moving region may merge when their bounds are within
  `10 px` after dilation or when they overlap the same dilated activity island.
- Components outside the same moving activity island must not merge.
- More than `3` physical candidates in any frame after merge is
  `AMBIGUOUS_TRACK`, unless the existing reducer selects one coherent path and
  the unused candidates are off-path outliers under the configured caps.
- Excessive unmergeable fragments or operation budget exhaustion is
  `RESOURCE_LIMIT_EXCEEDED`, not a fallback to raw components.

### Per-Frame Shape And Color Gates

Each merged physical candidate emits `Blob` plus physical metrics. The existing
`Blob.compactness` remains the bounding-box fill value for legacy paths only.
Recorded-HFR physical shape gates must not use bbox fill/aspect as primary hard
shape evidence because those metrics change with the candidate's on-screen
angle.

Hard gates:

- visible short side: `>= 6 px`;
- convex-hull solidity (`area / convexHullArea`): `>= 0.55`;
- PCA principal-axis ratio: `<= 8.0`;
- elongated candidate (`principalAxisRatio > 3.0`) must have rounded/capsule
  evidence score `>= 0.35`;
- compact candidate (`principalAxisRatio <= 3.0`) must have circularity or
  roundness score `>= 0.80`;
- color coherence: saturation standard deviation `<= 0.28` and value standard
  deviation `<= 0.32` over threshold pixels.

The color-coherence limits and compact-branch roundness floor are provisional
until S8 field proof. They must be reported in diagnostics and may be tuned only
with measured device evidence.
When a candidate's visible short side is below `6 px`, the detector has
insufficient shape signal; it should fail loud or rely on motion/path
consistency only if a reviewed config explicitly allows that far-ball mode.

Soft/ranking metrics and branch-specific hard floors:

- circularity and ellipse ratio are not global hard rejects, but compact
  candidates must satisfy the roundness floor after also passing solidity, PCA,
  short-side, and color gates. This makes a filled square fail without making
  circularity the only discriminator.
- bbox fill/compactness and bbox aspect remain legacy/soft signals only;
- existing `blobShapePenalty` continues as a selector ranking penalty for
  legacy candidates;
- when recorded-HFR physical detection is enabled, validated physical capsule
  candidates must be penalty-exempt or must feed orientation-invariant physical
  metrics into the selector so the existing bbox compactness/elongation penalty
  cannot down-rank the exact capsule band admitted by the hard gate.

Fail-loud reason codes:

- `NO_MOTION_CANDIDATE`;
- `SHAPE_REJECTED`;
- `COLOR_INCOHERENT`;
- `BACKGROUND_SEPARATION_AMBIGUOUS`;
- `GLOBAL_MOTION_AMBIGUOUS`;
- `RESOURCE_LIMIT_EXCEEDED`.

### Cross-Frame Physical Consistency

The physical-candidate stage feeds the existing
`VisualEstimateCandidateReducer`. It must compose with, not duplicate, the
existing direction, signed-travel, stationary-after-motion, max-jump, RANSAC,
line-residual, speed, and slope-change gates.

After the reducer selects a path, recorded-HFR opt-in consistency validation
must check:

- adjacent candidate area ratio in `[0.45, 2.25]` for candidates that do not
  touch the frame edge;
- whole-track max/min area ratio `<= 3.0` after dropping edge-touching entry or
  exit candidates;
- adjacent short-side ratio in `[0.50, 2.00]` for candidates that do not touch
  the frame edge;
- whole-track max/min short-side ratio `<= 2.5` after dropping edge-touching
  entry or exit candidates;
- PCA principal-axis-ratio swing `<= 3.0`;
- color mean hue distance `<= 18 deg`, saturation difference `<= 0.22`, value
  difference `<= 0.28` between adjacent accepted candidates;
- vertical sawtooth: no more than one alternating-sign vertical step whose
  amplitude exceeds `0.75 * medianShortSidePx`;
- acceleration outlier: no adjacent step-length ratio outside `[0.25, 4.0]`
  unless the line residual and existing speed gates still select a shorter
  coherent sub-window.

If these fail, no mph/angle/carry result may display. The no-read reason is
`AMBIGUOUS_TRACK` with a message naming the failed consistency metric.
Edge-touching candidates may be retained for path selection, but collapse
checks either exempt them or trim them from the final consistency window. The
implementation must choose one behavior and test it; it must not reject a valid
track solely because the first or last visible ball is cropped by the frame
edge.

## Subtasks

### S1 - Models, Config, And Reason Codes

- Add physical-candidate metric models with KDoc:
  - per-frame threshold summary;
  - per-candidate shape/color metrics;
  - convex-hull solidity, PCA principal-axis metrics, and compact-branch
    roundness metrics;
  - temporal separation summary;
  - global-motion summary;
  - fail-loud reason code enum.
- Add a nullable recorded-HFR opt-in config on the streaming estimate path or
  track config. Default `null` must preserve sibling behavior.
- Add validation for every numeric threshold.
- Reconcile new metrics with existing `Blob.compactness` and
  `blobShapePenalty` by making the recorded-HFR physical-candidate selector use
  physical metrics or a validated-capsule penalty exemption. This is mandatory,
  not conditional.

Proof:

- JVM validation tests for invalid numeric config.
- Source/behavior test proving default `null` keeps legacy/direct/import paths
  unchanged.
- Unit test proving a validated horizontal or tilted capsule out-ranks a
  rounder off-path noise blob in the recorded-HFR physical selector.
- Unit test proving a compact filled box fails by the roundness floor while a
  compact filled disk passes.

### S2 - Bounded Threshold-Mask Accumulator

- Replace recorded-HFR per-frame raw `BlobDetector.detectCandidates` use with a
  recorded-HFR opt-in accumulator when physical detection is enabled.
- During streaming decode, build threshold masks and proof thumbnails, then drop
  full ARGB pixels immediately.
- Preserve existing source-validity/black-window checks and scanned-frame
  accounting.
- Keep cancellation/resource checks in the frame loop.

Proof:

- JVM test proves ARGB frames are not retained in candidate records.
- JVM test proves threshold-pixel/component/operation caps fail loud before
  motion merge.
- JVM/source test proves threshold masks are bit-packed and persistence uses a
  bounded per-pixel counter, not retained ARGB frames.
- Source scan proves `RecordedHfrStreamingEstimate` does not call the physical
  path unless the recorded-HFR opt-in config is non-null.

### S3 - Temporal Persistence Primary Background Separator

- Implement per-window persistence/activity masks as the production primary
  separator.
- Treat optional pre-impact/context frames as a future bonus; do not depend on
  them for the production path.
- Add ambiguous-separation metrics:
  - frame count;
  - threshold pixels;
  - persistent pixels;
  - active/moving pixels;
  - removed ratio;
  - dominant global shift;
  - candidate frame count.

Proof:

- Synthetic fixture: large static same-color background around `185k` scaled
  pixels plus moving ball delta. Expected result: moving ball isolated; static
  background is not the candidate centroid.
- Synthetic fixture: temporal-persistence-only production window with no
  pre-impact frames. Expected result: moving ball isolated.
- Negative fixture: slow/large/self-overlapping ball where temporal separation
  is ambiguous. Expected result: no-read reason names ambiguous background
  separation.
- Negative fixture: camera-jitter/static-background edge motion without a
  moving ball. Expected result: no-read reason names global motion/background
  separation, not a fake ball candidate.
- Negative fixture: single-frame jolt of a static same-color background.
  Expected result: no-read reason names global motion because adjacent dominant
  shift exceeds the reachable `4 px` guard.

### S4 - Motion-Scoped Fragment Merge And Shape Gate

- Merge fragments only inside moving activity islands.
- Compute merged centroid, bounds, area, convex hull, hull solidity, PCA
  principal-axis length/width ratio, edge-touching state, compact roundness,
  rounded/capsule score, and color statistics.
- Reject line, box, scattered fragments, and color-incoherent candidates with
  explicit reason codes.
- Emit physical candidate `Blob` records only after passing per-frame gates.

Proof:

- Synthetic fixture: one ball fragmented into same-color islands becomes one
  physical candidate.
- Synthetic fixture: `<= 2 ms` sharp ball passes.
- Synthetic fixture: elongated rounded/capsule motion blur passes.
- Negative fixtures: thin line fails; box fails by compact-branch roundness;
  scattered far-apart islands do not merge into one ball; color-incoherent
  region fails.
- Tilted motion-blur capsule fixture passes shape gates using solidity/PCA
  metrics even when bbox compactness/aspect would misclassify it.

### S5 - Existing Reducer Composition And Track Consistency

- Feed physical candidate frames to `VisualEstimateCandidateReducer.reduce`.
- Do not reimplement existing direction/RANSAC/max-jump/speed gates.
- Add recorded-HFR opt-in post-selection physical consistency validation.
- Keep existing `CandidateReductionBudget` behavior and messages.
- Exempt or trim edge-touching entry/exit candidates from area/short-side
  collapse consistency gates.
- Validate that the acceleration ratio gate cannot pass a 4x mis-association
  unless the existing line-residual/speed gates select a shorter coherent
  sub-window.
- Treat acceleration ratio `[0.25, 4.0]` as provisional; tests must prove
  existing line-residual/speed gates reject a 4x wrong association before any
  field tuning loosens or tightens it.

Proof:

- Pipeline fixture where raw threshold components exceed the old per-frame cap
  but merge into one physical candidate and proceed to the reducer.
- Sibling characterization test: with physical detection disabled, the same
  noisy candidate frames still fail/pass exactly as today.
- Negative fixtures: sudden area collapse, sudden short-side collapse, shape
  change to box, color jump, vertical sawtooth, and impossible step ratio each
  fail loud with no plausible mph.
- Positive fixture: smooth roughly straight centroid path with consistent
  physical candidates reaches estimate or the next downstream gate.
- Positive fixture: edge-cropped entry/exit ball still passes after trimming or
  exemption when the interior track is coherent.

### S6 - Proof Thumbnails, Logs, And User Diagnostics

- Extend detector trace/proof summaries with:
  - raw threshold/component counts;
  - persistent/static pixel count;
  - active/moving pixel count;
  - dominant global-motion shift;
  - physical candidate count;
  - shape/color rejection reason;
  - consistency failure reason.
- Proof thumbnails must draw or report merged physical candidates when
  available, not only raw fragments.
- Logs must stay counts/metrics only; no raw pixels, private paths, or media
  identifiers.

Proof:

- JVM trace test for accepted physical candidates.
- JVM trace test for ambiguous background separation.
- UI/report test that no-read displays actionable detector reason and no
  mph/angle/carry values.

### S7 - Docs And Functional Registry

Update in the implementation change:

- `docs/HOW_THE_APPLICATION_WORKS.md`;
- `docs/DATA_FLOW.md`;
- `docs/FUNCTIONAL_TEST_REGISTRY.md`;
- `docs/SECURITY_CHECKLIST.md`;
- `docs/HOW_TO_RUN.md` only if field-test steps change.

Docs must state that recorded-HFR detects a moving physical ball through
temporal motion isolation, fragment merge inside moving regions, shape/color
gating, and existing path/RANSAC selection. Docs must not claim strict
production accuracy until the S10+ field proof is captured. Docs must also
state the stationary-camera assumption and the fail-loud global-motion guard.

### S8 - Verification And Device Proof

Minimum local checks before implementation review:

- focused JVM tests for the new detector, reducer composition, streaming
  pipeline, UI/report diagnostics, and docs/source guards;
- `./gradlew :app:testDebugUnitTest`;
- `./gradlew :app:assembleDebug`;
- `pnpm docs:check`;
- `bash scripts/security-check.sh`;
- `git diff --check`.

Device proof after install:

- S10+ recorded-HFR `shoot` with post-Ready pop/impact;
- logs show bounded decode window, physical detector summaries, terminal
  estimate/no-read, and no unbounded whole-clip decode;
- proof thumbnails show processed frames and merged physical candidate evidence;
- failure, if any, names the specific detector/consistency gate.

## Fixture Matrix

Committed synthetic fixtures/tests:

1. Static same-color background plus moving ball delta.
2. Temporal-persistence-only production window with no context frames.
3. One ball fragmented into multiple same-color islands.
4. Supported `<= 2 ms` sharp-ball regime.
5. Elongated rounded/capsule motion-blur ball.
6. Thin line negative.
7. Box negative.
8. Scattered far-apart islands negative.
9. Sudden size/short-side collapse negative.
10. Sudden shape change negative.
11. Color incoherence/jump negative.
12. Vertical sawtooth negative.
13. Smooth physically feasible track positive.
14. Camera-jitter/static-background motion negative, including sustained jitter
    and a single-frame jolt.
15. Edge-cropped entry/exit positive.
16. Tilted capsule positive proving orientation-invariant solidity/PCA gates.
17. Validated capsule versus round off-path noise ranking positive.

Local-only diagnostic evidence:

- `.interagent/tmp/field-proof-recovery/extracted-1780862777355/impact-window-frames`
- `.interagent/tmp/field-proof-recovery/speed_ball_1280x720_120_1780862777355.mp4`

These real frames remain gitignored and must not be committed.

## Gate 2 Questions For Claude

1. Are the temporal-persistence/activity thresholds concrete enough for first
   implementation while preserving fail-loud ambiguity handling?
2. Is the bounded threshold-mask accumulator acceptable, or should Gate 2 force
   a different no-full-ARGB retention shape?
3. Does the proposed `Blob` metrics extension plus recorded-HFR opt-in
   consistency validation correctly reconcile with existing
   `blobShapePenalty`/`compactness`?
4. Are the S1-S8 subtasks sufficient to prove O1-O5 before implementation?
5. Are any thresholds too strict for the S10+ field proof or too loose for
   false-positive rejection?

## Current Status

Gate 1 has converged. Gate 2 is ready for adversarial review. Implementation
starts only after Gate 2 converges by mutual exhaustion.
