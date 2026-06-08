# Security Checklist

Run this on every code change before review and before commit.

## Secrets And Local Files

- [ ] No `.env`, `local.properties`, keystores, API keys, tokens, credentials, APKs, AABs, or private videos are committed.
- [ ] Logs do not expose private media paths except redacted display names in developer-only diagnostics.
- [ ] User-selected imported files are accessed through validated Android URI/content APIs.
- [ ] App-owned recorded clips stay app-private and saved/exported evidence does not store raw file paths.
- [ ] Successful app-owned recorded clips are deleted after estimate processing; failed debug recorded-HFR clips are retained only under the bounded app-private retention policy.
- [ ] Recorded-HFR processing does not persist or export raw decoded frames, thumbnail bytes, or media identifiers.

## Camera And Permissions

- [ ] Camera permission is requested only when needed and denied gracefully.
- [ ] Microphone permission is requested only for the hands-free voice trigger and denied gracefully.
- [ ] IMU level capture stores only a bounded still-phone snapshot, not raw sensor streams.
- [ ] Capture does not continue after lifecycle stop/pause without explicit intent.
- [ ] Burst duration and high-speed duty cycle are bounded.
- [ ] Fast-shutter Camera2 control is best-effort and falls back without extending capture duration.
- [ ] CameraDevice disconnect/error callbacks release all owned resources and
      complete with typed failure.
- [ ] The live setup feed releases its CameraDevice before recording/proof
      paths take ownership and on pause/stop/destroy.

## Input Validation

- [ ] User-entered calibration distances are finite, positive, and unit-validated.
- [ ] Editing calibration distance replaces the active value and invalid current
      distance cannot reuse stale feet for live or import estimates.
- [ ] HSV tolerances and ROI bounds are clamped, and recorded-HFR full-frame
      fallback remains bounded when ROI is absent or invalid.
- [ ] Video metadata and frame counts are validated before processing.
- [ ] Timestamp sequences are monotonic and reconciled to frames.

## Error Handling

- [ ] Measurement failures produce "No read" with actionable reason.
- [ ] Capture failures produce developer diagnostics/no-read with actionable reason.
- [ ] Readiness-blocked `shoot` commands expose the specific setup reason in UI
      and logs without raw frames, pixels, paths, or secrets.
- [ ] Exceptions that affect correctness are not swallowed.
- [ ] Defaults do not hide missing calibration, missing timestamps, or failed detection.

## Resource Management

- [ ] Camera sessions, MediaRecorder, MediaCodec, OpenCV Mats, GL textures, and file handles are released on all paths.
- [ ] Long-running processing is cancellable.
- [ ] Thermal/resource guards exist for sustained high-speed capture.
- [ ] Pure Kotlin frame processing has width, height, total-pixel, frame-count,
      threshold-pixel, connected-component, and operation-count caps.
- [ ] Recorded-HFR estimate processing has scanned-frame, candidate-blob,
      RANSAC-pair, proof-thumbnail, and cancellation bounds; resource no-read
      is explicit and does not rely on catching `OutOfMemoryError`.

## Supply Chain

- [ ] New dependencies are justified in the implementation plan.
- [ ] Native/OpenCV dependencies come from expected repositories.
- [ ] Build scripts do not download or execute unverified arbitrary scripts.

## Phase 8 Notes

- No media import path, OpenCV dependency, native dependency, private media path,
  APK, keystore, or credential is added by the Phase 8 pure pipeline foundation.
- Main source contains no concrete `MeasurementTimingProof`; every real source
  remains no-read until Phase 9 source proof exists.

## Phase 9 Notes

- Direct proof pixel signatures are aggregate hash/checksum/variation data only;
  raw tile pixels must not be logged or retained in diagnostics.
- Companion encoder scratch files are app-private cache data only, bounded by
  burst duration/size, deleted on terminal paths, and never decoded, imported,
  path-logged, or measurement-consumed.
- Direct GL readback is bounded by readback dimensions, frame count, and sample
  caps; callbacks after teardown are rejected and release failures remain
  fail-loud proof failures.
- The companion-first runner passes no file path to proof validation, runs no
  decoder or metadata reader, and prevents preview-control fallback after
  camera-busy, camera-open, or scratch-cleanup failures.
- Direct production timing tokens are accepted only through the bound
  `DirectSourceMeasurementInput`; the generic measurement path rejects naked
  direct tokens and `TimedFrameSequence` exposes no caller-settable proof
  identity fields.
- The bound direct measurement input rejects any measured sequence whose frame
  count, relative timestamps, dimensions, or aggregate pixel signatures do not
  match the proven direct frames.
- Token eligibility requires at least 12 consumed same-update direct frames plus
  passing timestamp and aggregate pixel-signature proof gates.
- The `autoStartDirectProof120` developer entry point exposes only bounded
  proof diagnostics and no-read UI on failure; it does not display scratch
  paths, raw pixels, mph, angle, or trajectory values.

