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
  value-anchor investigation logging, Phase 7 preview timestamp proof, visual
  estimate mode, and Phase 15 import estimate/history/export surfaces.

Imported media now flows only through Android user-selected content URIs and
only to the estimate path. It cannot mint strict production measurement
success.
Camera HAL modes, SENSOR_TIMESTAMP diagnostics, decoded frame counts, PTS gap
diagnostics, preview timestamp proof diagnostics, sampled frame dimensions,
Phase 8/10 no-read result reasons, Phase 10 workflow readiness lines, import
estimate state, saved redacted summaries, and export evidence lines may
flow through the developer diagnostics or result-state surfaces only after real
enumeration/capture/decode/proof, pure workflow validation, or a typed failure.
Phase 6 anchor diagnostics flow only to bounded logcat lines and remain outside
the Compose result state. Phase 8/10 success is reachable only from test source
or a future Phase 9 bound direct input because main source contains no
production-minted generic `MeasurementTimingProof`.
Phase 13 adds a separate visual estimate result path. Estimate success is not a
strict measurement success, carries no proof token, and is displayed only as a
labeled estimate with confidence and diagnostics.

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

## Recorded-HFR 120 fps Path

Run Mode `shoot`, the Run Mode manual `Shoot` button, and the setup drawer
`Record 120` diagnostic use the app-owned MediaRecorder path. A constrained
high-speed 120 fps Camera2 session records an MP4, then the app decodes a
bounded frame set, runs detector trace/proof generation, and gates the result
against capture-side count/cadence proof before any estimate value may surface.

```text
MainActivity
  -> request CAMERA permission
  -> request RECORD_AUDIO only when the hands-free Voice control is enabled
  -> HighSpeedCamera enumerates back-camera Camera2 high-speed HAL ranges
  -> pure HighSpeedMode mapper validates fixed Range(fps,fps)
  -> developer UI selects default 720p@120 when available
  -> SpeechRecognizer listens for "shoot" in Run Mode after setup is valid
Camera2 constrained high-speed session
  -> offscreen companion preview surface + MediaRecorder surface for Run Mode
  -> best-effort 1/1000s Camera2 manual exposure request, with AE fallback
  -> fixed timer stops the recording automatically
  -> capture callback SENSOR_TIMESTAMP list
  -> BurstDiagnostics unique-count floor + median-gap band
  -> saved burst in app-specific external files
  -> app-owned MP4 is passed directly to recorded estimate processing
  -> redacted metadata and decoded sample count probe
  -> detector working resolution selection, capped at 1280x720
  -> streaming `AndroidImportVideoFrameSource.nextFrame()` decode loop
  -> increment scanned decoded-frame count for every pulled frame
  -> existing HSV/blob detector while full-frame pixels are local
  -> discard full-frame ARGB immediately after detection
  -> retain only bounded candidate blob/index/timestamp records plus bounded thumbnails
  -> VisualEstimateCandidateReducer reuses the existing straight-hit/RANSAC selector
  -> ImportTimingReconciliation with RECORDED_CAPTURE_FRAME_INTERVAL from retained original decoded frame indexes
  -> skip non-hit leading frames, filter tiny speckles, and use RANSAC-style
     consensus to select a short high-velocity one-directional straight
     yellow-ball motion window
  -> VisualEstimateCaptureProofBuilder bounded thumbnails from retained recorded-HFR proof frames
  -> RecordedHfrCaptureGate metadata sample count == scanned decoded-frame count == unique SENSOR_TIMESTAMP count
  -> RecordedHfrCaptureGate sensor-cadence/capture-proof pass
  -> VisualEstimateOutcome estimate success or no-read with proof
  -> MeasurementResultUiState.EstimateOutcome UI formatting
  -> SavedResultSummaryStore app-private redacted RECORDED_ESTIMATE summary
  -> ImportEvidenceExporter redacted RECORDED_ESTIMATE text export
  -> app-owned MP4 deleted after processing
```

