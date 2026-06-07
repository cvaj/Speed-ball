# Gate 2 Subtask Queue: Sound-Triggered Recorded-HFR Window

Date: 2026-06-07

Gate 1 source: `docs/IMPL_PLAN_2026-06-07_sound_triggered_hfr_window.md`

Gate 1 status: approved by Claude and Codex. This queue is the Gate 2 review
packet. Do not implement until Gate 2 converges.

## Invariants

- The loud impact pop is a timestamp marker only, not a sound classifier.
- Recording is already armed before impact. Never listen first and then start
  the camera.
- The estimate path decodes only the impact window: 200 ms post-impact at the
  requested HFR rate plus a small bounded pre-impact margin sized from measured
  anchor error.
- At 120 fps the post-impact window is 24 frames.
- The decoder locates the window by container presentation time, not by using a
  sensor-derived frame index as a container-frame index. Sensor frame index is
  diagnostic only because MediaRecorder can drop encoded frames.
- A bad anchor, unmappable timestamp source, excessive anchor error, unbounded
  decoder seek prefix, black/invalid frames, insufficient detections, bad
  calibration, or bad timestamps returns a fail-loud no-read.
- The existing full-burst recorded-HFR gate remains intact. The new windowed
  gate is separate.
- Raw PCM audio never leaves memory and never appears in logs, files, exports,
  or proof artifacts.

## Subtasks

### S1. Impact Audio Trigger Contracts And Detector

Files:

- Add `app/src/main/java/com/speedball/app/audio/ImpactAudioTrigger.kt`
- Add `app/src/test/kotlin/com/speedball/app/audio/ImpactAudioTriggerTest.kt`
- Update `app/src/main/AndroidManifest.xml` only if the existing
  `RECORD_AUDIO` declaration changes, which is not expected.

Contracts:

- `ImpactAudioTriggerConfig`
- `ImpactAudioEvent`
- `ImpactAudioTriggerResult`
- `ImpactAudioSampleWindow` or equivalent small testable PCM value type
- `ImpactAudioClockAnchor` or equivalent value that maps AudioRecord sample
  positions to the monotonic clock base

Implementation requirements:

- Use mono PCM from `AudioRecord` in app code.
- Use `AudioRecord.getTimestamp()` when available to hardware-anchor the audio
  sample clock with `AudioTimestamp.framePosition` and `AudioTimestamp.nanoTime`.
  A software `AudioRecord` start stamp is diagnostic/fallback evidence only and
  must be included in the end-to-end anchor-error budget if used.
- Keep the detection math pure and testable outside Android framework calls.
- Detect the first loud transient using adaptive peak/RMS baseline, threshold
  multiplier, absolute peak floor, cooldown, and max arm duration.
- Return `Detected`, `NoImpact`, `PermissionDenied`, or `ResourceFailure`.
- Expose only derived diagnostics: peak, baseline RMS, trigger ratio, sample
  index, offset milliseconds, and trigger verdict.
- Add KDoc for public contracts and the detector algorithm.

Required tests:

- Quiet baseline does not trigger.
- One loud pop triggers exactly once.
- Repeated loud samples respect cooldown.
- Noisy background requires adaptive threshold and does not trigger on ordinary
  noise.
- Trigger timestamp is computed from sample index and sample rate.
- `AudioRecord.getTimestamp()` anchor maps a triggered sample to the monotonic
  base.
- Missing/stale audio timestamp anchor fails loud or is explicitly counted in the
  end-to-end anchor-error budget.
- Max arm duration returns `NoImpact`.
- Invalid config returns fail-loud `ResourceFailure` or validation no-read, not
  a fake detection.

### S2. Audio/Video Clock Anchor And Window Mapping

Files:

- Add `app/src/main/java/com/speedball/app/capture/AudioVideoClockAnchor.kt`
- Add `app/src/test/kotlin/com/speedball/app/capture/AudioVideoClockAnchorTest.kt`
- Update `app/src/main/java/com/speedball/app/capture/CameraTimestampSource.kt`
  if a helper is needed to reuse timestamp-source labels.

Contracts:

- `AudioVideoClockAnchor`
- `AudioVideoClockAnchorStatus`
- `ImpactWindowRequest`
- `ImpactWindowMapping`
- `ContainerTimeWindow`

Implementation requirements:

- Use the first positive Camera2 `SENSOR_TIMESTAMP` as frame zero.
- `recorderStartCommandElapsedNanos` is diagnostic only.
- Require a `SENSOR_INFO_TIMESTAMP_SOURCE` value that can be mapped to the
  `elapsedRealtimeNanos` base. If not, no-read.
- Map the impact audio sample to the same monotonic base using the audio hardware
  timestamp anchor from S1.
- Compute `impactOffsetUs` relative to the capture/container timeline.
- Compute `impactFrameIndex = floor((impactElapsed - firstFrameElapsed) * fps)`
  only as diagnostic sensor-clock evidence. It must not drive container decode.
- Compute `postImpactFrameCount = ceil(fps * 0.200)`.
- Compute `preImpactMarginFrames` from measured anchor-error seconds, with a
  hard ceiling. Initial review target: `<= 12` frames / `<= 100 ms` at 120 fps
  unless Gate 2 review chooses a tighter value.
- Compute `windowStartUs = max(0, impactOffsetUs - preImpactMarginUs)` and
  `windowEndUs = impactOffsetUs + 200_000`.
- The end-to-end anchor-error budget must include clock mapping error, audio
  timestamp anchoring error, and leading MediaRecorder drop/PTS-zero offset.
- If measured anchor error exceeds the ceiling, no-read instead of widening the
  window.
- Add KDoc for the anchor and mapping contracts.

Required tests:

- 200 ms at 120 fps maps to 24 post-impact frames.
- Impact before container time zero fails or clamps only when covered by bounded
  pre-impact margin.
- Impact beyond the clip fails.
- Non-finite fps, offset, or anchor values fail.
- Missing first-frame sensor timestamp fails.
- Unmappable timestamp source fails.
- Missing or stale audio hardware timestamp anchor fails or is included in an
  over-ceiling no-read.
- Anchor-error over the configured ceiling fails.
- Pre-impact margin is bounded and included in `windowStartUs`.
- `windowStartUs` clamps to zero when the margin precedes container time zero.
- Sensor-frame index and container-frame index divergence is tested; the mapping
  still emits a container-time window and does not count to a sensor-derived
  decoder index.

### S3. Recorder Orchestration: Arm First, Stop After Impact

Files:

- Update `app/src/main/java/com/speedball/app/MainActivity.kt`
- Update `app/src/main/java/com/speedball/app/capture/HighSpeedBurstRecorder.kt`
- Update `app/src/main/java/com/speedball/app/capture/HighSpeedMode.kt` only if
  `BurstOptions` needs an explicit externally-stopped/maximum-arm mode.
- Add or update tests:
  - `app/src/test/kotlin/com/speedball/app/ui/CalibrationUiSourceTest.kt`
  - `app/src/test/kotlin/com/speedball/app/capture/HighSpeedBurstRecorderTest.kt`
    if a pure-testable recorder lifecycle helper is introduced.

Implementation requirements:

- `shoot` and manual Shoot still require completed setup.
- After Ready + beeps, start recorded-HFR capture first.
- Start `AudioRecord` impact listening on receipt of the first Camera2 capture
  result with a positive `SENSOR_TIMESTAMP`, which is also the video anchor. Do
  not use a sleep/heuristic and do not start camera after sound.
- On impact, keep recording for `postImpactCaptureMillis = 200`, then stop the
  active recorder.
- On no impact by max arm duration, stop recorder and return
  `NO_IMPACT_SOUND_DETECTED`.
- Preserve `stopVoiceRecordListener()` before `AudioRecord` ownership and
  restart speech recognition after every terminal result/no-read when setup is
  still valid.
- Ensure `readySignalPending` and repeated partial/final voice callbacks cannot
  queue duplicate attempts.
- Log:
  - `RECORDED_HFR_AUDIO_ARMED`
  - `RECORDED_HFR_IMPACT_DETECTED offsetMs=... impactFrame=... anchorErrorMs=...`
  - `RECORDED_ESTIMATE_WINDOW windowStartUs=... windowEndUs=... postFrames=... preMarginFrames=... totalFrames=... fps=...`
  - terminal complete/no-read

Required tests:

- Voice "shoot" routes to recorded-HFR sound-window arming, not direct visual
  estimate and not whole-clip import.
