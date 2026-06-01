# Data Flow

## Phase 1 Shell

```text
MainActivity
  -> SpeedBallApp Compose shell
  -> Compose-free SpeedBallShellState
  -> pending/unavailable workflow rows
  -> no-read results state
```

The root project now has two modules:

- `:core` is pure Kotlin/JVM and contains measurement model, calibration,
  unit-conversion, velocity-fit, outlier-rejection, fail-loud measurement outcome
  logic, and trajectory physics.
- `:app` depends on `:core`, renders the Android shell, and owns the Camera2
  capture foundation plus Phase 5 decode/frame-timestamp proof, Phase 6
  value-anchor investigation logging, and Phase 7 preview timestamp proof.

No imported media or production mph results flow through the app shell yet.
Camera HAL modes, SENSOR_TIMESTAMP diagnostics, decoded frame counts, PTS gap
diagnostics, preview timestamp proof diagnostics, sampled frame dimensions,
Phase 8/10 no-read result reasons, and Phase 10 workflow readiness lines may
flow through the developer diagnostics or result-state surfaces only after real
enumeration/capture/decode/proof, pure workflow validation, or a typed failure.
Phase 6 anchor diagnostics flow only to bounded logcat lines and remain outside
the Compose result state. Phase 8/10 success is reachable only from test source
or a future Phase 9 bound direct input because main source contains no
production-minted generic `MeasurementTimingProof`.

## Core Measurement Path

```text
List<Detection> + pixelsPerFoot + MeasurementOptions
  -> timestamp/input/calibration validation
  -> centered-time OLS fit
  -> optional leave-one-out rejection
  -> residual gate
  -> px/s -> ft/s -> mph
  -> MeasurementOutcome.Success or MeasurementOutcome.Failure
```

`MeasurementOutcome.Failure` carries only a reason and message, never a partial
speed or angle.

## Core Trajectory Path

```text
LaunchState + BallSpec + AirSpec + TrajectoryOptions
  -> launch/ball/air/options validation
  -> RK4 projectile integration
  -> quadratic drag acceleration
  -> interpolated y=0 ground crossing
  -> apex/carry/hang summary
  -> TrajectoryOutcome.Success or TrajectoryOutcome.Failure
```

`TrajectoryOutcome.Failure` carries only a reason and message, never partial
trajectory samples or plausible carry/hang/apex values.

## Live 120 fps Path

```text
MainActivity
  -> request CAMERA permission
  -> HighSpeedCamera enumerates back-camera Camera2 high-speed HAL ranges
  -> pure HighSpeedMode mapper validates fixed Range(fps,fps)
  -> developer UI selects default 720p@120 when available
Camera2 constrained high-speed session
  -> preview surface + MediaRecorder surface
  -> capture callback SENSOR_TIMESTAMP list
  -> BurstDiagnostics unique-count floor + median-gap band
  -> saved burst in app-specific external files
  -> MediaExtractor reads exact output File video samples and PTS list
  -> bounded frame proof samples two non-adjacent decoded frames
  -> exact-count reconciliation pairs decoded frame index i to SENSOR_TIMESTAMP i
  -> fail loud on count mismatch, cadence mismatch, dropped gaps, or near-duplicates
  -> optional Phase 6 value-anchor diagnostics on near-duplicate/count mismatch
  -> TIMESTAMP_ANCHOR_* logcat lines only; never DecodeOutcome.Success
  -> bounded HSV/blob centroid detection after a source-specific timing proof exists
  -> flight window selection
  -> core velocity fit
  -> core trajectory physics
  -> Compose results UI
```

Phase 5 implements the path through decode/frame-timestamp reconciliation. Phase
6 implements investigation-only value-anchor diagnostics after selected fail-loud
decode paths. A diagnostic `Proven` anchor is not a measurement-ready pairing,
and the S10+ device proof for anchor behavior now rejects fail-loud with
`SENSOR_NEAR_DUPLICATE` after a real burst/decode run (`N=268`, exact distinct
sensor timestamps `325`, hypothetical post-collapse sensor count `260`).
Detection, calibration UI, result UI, import mode, and 240 fps GPU proof remain
planned.

## Phase 8 Pure Measurement Foundation