The sound-triggered recorded-HFR window workstream now has batch-1 foundations
only; `shoot` is not yet routed through it. The new pure path is:

```text
AudioRecord timestamp anchor from getTimestamp framePosition/nanoTime
  -> ImpactAudioTrigger detects the first loud transient marker only
  -> first positive Camera2 SENSOR_TIMESTAMP is the video anchor
  -> AudioVideoClockAnchor maps both anchors to elapsedRealtimeNanos
  -> ImpactWindowMapper computes container-time windowStartUs/windowEndUs
     with sensor impact frame index retained as diagnostic only
  -> RecordedHfrWindowFrameSource emits only frames with container PTS inside
     the requested window and enforces maxFrames/strictly increasing PTS
  -> RecordedHfrWindowCaptureGate accepts a bounded decoded subset while
     keeping the full-burst scanned==metadata==sensor gate unchanged
  -> RecordedHfrStreamingEstimate may use CONTAINER_PTS_DELTAS timing mode for
     retained sound-window candidates
  -> VisualEstimateCaptureProof can carry optional window/anchor/decode/source
     validity fields for the attempt report
```

This foundation still requires batch-2 Android recorder/microphone orchestration,
bounded MediaExtractor/MediaCodec implementation, and S10+ device proof before
it can be claimed as the live Run Mode route.

If the recorded-HFR gate fails, the detector trace/proof may still be shown as
imagery and counts, but the terminal outcome is no-read and no mph/angle/carry
value is displayed.

The older decode proof branch remains diagnostic-only:

```text
app-owned burst MP4
  -> MediaExtractor reads exact output File video samples and PTS list
  -> bounded frame proof samples two non-adjacent decoded frames
  -> exact-count reconciliation pairs decoded frame index i to SENSOR_TIMESTAMP i
  -> fail loud on count mismatch, cadence mismatch, dropped gaps, or near-duplicates
  -> optional Phase 6 value-anchor diagnostics on near-duplicate/count mismatch
  -> TIMESTAMP_ANCHOR_* logcat lines only; never DecodeOutcome.Success
```

Phase 5 implements the path through decode/frame-timestamp reconciliation. Phase
6 implements investigation-only value-anchor diagnostics after selected fail-loud
decode paths. A diagnostic `Proven` anchor is not a measurement-ready pairing,
and the S10+ device proof for anchor behavior now rejects fail-loud with
`SENSOR_NEAR_DUPLICATE` after a real burst/decode run (`N=268`, exact distinct
sensor timestamps `325`, hypothetical post-collapse sensor count `260`).
Phase 12 adds a developer-only `SENSOR_INFO_TIMESTAMP_SOURCE` characteristic
read. On the S10+ back camera it reports `REALTIME`, which supports shared-clock
analysis for exposed frame timestamps. Phase 12 then runs a count-independent
PTS-to-sensor value-match against a fresh S10+ burst; the best measured offset
is `AMBIGUOUS_MATCH` with `248/257` decoded samples matched, `62` ambiguous
matches, and only a `3`-frame longest contiguous unambiguous run. The measured
S10+ record-then-decode route therefore remains a scoped no-go for production
measurement. Strict production measurement and 240 fps GPU proof remain
planned. Visual estimate setup now includes a landscape live Camera2 feed with
a compact Compose overlay. The preview reads Camera2 sensor orientation
relative to the current display rotation, rotates the `TextureView` buffer into
screen coordinates, and preserves the oriented camera aspect ratio instead of
stretching the image. Draggable
vertical A/B caliper lines, the user-entered distance in feet, a tap-sampled
live ball color point, and the ROI rectangle feed the same reducer used by live
and import estimate paths. Editing the distance field overwrites the active
known-distance feet value while preserving A/B points; invalid distance text is
stored as not-ready state and cannot arm live capture or feed import
calibration as a stale older value. Import mode is estimate-only and uses the
existing detector/calibration/estimate pipeline.

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

## Phase 13 S10+ Visual Estimate Path

