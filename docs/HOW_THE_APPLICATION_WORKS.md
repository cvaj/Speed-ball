# How The Application Works

Speed-ball measures a hit softball by tracking a neon-colored ball in high-speed phone-camera frames.

## Current App State

The visible app opens to a Compose workflow list plus a developer diagnostics
surface for high-speed capture and decode proof. The app can request camera
permission, enumerate Camera2 high-speed modes from the device HAL, start a
bounded 120 fps MediaRecorder burst when a fixed 120 fps range is exposed, and
run Phase 5 decode/frame-timestamp reconciliation on the exact app-created MP4.
When exact-count reconciliation fails from near-duplicate sensor timestamps or a
decoded/sensor count mismatch, Phase 6 can emit logcat-only value-anchor
diagnostics. Those diagnostics are investigation evidence only; they cannot
produce a measurement-ready pairing or a result. Phase 7 adds a developer-only
decoder-free `SurfaceTexture` timestamp proof path. On the measured S10+ run,
`SurfaceTexture.timestamp` exactly matched `SENSOR_TIMESTAMP`, but the
preview-only stream delivered about 30 fps while 120 fps was requested, so the
path fails loud with `PREVIEW_CADENCE_MISMATCH`. Phase 8 adds the pure Kotlin
detection, calibration, measurement orchestration, and result-state foundation,
but it deliberately has no production implementation of `MeasurementTimingProof`.
The results section therefore stays in a no-read state for every real source
until Phase 9 provides an evidence-derived timing source.

The implemented `:core` module contains calibration, unit conversion, velocity
measurement, and trajectory physics. The `:app` module owns the Camera2 capture
foundation, timestamp diagnostics, Phase 5 decode proof, Phase 6 value-anchor
investigation logging, Phase 7 preview timestamp proof, and Phase 8 pure
measurement pipeline foundation. It must still not display a sample speed,
trajectory, or placeholder result value for any production source. Camera mode,
timestamp, frame-count, raw decode, preview proof, anchor diagnostics, and
no-read result reasons may be shown only after real HAL enumeration, a real
capture/proof run, a typed failure, or bounded logcat diagnostics.

## User Flow

1. The user selects a supported camera mode, such as 720p at 120 fps.
2. The user calibrates distance by marking a known distance in the ball's plane.
3. The user taps the neon ball to sample its color.
4. The app records a short high-speed burst.
5. The app detects the ball center in each frame.
6. The app fits a straight-line velocity over valid detections.
7. The app converts pixels per second to mph using the distance calibration.
8. The app reports speed, launch angle, confidence, and a drag-based trajectory.

## Measurement Rules

Frame coordinates are raw image pixels. Image `y` points downward, so upward velocity is negative `vy`.

The core measurement API accepts validated detections and distance calibration,
then returns either a typed success or a typed failure. Failures do not contain
mph, angle, or partial result values.

Speed is:

```text
speed_px_per_s = sqrt(vx^2 + vy^2)
feet_per_second = speed_px_per_s / pixels_per_foot
mph = feet_per_second * 0.6818182
```

Launch angle is:

```text
angle_degrees = atan2(-vy, abs(vx))
```

The camera must be roughly side-on to the swing plane. Off-axis setup creates foreshortening error and must be surfaced to the user.

## Trajectory Rules

Core trajectory physics simulates the measured launch in meters with quadratic
drag and fixed-step RK4 integration. The default ball/environment model is a
12-inch-circumference softball with standard sea-level air:

- softball diameter `0.0955 m`, mass `0.1899 kg`;
- air density `1.225 kg/m^3`, drag coefficient `0.40`;
- gravity `9.81 m/s^2`;
- integration time step `0.001 s`, max flight `15.0 s`.

Trajectory results report:

- `apexMeters`: maximum absolute height above ground;
- `carryMeters`: interpolated horizontal distance at the first `y = 0` ground
  crossing;
- `hangTimeSeconds`: interpolated time to that ground crossing.

Angles from `-90` through `90` degrees are valid. Negative or horizontal launches
from ground return an immediate ground result. Negative or horizontal launches
from positive height simulate to ground. Angles outside that range fail loudly.

## Fail-Loud Behavior

The app must show "No read" rather than a wrong speed when:

- camera permission is denied;
- no back camera or no supported high-speed HAL mode exists;
- a requested high-speed mode lacks the exact fixed AE range needed for
  recording;
- a capture burst is already active;
- Camera2 open, disconnect, device error, session configuration, recorder setup,
  or recording fails;
