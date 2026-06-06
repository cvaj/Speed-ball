# How The Application Works

Speed-ball measures a hit softball by tracking a neon-colored ball in high-speed phone-camera frames.

## Current App State

The visible app opens as a landscape camera-first workflow with a live Camera2
feed used for field calibration. The feed reads the back camera sensor
orientation relative to the current display rotation, rotates the `TextureView`
preview into screen coordinates, and preserves the oriented camera aspect ratio
instead of stretching raw buffers.
It overlays two draggable vertical A/B caliper lines directly on the camera
image, plus a small two-color bottom-right level reference axis driven by the
phone IMU while setup is live. The cyan segment is true level-horizontal; the
amber segment is perpendicular level-vertical. The guide compensates for phone
roll, so tilting the camera rotates the on-screen guide in the opposite visual
direction needed to stay aligned with world horizontal/vertical. It freezes
when voice-triggered recording or direct capture starts so that the shot uses
the last pre-capture reference. The app does not draw a fake screen-horizontal fallback when level is unavailable. The live camera controls are
hidden by default except for a small translucent Tools handle at the bottom.
Tapping Tools opens one translucent bottom drawer at a time. The primary drawer
contains only camera-use actions: target A/B, coarse/fine caliper drag, voice,
setup, and hide. Setup replaces that drawer instead of stacking
another panel on the image; it contains distance, permission, mode, manual
level recapture, ball fallback, color sampling, ROI setup,
manual estimate, import/recalibration, and recorder diagnostics.
Both drawer pages print a compact on-screen status row with the current app
status and active setup target, so actions such as applying distance or
sampling color have visible confirmation. Hide closes all tools. The A/B
caliper lines and preview tap/drag calibration remain usable while tools are
hidden, so field references near the ground are not blocked by the drawer.
ROI, color sample target, and level/horizon overlays are calibration aids and
appear only with the summoned operation controls, not as permanent camera
clutter. The user enters the known distance in feet, slides the two vertical
lines onto the measured field markers with smooth finger drag, uses the drawer
toggle for coarse/fine drag precision, samples the ball color from camera
pixels, and places the ROI around the
expected hit path before running a live visual estimate or import estimate. The
distance field is replacement state: editing it after A/B lines are set
overwrites the active known-distance feet value while preserving the current A/B
line positions. The field remains raw user text while editing: clearing it stays
empty, partial text is allowed, and accepted numeric values are parsed separately
from the displayed text. Invalid distance text makes setup not ready instead of letting
the next live or import estimate reuse an older distance. If `shoot` is
recognized while setup is blocked, the app shows and logs the specific missing
gate, such as missing color sample, invalid sampled HSV, missing/invalid ROI,
missing distance setup, or invalid distance text. The
app can request camera permission, enumerate Camera2 high-speed modes from the
device HAL, listen for voice commands, release the setup feed so the
high-speed recorder can own the camera, start a bounded 120 fps MediaRecorder
burst when a fixed 120 fps range is exposed, stop on its timer, and
automatically estimate from the exact app-created MP4 without sending the user
through the Android picker.
In the setup drawer, `Record 120` is this MediaRecorder MP4 path. `Est 120` is
the separate direct live estimate path: it still uses the selected fixed 120 fps
Camera2 high-speed mode, but it reads direct frames instead of recording an MP4.
When an estimate succeeds, the camera preview is covered by a black result
screen with large bold white velocity, launch-angle, and carry-distance text.
The `CLEAR` button dismisses the result screen and returns to the camera view.
No-read and capture-failure outcomes do not use that full-screen black result
screen; they show a bottom report with reason, action, message, and
attempt-scoped capture proof while the setup preview remains visible after
capture recovery. The proof is a bounded contact sheet of the processed
direct-readback frames the detector saw. On the S10+ direct path those frames
are low-resolution 160x90 diagnostics, not full preview photos. The panel also
shows captured-frame count, readback size, candidate-frame count,
candidate-blob count, and selected-sample count. Zero captured frames means the
attempt produced no imagery. Zero candidate frames means the selected color/ROI
matched no blobs in the processed frames.

The app now separates the visible workflow into two app-level modes:

- **Setup Mode** is for configuring camera permission, high-speed mode, A/B
  distance calipers, raw distance text, ball color, ROI, level, fallback, import,
  and diagnostics. The live preview remains visible so the user can align setup.
- **Run Mode** is for executing shots. It does not present setup editing as the
  normal surface. It shows the camera view, command readiness, a manual `Shoot`
  control, and a way back to Setup Mode.