Setup Mode owns camera permission, selected high-speed mode, A/B caliper
positions, raw distance text and parsed feet, color sample, ROI, level reference,
and fallback/import setup. Run Mode consumes that setup but does not edit it as
the normal surface. Entering Run Mode and every `shoot` re-checks the same setup
gates; invalid setup produces a specific run-state reason and does not start
capture.

The setup drawer button labeled `Est 120` starts the direct visual-estimate
diagnostic path. Run Mode `Shoot` and the readiness-gated voice command `shoot`
start the recorded-HFR path above. The direct diagnostic path uses the selected
fixed 120 fps Camera2 high-speed mode, but it does not create a MediaRecorder
MP4 or decode a file; it reads app-owned frames through the direct GL/readback
surface and runs the live estimate pipeline. The setup preview surface is
required before setup can arm so the user can align distance, ROI, color,
calipers, and level, but it is not a direct-capture target.
`VisualEstimateFramePipeline` receives only GL/readback frames. The visible
preview may go black while the bounded direct capture owns the camera; the setup
preview is restarted after completion, failure, or no-read.

```text
DirectVisualEstimateCapture
  -> snapshot bounded DirectArgbFrame list
  -> convert same frame copy to RgbFrame list
  -> VisualEstimateFramePipeline.estimateFromFramesWithTrace
  -> estimator-owned BlobDetector trace and selected samples
  -> VisualEstimateCaptureProofBuilder bounded thumbnails
  -> clear full DirectArgbFrame/RgbFrame buffers
  -> DirectVisualEstimateCaptureOutcome with bounded proof only
  -> attempt-scoped VisualEstimateReport
```

Capture proof is one-attempt-deep and in memory by default. It contains
low-resolution thumbnail pixels, ROI/candidate/selected overlays, readback or
recorded-source dimensions, scanned decoded-frame count, retained candidate
counts, frame callback counts, unique sensor timestamp count, and bounded
detector counts. It does not contain mph, angle, distance, raw media paths,
full-resolution recorded frames, or a strict timing-proof token. No-read and failure
reports use the proof to explain whether zero frames, zero candidates, too few
candidate frames, too few selected samples, or recorded-source count/cadence
gates caused the attempt to fail loud.
On success, estimate result formatting includes speed, launch angle, and a
carry-distance estimate computed through the existing trajectory simulator.
The camera UI presents those values in a black full-screen result overlay only
while the current capture status is a successful estimate-complete state. Ready
states and active capture do not show that result overlay. No-read and direct
failure outcomes produce a typed report from `VisualEstimateOutcome.NoRead` and
render it as a non-fullscreen bottom panel with reason/action/message and no
mph, angle, or distance values while the setup preview holder remains present.
Readiness-blocked `shoot` commands do not start capture; they show and log a
specific `VOICE_SHOOT_NOT_READY` reason from calibration/color/ROI/level
readiness state.

Run command state is explicit and separate from capture status:

```text
Setup Mode state
  -> SpeedBallAppMode.Setup
  -> raw distance text + parsed readiness
  -> valid setup gates
  -> SpeedBallAppMode.Run
  -> SpeedBallRunCommandState.ManualReady / Listening / VoiceRetrying /
     VoiceUnavailable / VoiceError / Capturing / Reporting / SetupInvalid
```

Green listening is set only from `onReadyForSpeech` after setup is still valid.
Speech recognizer `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` are idle-listening
events, so they clear the consecutive-error count and schedule another listen
window without degrading voice. Other recognizer errors use one delayed restart
runnable, bounded backoff, and a consecutive-error cap before degrading to
manual-ready/voice-error state. Manual Run Mode `Shoot` remains available
whenever setup is valid, including voice unavailable/error/retrying states, but
voice remains the primary hands-free command path.