```text
TimedFrameSequence
  -> finite, strictly increasing timestamps
  -> bounded frame dimensions/count/pixels
  -> RGB/ARGB to HSV threshold inside ROI
  -> connected-components with threshold-pixel, component, and operation caps
  -> exactly one selected blob per accepted frame
  -> timestamp-preserving Detection list
  -> measurement-time DistanceCalibration revalidation
  -> VelocityMeasurementCalculator.measure
  -> TrajectoryPhysics.simulate
  -> MeasurementRunOutcome.Success only when caller supplies MeasurementTimingProof
```

In Phase 8/10 the only generic timing proof lives in `app/src/test/...`
synthetic fixtures. Production entrypoints use
`MeasurementPipeline.currentProductionNoRead()` and return `UNPROVEN_TIMING`.
Dropped or rejected interior frames do not renumber
timestamps; surviving detections keep the source frame timestamp so residual and
time-spread gates still see gaps.

## Phase 9 Direct Proof Domain Model

```text
DirectFrameProof list
  -> capture target is above the 12-frame token minimum
  -> require at least 12 consumed same-update direct frames
  -> direct timestamps checked against SENSOR_TIMESTAMP values
  -> exact membership or reviewed <1,000 ns zero-offset equivalent
  -> reject wrong-by-k offsets, cadence/drop/duplicate/non-monotonic gaps as a whole stream
  -> same-update tile/ROI pixels consumed into aggregate signatures only
  -> reject blank/stale signatures, count mismatch, and resource overrun
  -> root-cause diagnostics record callback/appended counts, readback time, release-step time, cadence/gaps, and final failed gate
  -> companion encoder scratch MP4 is app-private cache data only
  -> scratch MP4 deleted on terminal paths and never read as a source
  -> timestamp + dimensions + frame order + aggregate pixel-signature digests
  -> DirectSequenceContentIdentity
  -> DirectProofTokenEligibility only after all source gates pass
  -> vetted factory emits inseparable DirectSourceMeasurementInput
  -> MeasurementPipeline.measureWithDirectProof
```

Task 1 adds the pure proof model only. It does not create an Android capture
path and does not mint a production `MeasurementTimingProof`. A run id alone is
not enough to authorize measurement. The direct-source token cannot be passed
through the generic `measureWithProvenTiming` path, and `TimedFrameSequence`
does not expose caller-settable proof identity fields. The vetted factory binds
current-run proof success to an inseparable `DirectSourceMeasurementInput` only
when the candidate `TimedFrameSequence` matches the proven direct frames by
count, relative timestamp, dimensions, and aggregate pixel signature. Only then
can `MeasurementPipeline.measureWithDirectProof` return success. Direct proof
diagnostics carry counts, session shape, sequence identity, and token-eligibility metadata, but no
raw pixels, paths, user media identifiers, mph, angle, or trajectory values.
The timestamp proof accepts exact `SENSOR_TIMESTAMP` membership first. A
nonzero zero-offset-equivalent path is limited to offsets below `1,000 ns` and
still rejects offsets ambiguous with whole-frame `k * expectedGap` displacement.
The pixel proof consumes raw tile pixels only long enough to compute aggregate
hash/checksum/variation metrics; no raw pixels are stored in diagnostics or
logs.
The companion encoder scratch helper exists only to provide a bounded
`MediaRecorder` surface for the later Camera2 session shape. Its file is created
under app-private cache, bounded by duration/size, deleted on terminal paths,
and is not decoded, imported, path-logged, or measurement-consumed.
The direct GL readback helper consumes each accepted `SurfaceTexture` callback
with one `updateTexImage()` call, records that same consumed-frame timestamp,
draws the external OES texture into a bounded pbuffer, and immediately converts
`glReadPixels` output into the aggregate pixel signature. The frame collector
stores only one atomic timestamp/signature proof record per callback, rejects
late callbacks after teardown, and fails loud on frame/sample resource caps.
Phase 11 targets 24 direct readbacks instead of stopping at one frame, and adds
bounded diagnostics for frame-available callbacks, capture callbacks, appended
proof frames, readback latency, release-step latency, direct/sensor median
cadence, direct/sensor maximum gaps, and the final failed proof gate. The runner
does not filter interior degraded frames to reach the 12-frame minimum; a stream
with interior near-duplicates, coalescing, or dropped-frame gaps rejects as a
whole.
The companion-first proof runner attempts the companion-encoder session shape
before the preview-only control. It validates the companion's consumed direct
timestamps and aggregate pixel signatures before producing token eligibility,
and it rejects fewer than `12` consumed same-update frames with
`INSUFFICIENT_DIRECT_FRAMES` before any token eligibility exists. The
preview-only result is logged as a regression diagnostic only and cannot
authorize a proof token. Camera-busy, camera-open, or scratch-cleanup failures
stop before the preview control so the app cannot mask an unreleased camera or
leftover scratch file with a later control result.
The production direct-source timing token is structurally bound to the factory
emitted `DirectSourceMeasurementInput`. A stale token, a naked direct token on
the generic pipeline, a forged success outside the allowlisted proof producers,
or a measured sequence that does not recreate the proof-frame signatures remains
`UNPROVEN_TIMING`.
The debug `autoStartDirectProof120` entry point runs the direct companion proof
from `MainActivity`. It owns a companion `MediaRecorder` scratch surface and a
direct `SurfaceTexture`/GL readback surface in the same constrained high-speed
session, then runs the preview-only control as a separate diagnostic when the
companion teardown permits it. UI diagnostics show only direct proof status,
typed failures, counts, and whether preview control was attempted; they do not
show paths, raw pixels, mph, angle, or trajectory on failure.