Setup is expected to happen once per physical setup. Repeated shots preserve A/B
lines, distance text, color, ROI, and level unless the user explicitly changes
or recalibrates them. Entering Run Mode and every `shoot` re-checks the setup
gates, so a lost preview, invalid distance, missing color, invalid ROI, missing
level, or missing permission blocks the next capture with the specific reason.
Green ready/listening means the setup is valid and a command path is available.
If Android speech recognition is unavailable or repeatedly errors, Run Mode
shows a degraded/manual-ready state instead of green listening; manual `Shoot`
remains available when setup is valid, but the intended operating path is still
hands-free voice. Ordinary Android recognizer idle events such as no-match or
speech-timeout mean the app did not hear `shoot` yet; they restart listening and
do not count toward the fatal error cap. Real recognizer failures are bounded by
a single delayed scheduler, backoff, and a consecutive-error cap before the UI
settles into manual-ready/degraded state. Periodic listening beeps are deferred;
the visual readiness state is the primary signal.

Each Run Mode shot creates a monotonic visual-estimate attempt id. Success,
no-read, and failure reports belong to that attempt. `CLEAR` hides only that
attempt's report/proof and does not clear setup. Setup edits, live-feed
restarts, status changes, distance edits, and voice-listener updates cannot
resurrect a cleared report from the same attempt. A later shot can show a new
report and new proof imagery even if the reason text is identical. A success
full-screen overlay pauses command readiness until cleared because it covers
the field view; the same attempt proof remains visible on that overlay before
clear. A no-read/failure bottom panel may coexist with resumed
manual/listening readiness because the preview remains visible.
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
Phase 9 adds a developer-only direct proof path with a companion encoder
session driver plus same-update `SurfaceTexture`/GL readback. The encoded
scratch file is never read. The path still stays no-read on the measured S10+
run because it consumed only one direct frame; token eligibility now requires
at least 12 consumed same-update direct frames plus timestamp and pixel gates.
Direct timing tokens cannot authorize arbitrary frame sequences through the
generic pipeline; they must come through the factory-emitted bound direct input
whose RGB frames recreate the proven direct frame signatures.
Phase 10 adds the user-facing workflow foundation around that boundary:
calibration readiness, color-sample readiness, Phase 9 source-proof readiness,
capture route state, and result presentation all flow through typed pure JVM
state. It still adds zero production measurement capability on real hardware.
Phase 11 raises the direct readback target above the 12-frame token minimum and
adds root-cause diagnostics for the S10+ one-frame blocker: frame-available
callbacks versus appended proof frames, readback timing, release-step timing,
direct/sensor cadence, maximum gaps, and the final failed gate. Production
remains no-read unless the consumed direct stream passes the timestamp and pixel
proof gates as a whole; degraded streams cannot be filtered down to 12 frames.
Phase 12 begins the source-route unblock by making direct-source variants
explicit in code. The Camera2 GL path can now request the baseline
companion+direct surface order, the direct-first control order, or a direct-only
GL constrained-high-speed target when the device accepts it. Diagnostics carry
variant id, consumer model, surface order, direct buffer size, request-list
size, and producer/capture callback cadence. The ImageReader route now creates
YUV `ImageReader` targets for constrained and standard Camera2 probes plus a
constrained `PRIVATE`/video-encode usage target, converts only a bounded tile
from same acquired YUV images into an aggregate signature, and closes the image.
The S10+ matrix proves constrained YUV ImageReader is rejected, constrained
PRIVATE ImageReader is accepted but delivers no image callbacks, and standard
ImageReader runs below the requested 120 fps producer band. The developer adb
entry point can select a route with
`--es directProofVariant <variant-id>`. The next route, `pbo-gl-readback`, is
now code-wired as a separate GLES3 PBO-backed GL consumer model: it does not
reuse the inline consumer label, and it maps the previous pixel-pack buffer on
the next callback instead of blocking the current callback on CPU readback. On
the S10+ it is accepted by constrained high speed, but still consumes about
30 fps and fails loud with `DIRECT_CADENCE_MISMATCH`, so no terminal no-go is
claimed without review.
Phase 12 also adds a developer-only timestamp-source characteristic read for
the remaining record-then-decode source decision. On the measured S10+ back
camera, `SENSOR_INFO_TIMESTAMP_SOURCE` is `REALTIME`. A follow-up
count-independent PTS-to-sensor value-match still fails to produce a
measurement-safe binding: `matched=248/257`, `ambiguous=62`, and
`longestCleanRun=3`. Combined with Phase 6 decoded `268`, exact distinct sensor
timestamps `325`, and post-collapse `260`, the S10+ `1280x720 @ 120`
record-then-decode route is a scoped production no-go and stays no-read.
Phase 13 begins the adaptable visual estimate path for the user's
personal-estimate product. It is separate from strict proof-token-backed
measurement: estimate success is a distinct type, cannot satisfy
`MeasurementRunOutcome.Success`, and does not carry `MeasurementTimingProof`.
The estimate path uses app-owned ordered frames. The direct live adapter reads
bounded ARGB frames from the same `SurfaceTexture.updateTexImage()` source,
not from a decoder. When real per-frame timestamps exist, they anchor timing.
When timestamps are missing or unusable, the app may use visual frame-delta
inference from centroid displacement plus a known per-frame interval. When both
signals exist, centroid displacement validates skipped/coalesced intervals;
disagreement no-reads as an ambiguous track. Estimate output is labeled,
carries the timing basis, confidence, residual, timestamp/frame-gap
diagnostics, scale basis, and discloses the calibrated-image-plane assumption.
When distance calibration is missing, estimate mode may self-calibrate from a
known ball diameter and detected apparent short-axis ball diameter; that scale
basis is reported separately and discloses that the entered ball type must match
the real ball and that motion blur can bias scale. For this ball-diameter
self-calibration estimate path, strong apparent size change can continue only
as LOW confidence with a warning rather than as a strict measurement claim.
Visual frame-delta estimates also disclose that the smallest observed frame gap
is assumed to be one native interval; uniformly dropped frames would bias speed
high. The S10+ decoder route remains closed for this mode.
Level is captured by the app at setup time with the phone's IMU, not recovered
from the MP4. The app samples `TYPE_GRAVITY` when available, falls back to the
accelerometer, uses the gyroscope to reject a moving phone, stores one static
roll reference, and assumes the tripod is not moved before capture. The preview
shows a level/horizon overlay from that snapshot. Estimate speed is unchanged
by image rotation, but launch angle is corrected by subtracting the captured
roll so it is reported against true horizontal. That sign convention is
documented in `LevelReference.kt` and remains provisional until the physical
gate `S10_IMU_LEVEL_SIGN_VALIDATION_PENDING` verifies a known phone roll against
a true horizon and known-horizontal motion. Estimates without a captured level
state that launch angle is not corrected for camera tilt.
Phase 15 adds estimate-only video processing. The normal field-test path is
hands-free: the user taps Voice once, then says "shoot" to run the
readiness-gated ready/beep sequence before the direct 120 fps visual estimate
path. Explicit "record" variants and the `Record 120` button remain a
diagnostic app-owned 120 fps MP4 path; they are not the S10+ field-test
`shoot` path because that route depends on recording then decoding a file. The
recorded file is fed directly into the same import estimate pipeline. The
high-speed recorder
requests a fast 1/1000s shutter through Camera2 manual exposure when the device
and constrained high-speed session allow it; if that manual request is rejected,
recording falls back to the device's auto-exposure path rather than failing the
capture. Manual document-picker import remains available for saved clips and
debugging. Picker imports validate a `content://` URI;
recorded estimates use the app-private file directly and do not store a media
URI or raw path in saved evidence. The app deletes the app-owned recorded MP4
after frame extraction/estimate processing. The app reads redacted metadata,
probes monotonic presentation timestamps as estimate evidence only for
user-selected imports, decodes a bounded set of frames, scales them to the
existing readback size, and runs the same color detector, calibration state, and
visual estimate pipeline used by live estimates. The Android import adapter
prefers indexed frame extraction and falls back to timestamp extraction when the
platform cannot return frames by index. Estimate-mode import can use a known
capture frame interval for slow-motion files whose playback PTS has been
rewritten; app-owned recordings use a distinct recorded-capture frame-interval
timing basis from the requested capture FPS. Recorded estimates are saved and
exported as `RECORDED_ESTIMATE` with a LOW-confidence MediaRecorder
frame-drop/coalescing caveat rather than import-only re-encode/VFR caveats.
Import uses the sampled
ball point to define the HSV color band, but does not force the track to start
at that point; it skips leading frames before the ball enters the ROI, filters
tiny color speckles, and uses RANSAC-style straight-line consensus to select a
short high-velocity one-directional yellow-ball window. The accepted window
must have enough inliers, low line residual, low slope change, and hit-like
speed so a struck ball is distinguished from a slower toss/pitch arc. The
window includes the frame immediately before fast motion when present, and the
final estimate no-reads if the calibrated speed is below the hit-ball
threshold. Import cannot produce
strict `MeasurementRunOutcome.Success`,
even for clean container timestamps, because container PTS is not proof of
original capture cadence. Final import
diagnostics use source-specific timing-basis labels, carry confidence from the
timing reconciler, and disclose provenance risks such as re-encode, VFR remux,
editor rewrite, transcode, user-declared interval, uniform-loss blindness, or
recorded MediaRecorder frame drop/coalescing where applicable. Import and
recorded results/no-reads can be saved as app-private redacted summaries and
exported as text evidence containing source kind, timing basis, frame/detection
counts, speed only for
successful estimates, no-read reason, and assumptions. Raw media URIs, paths,
videos, pixels, device endpoints, and pairing codes are not saved or exported.