```text
MainActivity / DirectVisualEstimateCapture
  -> begin monotonic visual-estimate attempt id
  -> Camera2 constrained high-speed direct GL/readback SurfaceTexture target
  -> SurfaceTexture.updateTexImage()
  -> bounded GL readback to DirectArgbFrame
  -> TimedFrameSequence from app-owned full-frame/ROI RGB frames
  -> bounded frame/count/pixel validation
  -> real per-frame timestamps preserved when valid
  -> otherwise visual frame-delta inference with a known per-frame interval
  -> HSV/blob detection in the ROI
  -> VisualEstimateTrackSample list with centroid and apparent diameter
  -> timestamp-gap summary from real or inferred frame gaps
  -> displacement-vs-timestamp coherence gate for skipped/coalesced intervals
  -> measurement-time DistanceCalibration revalidation, or explicit
     known-ball-diameter self-calibration when distance calibration is absent
  -> VelocityMeasurementCalculator with estimate residual threshold
  -> confidence/no-read gates
  -> VisualEstimateOutcome.Success or VisualEstimateOutcome.NoRead
  -> typed VisualEstimateReport(attemptId, kind, lines, dismiss key)
  -> success-only ResultOverlay OR non-destructive no-read/failure bottom panel
  -> CLEAR dismisses only that attempt id
```

`updateShellState()` does not derive report visibility from volatile status
strings. It receives the currently owned attempt report, excluding an attempt id
that has been cleared. Setup edits, status changes, distance edits, live-feed
restarts, and voice-listener updates therefore cannot resurrect the same old
report after `CLEAR`; the next shot gets a new attempt id and can show a new
report.

This path is for the S10+ personal estimate mode, not certified measurement.
Real per-frame timestamps anchor absolute time when available. If a frame
source lacks usable timestamps but the source has a known per-frame interval,
the app may infer frame spacing from ordered centroid displacement and label
the result with `VISUAL_FRAME_DELTA_INFERENCE`. That path carries a first-class
assumption that the smallest observed frame gap is one native interval; if every
observed gap was uniformly coalesced, speed would be biased high. When both
timestamps and displacement evidence exist, visual displacement can validate relative
skipped/coalesced intervals: if a later centroid jump is about five times the
normal displacement and the real timestamp gap is also about five times the
normal gap, the estimate may proceed with lower confidence and a
skipped/coalesced diagnostic. If timestamp gaps and displacement disagree, the
track no-reads as ambiguous. Strong apparent ball size change normally no-reads
because it indicates depth motion and violates the calibrated-plane assumption.
If distance calibration is absent, the estimate path can infer `pixelsPerFoot`
from a reviewed known ball diameter plus the median detected apparent short-axis
diameter, avoiding the motion-blurred long axis for fast balls. In that
ball-diameter self-calibration path only, apparent scale change can proceed as
a LOW-confidence warning rather than a strict no-read.
That result is labeled with `BALL_DIAMETER_SELF_CALIBRATION` and carries a
first-class assumption that the entered ball type must match the real ball and
that motion blur can bias scale. Raw pixels are consumed only in memory by the
detector and do not flow into result payloads.

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
The direct GL readback helper has two explicitly labeled consumer models. The
inline model consumes each accepted `SurfaceTexture` callback with one
`updateTexImage()` call, records that same consumed-frame timestamp, draws the
external OES texture into a bounded pbuffer, and immediately converts
`glReadPixels` output into the aggregate pixel signature. The PBO model is a
separate GLES3 path: it issues `glReadPixels` into a pixel-pack buffer for the
current callback and maps the previous callback's PBO on the next callback, so
CPU readback is not serialized before the BufferQueue can advance. The frame
collector stores only atomic timestamp/signature proof records, rejects late
callbacks after teardown, and fails loud on frame/sample resource caps.
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

## Phase 12 Source-Route Variant Model

```text
DirectProofVariant
  -> variant id + session shape + consumer model
  -> ordered Camera2 surface roles
  -> constrained-high-speed or standard-session flag
  -> request template, ImageReader format/usage, and ImageReader maxImages when applicable
  -> capture diagnostics record variant, surface order, direct buffer, request list
  -> producer/capture callback cadence gate
  -> consumer-ratio interpretation only after standard-session producer cadence is in band
```