## Phase 10 Workflow Foundation

```text
CalibrationWorkflowState
  -> MeasurementCalibrationState.pixelsPerFoot() revalidation
  -> calibration ready/not-ready line
ColorWorkflowState
  -> finite HSV sample + clamped tolerance + clipped ROI
  -> detector-ready HsvThreshold/RegionOfInterest or not-ready line
MeasurementWorkflowState
  -> calibration/color/source/capture prerequisites
  -> result carried only as MeasurementRunOutcome
MeasurementResultUiState
  -> no-read action text or success values from MeasurementRunOutcome.Success
MainActivity
  -> SpeedBallShellState guided checklist
  -> Compose shell result/readiness lines
```

Phase 10 does not connect live Camera2 frames, decoder output, preview
diagnostics, or Phase 9 device evidence to measurement success. The app shell
shows calibration/color/source/capture readiness and the current
`MeasurementRunOutcome`. Developer proof diagnostics remain separate diagnostic
lines. Production stays `UNPROVEN_TIMING` until direct readback proves at least
12 same-update frames and emits a bound direct input in a later phase.

## Preview Timestamp Proof Path

```text
MainActivity --ez autoStartPreview120 true
  -> debug-only show-when-locked / turn-screen-on proof window
  -> PreviewTimestampSpikeCapture
  -> Camera2 constrained high-speed session
  -> single SurfaceTexture preview target
  -> SurfaceTexture.updateTexImage() consumed-frame timestamps
  -> CaptureResult.SENSOR_TIMESTAMP callback timestamps
  -> pure PreviewFramePairer exact-membership proof
  -> PreviewFrameOutcome.Success or typed PreviewFrameOutcome.Failure
  -> bounded PREVIEW_* logcat diagnostics and developer UI lines only
  -> no measurement result
```

The S10+ measured 720p@120 proof accepts the preview-only session and Camera2
generates a high-speed request list of size `4`. The consumed preview timestamps
exactly match `SENSOR_TIMESTAMP` values, but the median preview cadence is
`33.3775 ms` instead of the requested `8.3333 ms`; the latest run had
equal preview and sensor counts with all offsets zero. The pairer therefore
returns
`PREVIEW_CADENCE_MISMATCH` and keeps the app no-read.

## Live 240 fps Path

```text
Camera2 constrained high-speed session
  -> SurfaceTexture / GPU preview path
  -> GLSL HSV threshold mask
  -> centroid reduction
  -> SENSOR_TIMESTAMP timing
  -> core velocity fit
  -> results UI
```

This path is required on S10+ because MediaRecorder cannot persist true 240 fps.

## Import Path

```text
user-selected video URI
  -> validated metadata and frame extraction
  -> offline HSV/blob detection after import timing proof exists
  -> timestamp/PTS validation
  -> core velocity and trajectory
  -> results UI
```

## Calibration

Color calibration produces HSV bounds. Distance calibration produces
`pixelsPerFoot`. Both are required before reporting mph, and distance
calibration is revalidated at measurement time rather than trusted from stale
state.