The implemented `:core` module contains calibration, unit conversion, velocity
measurement, and trajectory physics. The `:app` module owns the Camera2 capture
foundation, timestamp diagnostics, Phase 5 decode proof, Phase 6 value-anchor
investigation logging, Phase 7 preview timestamp proof, Phase 8 pure
measurement pipeline foundation, visual estimate flow, and import estimate
history/export flow. It must still not display a sample speed,
trajectory, or placeholder result value for a strict production measurement
source. A Phase 13 visual speed may be shown only as a labeled estimate with
confidence, timing-basis diagnostics, and no-read gates. Camera mode,
timestamp, frame-count, raw decode, preview proof, anchor diagnostics, estimate
diagnostics, import evidence, and no-read result reasons may be shown only
after real HAL enumeration, a real capture/proof run, a user-selected import,
a typed failure, or bounded logcat diagnostics.

## User Flow

1. In Setup Mode, the user selects a supported camera mode, such as 720p at 120
   fps.
2. In Setup Mode, the user calibrates distance by entering the measured feet value and sliding
   the two vertical A/B caliper lines onto the matching field markers in the
   ball's plane, or selects the reviewed
   ball-diameter fallback.
   Editing the feet value later replaces the active distance calibration and
   keeps the A/B points; invalid text blocks readiness without reformatting the
   field text.