The implemented GL capture path accepts explicit variants for the existing
companion+direct order, a direct-first surface-order control, and a direct-only
GL target. The surface-role helper is pure-testable and the Camera2 session uses
the requested order when building constrained high-speed surfaces and request
targets. The PBO GL readback variant is exposed as `pbo-gl-readback`; it is not
an alias for the inline `SurfaceTexture`/`glReadPixels` model and still requires
review before it can satisfy the no-go-blocking route. The S10+ device run
accepted the PBO route but still consumed about 30 fps, so the route remains
fail-loud no-read. The ImageReader proof path is currently a same-snapshot contract for
YUV images: one acquired image supplies both timestamp and bounded tile pixels,
the tile is converted to ARGB, the shared aggregate pixel-signature builder is
used, and the snapshot is closed. Raw planes and full-frame pixels are not
retained. The constrained YUV ImageReader variant uses constrained high-speed
session creation; the constrained PRIVATE/video-encode ImageReader variant
tests the preview/encoder-like buffered surface class but cannot mint proof
without delivered image callbacks and a reviewed `HardwareBuffer` proof path.
The standard ImageReader variant uses a standard Camera2 repeating request and
must prove producer cadence is in band before consumer-ratio evidence can be
interpreted.

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
  -> ImportContentAccess validates content:// and user selection
  -> MediaMetadataRetriever / MediaExtractor probe redacted metadata and PTS
  -> bounded indexed frame extraction through AndroidImportVideoFrameSource,
     with timestamp extraction fallback
  -> ImportFrameExtractor enforces frame/dimension/pixel caps and releases source
  -> ImportTimingReconciliation from monotonic container PTS as estimate
     evidence, or a known capture frame interval for slow-motion estimate imports
  -> optional setup-time LevelReferenceSnapshot corrects launch angle when present
  -> existing HSV/blob detector, calibration, and VisualEstimateFramePipeline
  -> skip non-hit leading frames, filter tiny speckles, and select a short
     high-velocity one-directional straight yellow-ball motion window
  -> VisualEstimateOutcome estimate success or no-read
  -> MeasurementResultUiState.EstimateOutcome UI formatting
  -> SavedResultSummaryStore app-private redacted summary
  -> ImportEvidenceExporter redacted text export
