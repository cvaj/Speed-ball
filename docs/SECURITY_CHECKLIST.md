# Security Checklist

Run this on every code change before review and before commit.

## Secrets And Local Files

- [ ] No `.env`, `local.properties`, keystores, API keys, tokens, credentials, APKs, AABs, or private videos are committed.
- [ ] Logs do not expose private media paths except in developer-only diagnostics.
- [ ] Imported files are accessed through validated Android URI/content APIs.

## Camera And Permissions

- [ ] Camera permission is requested only when needed and denied gracefully.
- [ ] Capture does not continue after lifecycle stop/pause without explicit intent.
- [ ] Burst duration and high-speed duty cycle are bounded.
- [ ] CameraDevice disconnect/error callbacks release all owned resources and
      complete with typed failure.

## Input Validation

- [ ] User-entered calibration distances are finite, positive, and unit-validated.
- [ ] HSV tolerances and ROI bounds are clamped.
- [ ] Video metadata and frame counts are validated before processing.
- [ ] Timestamp sequences are monotonic and reconciled to frames.

## Error Handling

- [ ] Measurement failures produce "No read" with actionable reason.
- [ ] Capture failures produce developer diagnostics/no-read with actionable reason.
- [ ] Exceptions that affect correctness are not swallowed.
- [ ] Defaults do not hide missing calibration, missing timestamps, or failed detection.

## Resource Management

- [ ] Camera sessions, MediaRecorder, MediaCodec, OpenCV Mats, GL textures, and file handles are released on all paths.
- [ ] Long-running processing is cancellable.
- [ ] Thermal/resource guards exist for sustained high-speed capture.
- [ ] Pure Kotlin frame processing has width, height, total-pixel, frame-count,
      threshold-pixel, connected-component, and operation-count caps.

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
