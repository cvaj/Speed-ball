# Gate 2 Subtask Queue: Recorded-HFR Field Proof Recovery

Date: 2026-06-07

Gate 1 plan:

- `docs/IMPL_PLAN_2026-06-07_recorded_hfr_field_proof_recovery.md`

Workstream:

- `recorded-hfr-field-proof-recovery`

## Non-Negotiable Constraints

- Do not reintroduce `ImageReader`, `MediaCodec.getOutputImage()`,
  `Image.getPlanes()`, `.planes`, `Bitmap`, or whole-window ARGB frame lists in
  the recorded-HFR window route.
- Do not decode the whole MP4 looking for the ball.
- Do not delete the only failed-attempt artifact before proof UI/logs can show
  what happened.
- Do not show mph from timeout, black/underexposed frames, excessive exposure
  blur risk, bad calibration, invalid source-to-working transform, insufficient
  detections, invalid timestamps, unsupported decoder output, or failed source
  validity.
- Retained media is debug-only, bounded, app-private, sanitized by display name,
  and never committed.

## Subtask 1: Recorded-HFR Retention Policy

Create a small app-layer retention helper for recorded-HFR estimate attempts.

Requirements:

- Retain the last failed recorded-HFR estimate MP4 for:
  - decode timeout;
  - partial-timeout proof;
  - black/source-invalid no-read;
  - decoder/source failure;
  - capture-proof failure;
  - no-impact after a recording was created.
- Delete successful estimate MP4s after proof/report is built unless a debug
  retain flag is explicitly set.
- Delete older retained failed attempts beyond both:
  - count cap: 3 files;
  - total byte cap: 25 MB.
- Scope cleanup to app-owned recorded-HFR MP4 names only:
  `speed_ball_<width>x<height>_<fps>_<timestamp>.mp4`.
- Log only sanitized display names and byte counts.
- Keep release builds cleanup-first unless `BuildConfig.DEBUG` is true.

Tests:

- Retention keeps the failed file on timeout/no-read under cap.
- Success cleanup deletes the file.
- Cleanup deletes oldest matching retained files when count or byte cap is
  exceeded.
- Cleanup ignores unrelated files and directories.
- Release-mode policy does not retain failed files.

## Subtask 2: Exposure Contract And Diagnostics

Change HFR burst default exposure behavior so Run Mode is visible by default and
does not force the 1 ms shutter unless explicitly requested.

Requirements:

- Change `BurstOptions.preferredExposureTimeNanos` default to `null`.
- Keep explicit manual exposure support:
  - when non-null, `CONTROL_AE_MODE_OFF` and `SENSOR_EXPOSURE_TIME` are set;
  - when null, AE remains enabled and no manual sensor exposure is set.
- Capture actual exposure from `CaptureResult.SENSOR_EXPOSURE_TIME` when present.
- Add bounded exposure diagnostics to `BurstDiagnostics`:
  - requested exposure mode: `AE` or `MANUAL`;
  - requested exposure nanos when manual;
  - actual exposure sample count;
  - actual min/median/max exposure nanos.
- Log requested and actual exposure summaries in `RECORDED_HFR_CAPTURE_SUCCESS`.
- Carry exposure summary into the recorded-HFR proof/report lines.

Blur-risk fail-loud rule:

- Gate 2 implementation must define `RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS`
  initially at `2_000_000L` (2 ms).
- If actual median exposure is available and exceeds this threshold, a speed
  estimate must no-read with a motion-blur-risk message unless the route is
  explicitly marked diagnostic-only with no mph.
- If actual exposure is unavailable, report it as unavailable in proof/logs and
  do not use that absence as success evidence.

Tests:

- `BurstOptions` default exposure is `null`.
- Default request does not contain manual exposure settings.
- Explicit manual exposure still applies `AE_MODE_OFF` and sensor exposure.
- Exposure diagnostics compute min/median/max correctly.
- Recorded-HFR estimate no-reads when actual median exposure exceeds the blur
  threshold, without mph leakage.
- Proof/report lines include exposure summary or unavailable exposure.

## Subtask 3: Recorded-HFR Working Coordinate Space

Reduce recorded-HFR window processing cost while preserving measurement scale.

Requirements:

- Change recorded-HFR window working resolution selection:
  - 1280x720 source -> 640x360 working;
  - 1920x1080 source -> 640x360 working for this route;
  - smaller sources keep source dimensions when valid.
- Add an explicit source-to-working transform contract for recorded-HFR:
  - source width/height;
  - working width/height;
  - x/y scale;
  - transformed calibration points or transformed `pixelsPerFoot`;
  - transformed ROI;
  - transformed detector area gates;
  - transformed max-frame-jump.
- Fail loud if source-to-working dimensions are invalid, non-finite, or any
  transformed setup component is outside the working frame.
- Keep source dimensions and working dimensions visible in proof/report.

Tests:

- 1280x720 and 1920x1080 select 640x360.
- Source-to-working transform scales `pixelsPerFoot`, ROI, min/max blob area,
  and max-frame-jump consistently.
- Invalid/non-finite transform returns no-read.
- Golden scale-invariance test:
  - build the same synthetic ball-motion fixture at 1280x720 and 640x360;
  - transform calibration/ROI/gates/max-jump to each working space;
  - run the real visual estimate pipeline;
  - assert mph matches within a tight tolerance.