## Phase 10 Notes

- User-entered calibration remains pure in-memory workflow state. Distances must
  be finite and positive, and measurement still revalidates
  `pixelsPerFoot()` before any result can exist.
- Color sampling remains pure in-memory workflow state. HSV values must be
  finite, saturation/value stay in `0..1`, tolerances are clamped, and ROI is
  clipped to frame bounds before detector use.
- Phase 10 adds no media import, OpenCV/native dependency, persistence,
  credential, file-picker, APK/AAB artifact, or private media path exposure.
- Result display is proof-gated: no-read states contain reason/action/message
  only; mph, angle, trajectory, carry, apex, and hang time appear only from a
  `MeasurementRunOutcome.Success`.
- Developer burst/decode/preview/direct proof diagnostics remain separate from
  production result state and cannot authorize success. Synthetic timing proof
  exists in test source only.
- Production remains no-read until a future direct-readback-to-12-frames phase
  can mint a Phase 9 bound direct input.

## Phase 11 Notes

- Direct readback diagnostics are bounded counts, timings, cadence/gap values,
  release-step names, and typed gates only. They must not expose raw pixels,
  scratch paths, private media paths, mph, angle, trajectory, APKs, keystores,
  or credentials.
- The companion MP4 remains app-private cache data only. It is not decoded,
  imported, path-logged, or measurement-consumed.
- The direct capture target is above the 12-frame token minimum, but token
  eligibility still requires whole-stream timestamp and pixel proof. Interior
  near-duplicates, coalescing, or dropped-frame gaps must reject the stream
  rather than being filtered into a passing subset.
- Camera/session/GL/recorder release steps are timed and logged by bounded step
  name so teardown blockers can be diagnosed without leaking file paths.

## Phase 14 Notes

- Phase 14 estimate setup remains in-memory reducer state. Normalized
  calibration points, color sample points, ROI, fallback ball diameter, and the
  single IMU level snapshot are not persisted or exported.
- Phase 16 calibration overlay controls write only normalized reducer state for
  vertical A/B caliper line positions, the user-entered distance in feet, the
  color point, sampled HSV value, and ROI movement. The overlay does not store
  preview screenshots, raw pixels, media identifiers, or device paths.
- The live camera overlay separates camera-use actions from application
  settings. The translucent bottom drawer writes only transient setup/capture
  state; permission, mode, distance, manual level, import, fallback, and
  recorder diagnostics live on the setup drawer page and still do not persist
  raw media, paths, endpoints, or device identifiers. Hiding the drawer leaves
  calibration gestures active without exposing additional data.
- App-level Setup Mode and Run Mode are transient UI state. Setup values remain
  in memory for repeated shots, while Run Mode command state stores only
  readiness labels such as manual-ready, listening, retrying, degraded, or
  setup-invalid reason. It must not store raw speech transcripts, frames,
  wireless-debugging endpoints, or pairing codes.
- Speech-recognizer no-match and speech-timeout events are treated as ordinary
  idle listening restarts, not fatal errors. Other restart paths are bounded by
  one delayed scheduler, backoff, and a consecutive-error cap. Logs include only
  bounded error codes/counts and command recognition markers; they must not
  include full raw alternatives or transcript text beyond existing bounded
  counts.
- Visual-estimate reports are process-lifetime attempt-scoped UI objects.
  Clearing a report stores at most the dismissed attempt id and must not persist
  media, raw pixels, private paths, endpoints, pairing codes, or secrets.
- Live color sampling copies only a small bounded patch from the setup feed,
  averages it immediately to HSV, recycles the bitmap, and leaves color
  not-ready if `PixelCopy` fails or the feed is unavailable. A failed sample
  must not silently fall back to a hidden default color.
- Direct visual-estimate diagnostics log frame/callback counts, unique sensor
  timestamp count, readback dimensions, and redacted session/no-go evidence
  only. They must not log raw frames, raw pixels, preview screenshots, ADB
  endpoints, pairing details, file paths, or device-private identifiers.
- Level capture prefers `TYPE_GRAVITY`, falls back to accelerometer, uses
  gyroscope movement to reject a bumped phone, and stores only roll/pitch,
  source, sample count, and capture time.
- Level-corrected launch angle remains disclosed as provisional until
  `S10_IMU_LEVEL_SIGN_VALIDATION_PENDING` physically verifies the correction
  sign. Estimates without level must disclose that angle is not tilt-corrected.
- Geometry changes clear known-distance calibration, color/ROI setup, and level
  before capture can arm, so stale setup cannot silently produce a plausible
  speed.
- Known-ball-diameter setup is labeled as a secondary estimate fallback and must
  disclose ball-type and motion-blur scale assumptions during setup.
- Estimate no-read UI remains value-free. Mph, angle, trajectory, carry, apex,
  and hang time are displayed only by `VisualEstimateOutcome.Success`.
