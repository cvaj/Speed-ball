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

## Input Validation

- [ ] User-entered calibration distances are finite, positive, and unit-validated.
- [ ] HSV tolerances and ROI bounds are clamped.
- [ ] Video metadata and frame counts are validated before processing.
- [ ] Timestamp sequences are monotonic and reconciled to frames.

## Error Handling

- [ ] Measurement failures produce "No read" with actionable reason.
- [ ] Exceptions that affect correctness are not swallowed.
- [ ] Defaults do not hide missing calibration, missing timestamps, or failed detection.

## Resource Management

- [ ] Camera sessions, MediaRecorder, MediaCodec, OpenCV Mats, GL textures, and file handles are released on all paths.
- [ ] Long-running processing is cancellable.
- [ ] Thermal/resource guards exist for sustained high-speed capture.

## Supply Chain

- [ ] New dependencies are justified in the implementation plan.
- [ ] Native/OpenCV dependencies come from expected repositories.
- [ ] Build scripts do not download or execute unverified arbitrary scripts.