3. In Setup Mode, the user taps the live feed at the hanging ball/start reference so the app
   samples the actual camera pixels for the HSV color band, then sets the ROI
   around the expected ball path.
4. In Setup Mode, the app captures a still-phone IMU level snapshot and shows the horizon
   overlay; voice recording captures this automatically if it is missing.
5. Once setup is valid, the user enters Run Mode. Run Mode shows ready/listening
   only when the command path is truthful, and always exposes manual `Shoot`
   when setup remains valid.
6. In Run Mode, saying `shoot` or pressing `Shoot` starts a new capture attempt.
   The app captures a bounded high-speed visual-estimate window and stops
   automatically; the user should not reset setup between attempts.
7. For import mode, the user selects a video and the app processes it
   end-to-end without a manual start/stop recording step.
8. The app detects the ball center in each accepted frame.
9. The app uses RANSAC-style straight-line consensus to reject non-hit motion
   and fits velocity over the accepted detections.
10. The app converts pixels per second to mph using the distance calibration or
   the reviewed ball-diameter estimate fallback.
11. The app reports speed, level-corrected launch angle, confidence, and evidence for estimates,
   or a typed no-read/failure report with no speed values. Clearing the report
   returns Run Mode to the next ready/manual-ready shot path without clearing
   setup.

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
- the camera/session rejects fast-shutter control and the fallback recording
  path also fails;
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
- record-then-decode source decision evidence cannot identify which captured
  `SENSOR_TIMESTAMP` values survived encoding, or the count-independent
  PTS-to-sensor value-match is ambiguous/incomplete even when the camera
  timestamp source reports `REALTIME`;
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
- visual-estimate samples have neither usable timestamps nor a known visual
  frame-delta interval, have fewer than four usable detections, have incoherent
  timestamp-gap-vs-centroid-displacement ratios, lack positive centroid motion
  for visual frame-delta timing, reject too many visual outliers, exceed the
  estimate residual threshold, or show strong apparent ball-size change that
  violates the calibrated-plane assumption outside the reviewed
  ball-diameter self-calibration warning path;