```

Container PTS is never strict timing proof in Phase 15. A clean monotonic
imported clip remains `IMPORT_ESTIMATE` or no-read, app-owned voice recordings
remain `RECORDED_ESTIMATE` or no-read, and import code does not construct
`MeasurementRunOutcome.Success`. The import adapter replaces the internal
live-timestamp diagnostic label with source-specific estimate timing bases
before UI/export sees the result, and propagates reconciler confidence and
assumptions into `VisualEstimateDiagnostics`. Saved summaries and exports
contain source kind, timing basis, frame/detection counts, assumptions, no-read
reason, and success estimate values only when the outcome is a successful
`VisualEstimateOutcome`. They do not contain the content URI, filesystem path,
raw video, raw pixels, ADB endpoints, or pairing codes.
Slow-motion imports may use a known capture interval instead of playback PTS;
that route remains estimate-only and discloses that an incorrect declared
interval or uniform frame loss is not internally detectable.
Import hit-ball selection combines a minimum calibrated speed gate, same
horizontal direction, straight-window residual/slope-change gates, and a
minimum blob area so slow pitch/toss arcs and static color speckles do not
produce plausible mph values.

## Calibration

Color calibration produces HSV bounds. Distance calibration produces
`pixelsPerFoot` for strict measurement. Estimate mode also supports an explicit
known-ball-diameter self-calibration fallback when distance calibration is
absent and apparent short-axis ball diameter is detected in enough frames. Any
scale input is revalidated at measurement time rather than trusted from stale
state.

Phase 14 estimate setup stores vertical caliper line positions, the entered
distance in feet, color sample point, sampled HSV value, and ROI as normalized
frame coordinates after preview scale and sensor-rotation transform handling.
It also stores a setup-time
`LevelReferenceSnapshot` from the phone IMU: `TYPE_GRAVITY` is preferred,
accelerometer is the fallback, and gyroscope movement during an explicit still
snapshot rejects the reading. During setup, the app also observes gravity live
and refreshes the stored level reference so the on-screen horizon/vertical guide
compensates for phone roll and stays aligned to true horizontal/vertical while
the user tilts the phone. Voice-triggered recording and direct capture stop that live observer first, freezing the last pre-capture
reference for the shot. Phase 16 renders those normalized selections as a
user-facing overlay on the live Camera2 feed: two vertical A/B caliper lines,
an explicit display-rotation `TextureView` transform with aspect-preserving
layout, a bottom-right two-color IMU level reference axis, a hidden
translucent bottom drawer for camera-use commands, and a setup drawer page
for configuration commands that do not belong in the live camera operation
controls. Both drawer pages show a compact status row sourced from
`SpeedBallShellState.captureStatus` plus the active setup target, so applied
setup changes and not-ready conditions are visible on the camera screen. Only
one drawer page is visible at a time; hiding the drawer leaves caliper drag and
preview taps active on the camera image. Caliper drag does
not choose the nearest line: the selected A/B target owns every drag anywhere
on the preview. Coarse and fine are both smooth relative drags; coarse applies
the full drag delta, while fine applies a much smaller scaled delta. Both
clamp at the preview edges rather than refusing edge movement. The UI keeps
the drag recognizer stable for the full gesture and draws the selected line
from a local active-drag override, then commits the final fraction to app state
when the gesture ends. This keeps the line visually tied to the finger instead
of restarting pointer input or waiting on parent shell recomposition for every
drag step.
The reference axis is drawn only from a captured `LevelReferenceSnapshot`; no
screen-horizontal fallback is drawn. The color sample target, ROI rectangle,
and level/horizon line are shown only while setup controls are visible.
Target-selection, line drag gestures, color/ROI feed taps, and the drawer
coarse/fine precision toggle update the same reducer state. Caliper drags use
the Captain-style fraction update over the visible preview width because the
vertical A/B lines are display controls that must reach both preview edges.
Color and ROI taps still convert through `PreviewFrameTransform`, where
letterbox and camera-buffer mapping matter. Color
sampling uses `PixelCopy` on a small bounded live-feed patch,
averages it to HSV, and fails closed if the feed or copy is unavailable; no raw
preview pixels, sensor streams, or media paths are stored. At capture time the
active mode/readback geometry
transforms those selections into the detection/readback coordinate space before
`CalibrationWorkflowState`, `ColorWorkflowState`, `BlobDetector`, and
`VisualEstimatePipeline` consume them. The level snapshot is passed into the
estimate config so image-space launch angle is corrected against true
horizontal. The correction sign is documented in `LevelReference.kt` and is
reported as provisional until `S10_IMU_LEVEL_SIGN_VALIDATION_PENDING` closes; if
no level exists, estimate diagnostics disclose that launch angle is not
tilt-corrected. A mode, readback, or preview-transform identity change clears
known-distance calibration, color/ROI setup, and level so stale setup cannot
arm. The P14-G2-A golden covers the full composed chain: preview-space
calibration plus readback-space ball motion plus known delta time must produce
the expected mph through real estimate logic, including a non-square/aspect
ratio case.
The `shoot` voice command is a readiness-gated recorded-HFR command: it checks
the same Phase 14 `canArm()` gate used by setup, prints `Ready to shoot`, speaks
`Ready`, plays three short beeps, and then starts the recorded-HFR path. It must
not route through `startDirectVisualEstimate()` on S10+. If readiness fails, the
status row reports the specific condition, logcat records
`VOICE_SHOOT_NOT_READY`, and listening continues. The preview surface is a setup
aid only. Recorded capture can use an offscreen companion preview surface, and
the setup feed restarts after completion, failure, or no-read.