- Visual-estimate proof thumbnails are bounded, in-memory diagnostic copies from
  the processed attempt frames. For Run Mode `shoot`, they come from bounded
  recorded-HFR decode after downscale to the detector working size; for the
  manual direct diagnostic path, they come from processed direct readback. They
  are owned only by the current attempt report, are dropped on clear/new
  attempt, and are not written to public storage or exported automatically.
- Proof logs contain counts only: captured frames, readback dimensions,
  callbacks, unique sensor timestamps, candidate frames/blobs, and selected
  samples. They must not log raw pixels, thumbnail bytes, image paths,
  screenshots, ADB endpoints, pairing codes, or speech transcripts.
- Detector/setup diagnostics must stay bounded and must not log raw pixels,
  private paths, device IPs, pairing ports, media files, APKs, keystores, or
  credentials.
- `scripts/security-check.sh` uses `scripts/secret-text.patterns` to reject
  repo-local wireless-debugging endpoints and pairing codes in tracked or
  nonignored untracked files.

## Phase 15 Notes

- Imported media must enter through Android's user-selected content URI picker;
  raw filesystem paths and unsupported schemes are rejected.
- The app does not persist imported content URIs in saved summaries or exports.
  Phase 15 takes no long-lived URI grant in the production import flow; grant
  lifecycle logic still releases retained grants on result/session deletion.
- Imported frame extraction is bounded by frame count, dimensions, and total
  pixels; the Android frame source releases retriever/extractor resources on
  success and no-read paths.
- App-owned recorded-HFR estimate processing streams one decoded frame at a
  time and scans every emitted in-window frame for the capture/drop count. The
  fixed-camera motion detector may retain only the bounded decoded impact
  window at the capped working size long enough to build the median luma
  background, then must drop full-frame ARGB and retain only compact
  blob/index/timestamp candidate records plus bounded downscaled proof
  thumbnails. Retained candidate count is detector evidence only and must not
  satisfy capture-side decoded/scanned/`SENSOR_TIMESTAMP` gates.
- Recorded-HFR candidate/reducer caps are configuration-scoped to the
  recorded-HFR path. Full-frame fallback without ROI is allowed only under the
  recorded-HFR scanned-frame, blob-count, total-candidate, RANSAC-candidate, and
  pair-hypothesis budgets; sibling live/import paths do not inherit these caps
  unless they explicitly opt in.
- The sound-triggered recorded-HFR motion detector may retain bounded luma
  medians, foreground masks, connected-component metrics, motion-candidate
  shape/foreground summaries, and low-resolution proof thumbnails only. It must
  not persist raw decoded frames or thumbnail bytes, and no foreground, global
  lighting/camera motion, oversized merged foreground, or ambiguous track must
  fail loud instead of producing a plausible speed.
- Recorded-HFR window decoding uses `MediaCodec` byte-buffer output and rejects
  unsupported or opaque layouts instead of falling back to render-surface plane
  APIs. The S10+ measured format is NV12-compatible
  `COLOR_FormatYUV420SemiPlanar` with explicit stride/slice-height handling.
- Sound-triggered recorded-HFR window routing treats the loud pop only as a
  timestamp marker. Raw PCM stays in memory, is never logged, persisted,
  exported, or included in proof artifacts, and only derived scalar diagnostics
  may be reported. Run Mode suspends `SpeechRecognizer` before `AudioRecord`
  starts so microphone ownership remains single-owner during the shot.
- Impact audio is processed as a chunk stream. The app does not persist raw PCM
  and no longer repeatedly copies/rescans the growing in-memory buffer. The
  provisional pop gates are field-tuned constants and must be validated against
  realistic ambient no-false-trigger proof before release.
- `BurstStopMode.ExternalStop` still receives a duration failsafe so a stalled
  audio marker path cannot keep Camera2/MediaRecorder running indefinitely.
- Sound-triggered window decoding must use bounded container-PTS windows and
  low-resolution proof thumbnails only. Optional proof fields may contain
  window times, anchor error, decode wall-clock, and source-validity verdicts,
  but not raw audio, raw decoded frames, media paths, thumbnails bytes, ADB
  endpoints, or pairing codes.
- Recorded-HFR source processing must close the Android frame source on
  success, no-read, resource-limit, cancellation, and decode-error paths. The
  app-owned MP4 remains app-private. Successful attempts are deleted after
  terminal handling; failed debug attempts may retain only matching
  `speed_ball_...mp4` files under the 3-file/25 MB cap, and release builds
  clean failed clips instead of retaining proof media.
- Imported clips are estimate-only. Clean monotonic container PTS is not strict
  timing proof, and import code must not construct
  `MeasurementRunOutcome.Success`.
- Saved summaries and export text contain redacted evidence only: source kind,
  timing basis, frame/detection counts, assumptions, optional estimate speed for
  successful estimates, and no-read reason. They must not contain raw pixels,
  raw video, private paths, content URIs, ADB endpoints, pairing codes, APKs,
  keystores, or credentials.