- imported media is not selected through Android content URI APIs, has invalid
  or unsupported metadata, exceeds decode/frame/pixel caps, lacks strictly
  increasing presentation timestamps for the current Android import adapter,
  cannot be decoded safely, lacks required calibration/color/ROI setup, cannot
  form a coherent seeded or high-velocity one-directional yellow-ball motion
  window, or cannot run through the existing visual estimate pipeline;
- calibration is missing or invalid, unless estimate mode has a reviewed known
  ball-diameter self-calibration input and enough valid apparent diameters;
- the fit is non-finite or residuals are too large;
- trajectory launch, ball, air, or numeric options are invalid;
- trajectory integration becomes non-finite or does not cross ground within the
  max flight time;
- the detector cannot distinguish the ball from background blobs;
- Phase 8 frame-processing bounds are exceeded for dimensions, frame count,
  threshold-passing pixels, connected-component count, or per-frame operations;
- Phase 9 direct source proof rejects the session, timestamps, pixel signature,
  sequence identity, resource bounds, or proof-token eligibility;
- Phase 12 direct-source variant setup is rejected by Camera2/HAL, has
  below-band producer cadence where a standard-session consumer ratio would be
  interpreted, reaches an ImageReader timestamp/pixel conversion failure, or
  reaches a GL/PBO readback setup or mapping failure;
- Phase 10 workflow state is missing calibration, color sample, or bound Phase 9
  source proof;
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

Phase 9 direct proof failure reasons are diagnostic-only and include:

- `UNSUPPORTED_MODE`
- `CAPTURE_BUSY`
- `CAMERA_OPEN_FAILED`
- `SESSION_CONFIGURATION_FAILED`
- `COMPANION_RECORDER_SETUP_FAILED`
- `SCRATCH_FILE_CLEANUP_FAILED`
- `MISSING_DIRECT_TIMESTAMPS`
- `INSUFFICIENT_DIRECT_FRAMES`
- `DUPLICATE_DIRECT_TIMESTAMPS`
- `DIRECT_TIMESTAMPS_NON_MONOTONIC`
- `DIRECT_CADENCE_MISMATCH`
- `DIRECT_DROPPED_FRAME_GAP`
- `DIRECT_TIMESTAMP_NEAR_DUPLICATE`
- `MISSING_SENSOR_TIMESTAMPS`
- `SENSOR_MEMBERSHIP_UNAVAILABLE`
- `NONZERO_OFFSET_OUT_OF_BOUND`
- `AMBIGUOUS_WRONG_BY_K_OFFSET`
- `PIXEL_READBACK_FAILED`
- `BLANK_OR_STALE_PIXEL_PROOF`
- `RESOURCE_LIMIT_EXCEEDED`
- `LATE_CALLBACK_AFTER_TEARDOWN`
- `PROOF_TOKEN_REJECTED`

Phase 10 workflow/result behavior:

- Calibration state stores two image points and a known distance, but readiness
  always delegates to measurement-time `pixelsPerFoot()` validation.
- Color state stores HSV sample, clamped tolerance, and optional ROI. The ROI is
  clipped to frame bounds before detector use; missing sample, invalid HSV, bad
  frame dimensions, or empty ROI stays not-ready/no-read.
- Result formatting accepts only `MeasurementRunOutcome`. No calibration,
  color, preview, or readiness formatter can synthesize mph, launch angle,
  trajectory, carry, apex, or hang time.
- Phase 13 visual-estimate result formatting accepts only `VisualEstimateOutcome`.
  Estimate success lines are labeled `result=estimate`, include confidence,
  frame/detection counts, the timing basis (`REAL_PER_FRAME_TIMESTAMPS`,
  `VISUAL_FRAME_DELTA_INFERENCE`, or an `IMPORT_*` estimate timing basis),
  scale basis (`DISTANCE_CALIBRATION` or `BALL_DIAMETER_SELF_CALIBRATION`),
  residual, gap summary, and the calibrated-plane assumption. Ball-diameter
  self-calibrated estimates also disclose the apparent-diameter scale
  assumption. Imported estimates add import provenance assumptions. Estimate
  no-read lines never include mph, angle, trajectory, carry, apex, or hang time.