- Manual Shoot uses the same arming route.
- Repeated partial/final speech results do not start duplicate attempts.
- Speech recognizer is stopped before `AudioRecord` starts and restarted after
  terminal result/no-read.
- No-impact timeout stops recorder and returns no-read.
- Impact schedules recorder stop after 200 ms, not immediately and not after
  the old 3-second fixed duration.
- Mic unavailable returns `ResourceFailure` and does not decode an untriggered
  window.

### S4. Bounded Window Decoder

Files:

- Add `app/src/main/java/com/speedball/app/importing/RecordedHfrWindowFrameSource.kt`
- Add `app/src/test/kotlin/com/speedball/app/importing/RecordedHfrWindowFrameSourceTest.kt`
- Keep `AndroidImportVideoFrameSource.kt` for existing import/full-source paths;
  do not route the sound-triggered path through `getFrameAtIndex`.

Implementation requirements:

- Use `MediaExtractor.seekTo(windowStartUs, SEEK_TO_CLOSEST_SYNC)`.
- Use `MediaCodec` to decode forward until container PTS reaches
  `windowStartUs`, then emit frames whose PTS are inside
  `[windowStartUs, windowEndUs]`, capped at `maxFrames`.
- Do not count decoded frames toward a sensor-derived frame index. The latest
  S10+ evidence showed 400 unique sensor timestamps but only 325 encoded
  container frames, so container frame position and sensor frame index are not
  interchangeable.
- Verify or configure a short GOP/keyframe interval for the recorded burst. If
  Android/MediaRecorder cannot guarantee it, record a measured S10+ seek-prefix
  bound and enforce the wall-clock budget.
- Enforce a hard wall-clock timeout for seek-prefix plus window decode.
- Release extractor, codec, output surfaces/images, and bitmaps on success,
  no-read, timeout, cancellation, and exceptions.
- No fallback to whole-clip `MediaMetadataRetriever.getFrameAtIndex`.
- KDoc must state the bounded-window invariant and resource-release contract.

Required tests:

- Window decoder receives `windowStartUs`, `windowEndUs`, and `maxFrames`.
- Decoder emits no more than the requested window.
- Decoder skips pre-window sync-prefix frames by PTS, not by sensor-derived
  frame count.
- Short mid-window decode returns no-read.
- Non-monotonic window PTS returns no-read.
- Timeout releases resources and returns no-read.
- Cancellation releases resources and returns no-read.
- Pattern test proves the sound-triggered route does not call
  `AndroidImportVideoFrameSource.create` or `getFrameAtIndex`.
- Regression test proves sensor/container count mismatch does not shift the
  emitted window away from the requested PTS interval.
- Test fake/stub infrastructure may emulate Android codec callbacks, but the
  window-bound calculation and resource-release state machine must be real app
  logic.

### S5. Windowed Capture/Decode Gate

Files:

- Update `app/src/main/java/com/speedball/app/importing/RecordedHfrEstimateContracts.kt`
- Add/update `app/src/test/kotlin/com/speedball/app/importing/RecordedHfrEstimateContractsTest.kt`

Contracts:

- `RecordedHfrWindowGateProof`
- `RecordedHfrWindowCaptureGate`
- Window no-read reasons/messages that distinguish capture/drop failure from
  window-decode failure.

Implementation requirements:

- Preserve existing `RecordedHfrCaptureGate` behavior for full-burst paths.
- Windowed gate proves:
  - `BurstDiagnostics.uniqueTimestampCount > 0`;
  - median sensor cadence passes the requested high-speed band;
  - capture proof passes;
  - metadata duration is finite and positive;
  - decoded window count is the requested count, except documented end-of-clip
    minimum cases;
  - window PTS are monotonic;
  - emitted frames are inside the requested container-time window;
  - sensor index and container frame count are never required to match for the
    windowed route;
  - source dimensions are positive;
  - proof thumbnails/frames exist when expected.
- Windowed gate fails loud on short mid-window decode, non-monotonic PTS,
  missing dimensions, missing proof frames, black/source-invalid verdict, or
  insufficient detections.

Required tests:

- Existing full-burst equality mismatch test still fails.
- Windowed gate accepts a valid subset with full-burst sensor count larger than
  decoded window count.
- Windowed gate accepts a valid container-time window when sensor timestamp count
  and encoded container frame count differ.