## Subtask 4: Partial Timeout Proof And Source Validity

Ensure timeout/no-read reports show the decoded frames already seen.

Requirements:

- Refactor `RecordedHfrStreamingEstimate` or its call site so partial decoded
  frames can produce proof thumbnails even when the source later times out.
- A timeout after at least one emitted frame must return a no-read with:
  - proof thumbnails;
  - decoded frame count;
  - sync-prefix count;
  - decode wall-clock millis;
  - source-validity verdict;
  - no mph.
- Run source-validity over partial frames before returning timeout no-read.
- If partial frames are black/near-black, report source invalid/underexposed
  ahead of generic timing failure.
- If timeout happens before the first emitted frame, return a timing/resource
  no-read and retain the MP4 for inspection.

Tests:

- Source emits partial non-black frames then timeout -> no-read with proof
  thumbnails and timeout diagnostics.
- Source emits partial black frames then timeout -> no-read with
  source-validity `NO_READ_BLACK_OR_INVALID_SOURCE` or equivalent.
- Timeout before first frame -> no proof thumbnails but retained MP4 policy is
  invoked.
- All timeout paths leak no mph.

## Subtask 5: Decode Diagnostics And Sync-Prefix Lever

Make performance failure mode explicit and prepare the fallback if downscale is
not enough.

Requirements:

- Add or preserve log/report fields:
  - decoded in-window frames;
  - sync-prefix frames;
  - first/last in-window PTS;
  - decode wall-clock millis;
  - timeout boolean;
  - source/working dimensions;
  - source-validity verdict;
  - exposure summary.
- Device proof must include at least one `RECORDED_ESTIMATE_WINDOW` line with
  `timedOut=false`.
- If device proof still times out after 640x360 and AE/exposure fixes, do not
  declare the feature complete. Implement or plan the conditional sync-prefix
  mitigation:
  - set recorder keyframe/GOP interval when Camera2/MediaRecorder exposes a
    supported knob;
  - prove sync-prefix count drops in logs;
  - keep the same no-ImageReader/no-whole-clip constraints.

Tests:

- Diagnostic log builder includes every required field.
- Validator rejects timeout even when proof thumbnails exist.
- Source guards still reject forbidden decoder APIs and full-window ARGB lists.

## Subtask 6: UI Report Lines

Make no-read reports actionable for the user.

Requirements:

- Measurement result/report lines must distinguish:
  - recorded nothing/no file;
  - recorded black/underexposed;
  - exposure too long/motion blur risk;
  - decode timeout;
  - no ball candidates;
  - insufficient detections;
  - bad timing/window.
- Proof panel must remain visible for no-read when proof frames exist.
- Clear dismisses the current report only. It does not delete the retained debug
  artifact for that attempt.

Tests:

- Report lines include source/working dimensions, decoded count, source validity,
  exposure summary, and retention/debug artifact status where applicable.
- Clear hides the report and does not mutate setup readiness.
- No-read report never contains mph.

## Subtask 7: Docs And Security

Update docs in the same change.

Required docs:

- `docs/HOW_THE_APPLICATION_WORKS.md`;
- `docs/DATA_FLOW.md`;
- `docs/FUNCTIONAL_TEST_REGISTRY.md`;
- `docs/HOW_TO_RUN.md`;
- `docs/SECURITY_CHECKLIST.md`.

Only update `docs/HIGH_SPEED_FINDINGS.md` after measured device evidence is
captured.

Security checks:

- Retained videos are app-private debug diagnostics.
- Logs use sanitized display names, not private absolute paths.
- No media, APKs, keystores, `local.properties`, secrets, or endpoint codes are
  committed.

## Subtask 8: Verification And Device Proof

Local checks before implementation review:

```bash
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:assembleDebug --rerun-tasks --no-daemon
pnpm docs:check
pnpm security:check
git diff --check
```

S10+ proof after implementation review closure:

1. Install debug APK.
2. Launch normally; verify setup preview is visible.
3. Run controlled `autoStart120` indoors under AE; pull retained/debug MP4 and
   sample frames. Frames must be non-black or report underexposure with proof.
4. Run speech-triggered `shoot` with loud pop and visible ball/object crossing
   ROI.
5. Verify logs:
   - `VOICE_SHOOT_COMMAND`;
   - `RECORDED_HFR_AUDIO_ARMED`;
   - `RECORDED_HFR_START`;
   - `RECORDED_HFR_READY_CUE_EMITTED`;
   - `RECORDED_HFR_IMPACT_DETECTED`;
   - `RECORDED_HFR_CAPTURE_SUCCESS`;
   - `RECORDED_ESTIMATE_WINDOW ... timedOut=false`;
   - terminal complete/no-read with proof.
6. Verify UI proof thumbnails appear for no-read when any frame was decoded.
7. Verify actual exposure summary appears in logs/report.
8. If `timedOut=true` remains, stop and use Subtask 5's sync-prefix lever
   instead of claiming completion.

## Implementation Order

1. Retention helper and tests.
2. Exposure default/diagnostics and tests.
3. Working-coordinate transform and scale-invariance golden tests.
4. Partial-timeout proof/source-validity changes and tests.
5. Diagnostics/report lines and tests.
6. Docs/security updates.
7. Full local checks.
8. Implementation review request.
9. Install/device proof after review closure.