- Phase 14 adds a phone-operable estimate setup reducer. User setup selections
  are stored as normalized frame coordinates, invalidated when mode/readback/
  preview geometry changes, and transformed into active detection/readback
  space before calibration, ROI, color, and detector centroids are compared.
  Known-distance calibration is primary and uses the user-entered feet value
  plus two visible vertical A/B caliper lines. Known-ball-diameter
  self-calibration is a secondary fallback and displays setup-time
  ball-type/motion-blur assumptions before capture. Phase 16 adds the
  user-facing overlay and controls for this reducer: landscape live Camera2
  setup feed, display-rotation `TextureView` preview transform with an explicit
  aspect-preserving layout, draggable vertical caliper lines, a live-updating
  two-color bottom-right IMU-compensated level reference axis that freezes at capture start,
  hidden translucent bottom drawer, drawer-controlled coarse/fine line drag
  that draws from a local active-drag override and commits the final fraction
  at gesture end, editable distance in feet on the setup drawer page,
  on-screen drawer status for applied setup actions,
  tap-to-sample live ball color from preview pixels, ROI rectangle only while
  setup controls are visible, and full tool hiding while caliper drag remains
  active on the camera image. Failed live color sampling
  leaves color not-ready instead of using a hidden yellow default.
  The `shoot` voice command checks readiness, prints `Ready to shoot`, speaks
  `Ready`, plays three beeps, and then starts the direct 120 fps visual
  estimate path only when the setup readiness gate passes. The visible preview
  is required for setup, and if the preview surface is unavailable, the app
  reports `Live feed required` before starting capture. During direct capture,
  the camera is owned by the single hidden GL/readback target, so the preview may
  temporarily go black. Estimate frames still come only from GL/readback, and the
  setup preview restarts after completion, failure, or no-read.
  Successful estimates cover the preview with a black full-screen result
  overlay showing velocity, angle, and carry distance in large bold white text
  until the user taps `CLEAR`. The overlay is gated to current successful
  estimate-complete statuses. No-read and direct-capture failure outcomes show a
  non-destructive bottom report with reason/action/message and no mph, angle, or
  distance values, so the returned setup preview stays visible.
  Readiness-blocked `shoot` commands log `VOICE_SHOOT_NOT_READY` and display the
  specific setup reason instead of silently returning to listening.
- Synthetic timing proof remains in test source only and exists solely for JVM
  fixture coverage.
- Phase 11 is the direct-readback-to-12-frames effort. It targets more than 12
  raw direct readbacks, but token eligibility still requires at least 12
  whole-stream-clean frames after timestamp and pixel proof gates.

## Capture Modes

- 120 fps on S10+ records cleanly enough for record-then-decode investigation,
  but Phase 5/6 evidence keeps that route no-read for measurement until decoded
  frames can be paired to `SENSOR_TIMESTAMP` safely.
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
- Phase 12 record-then-decode source decision reads the back camera
  `SENSOR_INFO_TIMESTAMP_SOURCE` through a developer-only adb entry point. The
  S10+ reports `REALTIME`; a fresh count-independent value-match diagnostic
  then reports `AMBIGUOUS_MATCH`, `matched=248/257`, `ambiguous=62`, and
  `longestCleanRun=3`. The measured `1280x720 @ 120` route is a scoped no-go
  for production measurement.
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
- Phase 9 adds direct-source proof contracts. The companion-encoder shape is
  tried first, but the encoded scratch MP4 is only a session driver and is not
  decoded or read for proof. The runner validates consumed `SurfaceTexture`
  timestamps and same-update aggregate pixel signatures from the companion
  shape before any token eligibility exists, and rejects fewer than 12 consumed
  direct frames as `INSUFFICIENT_DIRECT_FRAMES`. The preview-only control runs
  only after companion teardown and scratch deletion, remains diagnostic-only,
  and cannot mint a proof token. The production direct-source token factory emits
  a bound direct measurement input; a naked direct token passed to the generic
  pipeline remains `UNPROVEN_TIMING`, and the bound input rejects any measured
  sequence whose count, relative timestamps, dimensions, or aggregate pixel
  signatures do not match the proven direct frames. Caller-settable sequence
  identity therefore cannot produce a speed. Testers can trigger the path with the debug-only
  `autoStartDirectProof120` adb extra; failures still render as no-read
  diagnostics with no path, pixel, speed, angle, or trajectory values.
- Phase 11 keeps the Phase 9 proof contract and changes the direct capture
  attempt from a one-frame stopgap to a 24-frame target. It logs bounded
  readback/release diagnostics and cadence/gap/final-gate data. The timestamp
  proof remains whole-stream fail-loud: interior near-duplicates, coalescing, or
  dropped-frame gaps reject the stream instead of being filtered into a passing
  12-frame subset.