- Windowed gate rejects a short mid-window decode.
- Windowed gate rejects non-monotonic PTS.
- Windowed gate rejects frames emitted outside the requested container-time
  interval.
- Windowed gate rejects invalid dimensions.
- Windowed gate rejects missing proof frames when a window was decoded.
- Windowed gate no-read messages identify window failure vs capture/drop
  failure.

### S6. Estimate Pipeline And Proof Imagery

Files:

- Update `app/src/main/java/com/speedball/app/importing/RecordedHfrStreamingEstimate.kt`
- Update `app/src/main/java/com/speedball/app/measurement/VisualEstimateCaptureProof.kt`
- Update `app/src/main/java/com/speedball/app/ui/MeasurementResultUiState.kt`
- Update tests:
  - `app/src/test/kotlin/com/speedball/app/importing/RecordedHfrStreamingEstimateTest.kt`
  - `app/src/test/kotlin/com/speedball/app/measurement/VisualEstimateCaptureProofTest.kt`

Implementation requirements:

- Estimate timestamps for retained frames must use decoded container PTS deltas
  relative to the window start and disclose estimate-only timing basis. Do not
  assume consecutive decoded container frames are spaced exactly `1/fps` when
  MediaRecorder may have dropped frames.
- Proof must include source dimensions, working dimensions, start frame, impact
  diagnostic frame, `windowStartUs`, `windowEndUs`, decoded window count,
  selected sample count, black/source-validity verdict, anchor-error bound,
  pre-impact margin, and seek/decode budget.
- Proof thumbnails must come from decoded impact-window frames, not preview
  frames and not stale previous attempts.
- Black/near-black windows return no-read with visible proof diagnostics when
  possible.
- The UI result/report must show why there was no read: no impact, bad anchor,
  decode timeout, black window, no ball, insufficient detections, or bad
  calibration.

Required tests:

- Proof frame indices preserve original frame index and window-local index.
- Gap-aware timing golden test: sparse detections at window frames `0,3,6,9`
  produce a speed consistent with the index/PTS gaps and different from treating
  those detections as consecutive `1/fps` frames.
- Intra-window drop test uses container PTS deltas rather than `index * 1/fps`.
- Proof thumbnails are retained for black/no-ball windows when decoded frames
  exist.
- No stale proof appears after Clear or a new attempt.
- No-read report contains window start, decoded count, and failure reason.
- Success report contains the window proof metadata and does not claim strict
  measurement proof.

### S7. Main Recorded-HFR Route Integration

Files:

- Update `app/src/main/java/com/speedball/app/MainActivity.kt`
- Update `app/src/test/kotlin/com/speedball/app/importing/ImportContractsTest.kt`
- Update `app/src/test/kotlin/com/speedball/app/ui/CalibrationUiSourceTest.kt`

Implementation requirements:

- Replace the current `runRecordedEstimate(file, fps, diagnostics)` whole-clip
  route for `shoot` with a window-aware route that receives impact event,
  anchor, container-time window request, and decode budget.
- Keep existing import/user-selected video behavior separate.
- Keep the existing full-burst debug/import route separate if retained.
- Stop deleting the debug MP4 before proof capture if debug retention is enabled;
  retain only last-attempt debug media and keep it ignored/uncommitted.
- Return to ready/listening state after terminal result/no-read if setup remains
  valid.
- Clear means clear the visible report and do not resurrect it during setup.

Required tests:

- `shoot` uses `windowStartUs`/`windowEndUs` and the window frame cap.
- `shoot` no longer uses `DEFAULT_IMPORT_MAX_FRAMES`.
- `shoot` does not derive the decoder start point from sensor-frame index.
- `record` debug command behavior is explicit and does not contaminate `shoot`.
- After terminal no-read, setup remains valid and the app listens for another
  shoot.
- Clear hides a no-read report until a new attempt produces a new report.

### S8. Security, Privacy, And Documentation

Files:

- Update `docs/HOW_THE_APPLICATION_WORKS.md`
- Update `docs/DATA_FLOW.md`
- Update `docs/HOW_TO_RUN.md`
- Update `docs/FUNCTIONAL_TEST_REGISTRY.md`
- Update `docs/SECURITY_CHECKLIST.md`
- Update source KDoc for every public Kotlin contract added above.

Documentation requirements:

- State the microphone detects a loud transient marker only.
- State recording is already armed before impact.
- State the app decodes only the bounded impact window plus bounded margin.
- State the window is selected by container presentation time, while
  SENSOR_TIMESTAMP is capture/anchor evidence and sensor frame index is
  diagnostic only.
- State decoded container PTS deltas are used for estimate-only within-window
  timing when MediaRecorder drops frames.
- State raw audio is never persisted, exported, logged, or included in proof.
- State recorded-HFR remains an estimate path, not strict timing proof.
- Explain user-visible no-read reasons and how to inspect proof imagery.
- Add functional-test registry entries for each new test class and S10+ device
  proof.
- Add security checklist entries for microphone ownership, raw PCM handling,
  retained debug MP4/PNG evidence, and redacted logs.

Required checks:

- `pnpm docs:check`
- `pnpm security:check`
- `git diff --check`

### S9. S10+ Device Proof

Files/artifacts:

- Do not commit MP4/PNG/APK evidence.
- Store temporary proof under `.interagent/tmp/` or `/tmp` only.
- Add durable text evidence to the appropriate progress log or docs only after
  measured proof exists.

Required proof:

- Build and install debug APK on the S10+.
- No-ball loud pop run:
  - records first;
  - detects impact;
  - decodes a bounded window;
  - returns terminal no-read quickly;
  - no whole-clip crawl.
- Real visible ball run:
  - records first;
  - detects impact;
  - decodes only the impact window;
  - shows proof thumbnails from recorded frames;
  - returns success or specific fail-loud no-read.
- Log sequence includes:
  - `RECORDED_HFR_AUDIO_ARMED`
  - `RECORDED_HFR_IMPACT_DETECTED`
  - `RECORDED_ESTIMATE_WINDOW`
  - `RECORDED_HFR_ESTIMATE_COMPLETE` or `RECORDED_HFR_ESTIMATE_NO_READ`
- Device proof records:
  - measured end-to-end anchor-error bound, including audio anchor error,
    video clock mapping error, and leading MediaRecorder drop/PTS-zero offset;
  - selected pre-impact margin;
  - seek-prefix decode budget;
  - actual decode wall-clock time;
  - decoded window count and emitted window PTS range;
  - proof thumbnail path/contact sheet;
  - whether source frames are black or usable.

## Review/Implementation Batch Split

Implement in two reviewed batches after Gate 2 approval:

1. Pure/testable foundations: S1, S2, S4, S5, and S6. This batch must leave the
   existing full-burst/import streaming path green and must not route `shoot` to
   the new path yet.
2. Integration: S3 and S7, then S8/S9 evidence updates. This batch touches
   `MainActivity` and recorder orchestration after the pure contracts are proven.

Regression obligation for both batches:

- Preserve the just-approved streaming recorded-HFR path: no full-resolution
  ARGB retention, full-burst `scanned == metadata == sensor` gate remains intact,
  and existing streaming tests stay green.
- The approved streaming fix should be committed as its own logical commit before
  sound-window implementation if the user authorizes commits. Do not revert it.

## Required Verification Commands

Focused JVM tests:

```bash
./gradlew :app:testDebugUnitTest --tests '*ImpactAudioTriggerTest' --tests '*AudioVideoClockAnchorTest' --tests '*RecordedHfrWindowFrameSourceTest' --tests '*RecordedHfrEstimateContractsTest' --tests '*RecordedHfrStreamingEstimateTest' --tests '*VisualEstimateCaptureProofTest' --tests '*CalibrationUiSourceTest' --tests '*ImportContractsTest'
```

Full app unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Build/install:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Repository checks:

```bash
pnpm docs:check
pnpm security:check
git diff --check
```

## Gate 2 Review Questions

1. Is the two-batch split acceptable: pure/testable foundations first, then
   MainActivity/recorder integration?
2. Is `<= 12` frames / `<= 100 ms` at 120 fps acceptable as the first hard
   ceiling for `preImpactMarginFrames`, with fail-loud if measured anchor error
   exceeds it, now that the anchor error is explicitly end-to-end?
3. Should debug MP4 retention be always-on for debug builds, or hidden behind a
   developer flag in the same change?
4. Is measured S10+ GOP/seek-prefix proof acceptable if `MediaRecorder` cannot
   guarantee a short keyframe interval directly?