- no positive `SENSOR_TIMESTAMP` values are collected;
- the 120 fps capture proof fails either the unique-count floor or median-gap
  band;
- the recorder output file is missing, empty, lacks a video track, has invalid
  metadata, or cannot expose decoded sample timestamps;
- fewer than three decoded frames are available;
- decoded presentation timestamps are non-monotonic, have a median cadence
  outside the requested fps band, or contain a gap larger than `1.5 *
  expectedGap`;
- normalized `SENSOR_TIMESTAMP` values are missing, contain a nonzero
  near-duplicate gap below the provisional Phase 5 threshold, have a median
  cadence outside the requested fps band, or contain a gap larger than `1.5 *
  expectedGap`;
- decoded frame count does not exactly equal unique `SENSOR_TIMESTAMP` count;
- value-anchor diagnostics are rejected, ambiguous, unavailable, or only
  investigation-proven; Phase 6 never turns them into `DecodeOutcome.Success`
  or measurement timestamps;
- decoder-free preview proof lacks positive `SurfaceTexture` timestamps, lacks
  positive `SENSOR_TIMESTAMP` callbacks, has duplicate/non-monotonic/near-duplicate
  preview timestamps, has preview cadence outside the requested fps band, contains
  dropped preview gaps, cannot prove exact sensor membership, indicates preview
  undercount/coalescing, or hits a nonzero/ambiguous offset hypothesis;
- bounded frame extraction cannot prove two non-adjacent decoded frames at the
  expected dimensions;
- fewer than three valid detections exist;
- timestamps are missing, duplicate, non-monotonic, or unpaired;
- timestamps are too close together to produce a meaningful fit;
- calibration is missing or invalid;
- the fit is non-finite or residuals are too large;
- trajectory launch, ball, air, or numeric options are invalid;
- trajectory integration becomes non-finite or does not cross ground within the
  max flight time;
- the detector cannot distinguish the ball from background blobs;
- Phase 8 frame-processing bounds are exceeded for dimensions, frame count,
  threshold-passing pixels, connected-component count, or per-frame operations;
- a production source attempts to measure without a Phase 9 timing proof.

Core failure reasons are:

- `INSUFFICIENT_DETECTIONS`
- `BAD_TIMESTAMP`
- `INVALID_DETECTION`
- `INVALID_CALIBRATION`
- `INVALID_OPTIONS`
- `NON_FINITE_FIT`
- `EXCESSIVE_RESIDUAL`

Phase 4 capture failure reasons are:

- `CAMERA_PERMISSION_DENIED`
- `NO_BACK_CAMERA`
- `NO_HIGH_SPEED_MODES`
- `UNSUPPORTED_MODE`
- `CAPTURE_BUSY`
- `CAMERA_OPEN_FAILED`
- `CAMERA_DEVICE_DISCONNECTED`
- `CAMERA_DEVICE_ERROR`
- `SESSION_CONFIGURATION_FAILED`
- `RECORDER_PREPARE_FAILED`
- `RECORDING_FAILED`
- `NO_SENSOR_TIMESTAMPS`
- `RESOURCE_RELEASE_FAILED`

Phase 5 decode failure reasons are:

- `OUTPUT_FILE_MISSING`
- `OUTPUT_FILE_EMPTY`
- `UNSUPPORTED_DECODER_API`
- `NO_VIDEO_TRACK`
- `INVALID_VIDEO_METADATA`
- `FRAME_COUNT_UNAVAILABLE`
- `FRAME_COUNT_TOO_LOW`
- `FRAME_EXTRACTION_FAILED`
- `MISSING_SENSOR_TIMESTAMPS`
- `SENSOR_TIMESTAMP_NEAR_DUPLICATE`
- `FRAME_SENSOR_COUNT_MISMATCH`
- `PRESENTATION_TIMESTAMPS_NON_MONOTONIC`
- `PRESENTATION_CADENCE_MISMATCH`
- `PRESENTATION_DROPPED_FRAME_GAP`
- `SENSOR_CADENCE_MISMATCH`
- `SENSOR_DROPPED_FRAME_GAP`
- `DECODE_WORK_LIMIT_EXCEEDED`
- `RESOURCE_RELEASE_FAILED`

Phase 7 preview proof failure reasons are diagnostic-only and include:

- `CAMERA_PERMISSION_DENIED`
- `NO_BACK_CAMERA`
- `UNSUPPORTED_MODE`
- `CAPTURE_BUSY`
- `CAMERA_OPEN_FAILED`
- `CAMERA_DEVICE_DISCONNECTED`
- `CAMERA_DEVICE_ERROR`
- `SESSION_CONFIGURATION_FAILED`
- `SURFACE_CONFIGURATION_REJECTED`
- `GL_SETUP_FAILED`
- `FRAME_TIMEOUT`
- `MISSING_PREVIEW_TIMESTAMPS`
- `MISSING_SENSOR_TIMESTAMPS`
- `DUPLICATE_PREVIEW_TIMESTAMPS`
- `PREVIEW_TIMESTAMPS_NON_MONOTONIC`
- `PREVIEW_TIMESTAMP_NEAR_DUPLICATE`
- `PREVIEW_CADENCE_MISMATCH`
- `PREVIEW_DROPPED_FRAME_GAP`
- `SENSOR_MEMBERSHIP_UNAVAILABLE`
- `FRAME_SENSOR_COUNT_MISMATCH`
- `PREVIEW_UNDERCOUNT_COALESCING`
- `NONZERO_OFFSET_REQUIRES_REVIEW`
- `AMBIGUOUS_OFFSET`
- `LATE_CALLBACK_AFTER_TEARDOWN`
- `RESOURCE_RELEASE_FAILED`

Phase 8 measurement-run failure reasons are:

- `UNPROVEN_TIMING`
- `BAD_FRAME_SEQUENCE`
- `DETECTION_FAILED`
- `INSUFFICIENT_DETECTIONS`
- `BAD_CALIBRATION`
- `MEASUREMENT_REJECTED`
- `RESOURCE_LIMIT_EXCEEDED`

## Capture Modes

- 120 fps on S10+ uses record-then-decode because it records cleanly.
- 240 fps on S10+ requires GPU/preview detection because MediaRecorder drops frames.
- Device capabilities must come from Camera2 HAL enumeration, not hardcoded assumptions.
- Phase 4 records only fixed-range 120 fps modes. It enumerates 240 fps modes
  but returns `UNSUPPORTED_MODE` if asked to record them.
- A burst is considered 120 fps proof only when unique sensor timestamps meet
  the requested-duration floor and the median inter-frame gap is inside the
  `1000/fps` ms +/-15% band. For 120 fps that is about `8.33 ms`.
- Phase 5 decode proof uses `MediaExtractor` sample count and presentation
  timestamps for decoded-order diagnostics, then pairs frames by index only when
  decoded frame count exactly equals unique `SENSOR_TIMESTAMP` count. Container
  PTS is diagnostic; `SENSOR_TIMESTAMP` remains the measurement timing authority.
- Phase 6 value-anchor analysis runs only as developer diagnostics after
  near-duplicate sensor timestamps or decoded/sensor count mismatch. It compares
  decoded PTS values to real `SENSOR_TIMESTAMP` values, logs candidate offsets,
  residuals, dropped-hole agreement, near-duplicate/post-collapse evidence, and
  a diagnostic verdict, but it never feeds measurement or the user-facing result
  path. S10+ Phase 6 device evidence now shows a real 120 fps burst followed by
  a fail-loud anchor rejection: `SENSOR_NEAR_DUPLICATE`, decoded `N=268`, exact
  distinct sensor timestamps `325`, and hypothetical post-collapse sensor count
  `260`. That is evidence against using the record-then-decode path as a
  measurement-ready pairing source.
- Phase 7 preview proof uses a debug-only `autoStartPreview120` developer entry
  point and a Camera2 constrained-high-speed `SurfaceTexture` target. It logs
  bounded `PREVIEW_*` diagnostics and runs the pure Kotlin preview pairer. The
  measured S10+ result accepted the preview-only session and generated a
  high-speed request list of size `4`, but delivered `66` consumed preview
  timestamps at median gap `33.3775 ms` for a requested `8.3333 ms`; exact
  timestamp identity still held for all consumed preview frames. The app therefore
  returns no-read with `PREVIEW_CADENCE_MISMATCH`, not a speed.
- Phase 8 contains a bounded pure Kotlin RGB/HSV detector, connected-components
  blob selector, timestamp-preserving track extractor, calibration revalidation,
  core measurement orchestration, trajectory projection, and result-state
  formatting. Integration tests can produce `MeasurementRunOutcome.Success`
  only with a synthetic timing-proof token defined in test source. Main
  production source has no timing-proof implementation, so Phase 5/6/7 real
  sources still return no-read with `UNPROVEN_TIMING`.
