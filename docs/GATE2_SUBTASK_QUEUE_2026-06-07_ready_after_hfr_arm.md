# Gate 2 Subtask Queue - Ready After HFR Arm

Date: 2026-06-07
Gate 1 plan: `docs/IMPL_PLAN_2026-06-07_ready_after_hfr_arm.md`

## Approved Contract

The Run Mode `shoot` path must become:

1. stop speech recognition;
2. start `AudioRecord` first;
3. obtain a BOOTTIME audio timestamp anchor;
4. start HFR recording only after audio is actually armed;
5. wait for the first positive HFR `SENSOR_TIMESTAMP`;
6. emit the audible `Ready` cue only after both audio and video anchors exist;
7. ignore pre-ready and Ready-cue transients using sample-index boundaries; and
8. accept the next sharp loud ambient-relative delta/spike after cue blanking as
   the impact marker.

The mic may be running before video. An accepted marker must not predate the
first HFR frame because that would point to a video window that does not exist.
The trigger must not classify sound type. A mouth-generated shouted/pop sound is
valid when it is a loud delta over ambient after Ready.

## Subtasks

### S1 - Pure Detector Sample-Index Phases

- Extend `ImpactAudioTriggerConfig` with explicit sample-index phase boundaries:
  - `acceptAfterSampleIndex` or equivalent;
  - anchor-derived Ready-cue blanking end sample index;
  - user-actionable max samples measured after blanking.
- Keep every disabled/blanking/accepting decision in the same sample clock as
  detector scanning. Do not use wall-clock blanking in the pure detector.
- Reset or delay baseline windows so they never include pre-ready samples or
  blanked Ready-cue samples.
- Make the detector an ambient-delta transient detector:
  - do not require sound classification;
  - do not keep a hard absolute amplitude floor that can reject a loud
    ambient-relative spike;
  - replace `absolutePeakFloor` gating with an ambient-relative peak/RMS delta
    contract, with any optional noise floor used only to reject silence, not to
    block a clear ratio/delta spike;
  - preserve fail-loud no-read when post-ready samples never exceed the
    ambient-relative delta contract.
- Add diagnostics to `ImpactAudioTriggerResult.NoImpact` for:
  - scanned samples;
  - user-actionable samples scanned;
  - pre-ready transient count;
  - blanked transient count;
  - below-threshold post-blanking transient count.
- Include threshold diagnostics that explain why a post-ready spike failed:
  baseline RMS, strongest post-ready peak/RMS ratio or delta, and whether any
  silence/noise-floor guard, if present, was involved.
- KDoc the phase-boundary contract and the reason accepted markers cannot be
  before video.

### S2 - Detector Tests

Add real JVM tests in `ImpactAudioTriggerTest`:

- loud pre-ready sample is counted/diagnosed but not returned as `Detected`;
- loud cue-time sample inside blanking is counted/diagnosed but not returned as
  `Detected`;
- long/worst-case Ready cue tail remains blanked until the anchor-derived
  accept-after sample index, and only a later pop is accepted;
- blanked cue samples cannot become the baseline for the post-blanking scan;
- valid post-blanking pop returns `Detected` at the expected sample index and
  expected elapsed realtime;
- mouth-pop-style ambient-relative spike triggers even when its absolute
  amplitude would have been below the previous `absolutePeakFloor = 4000`;
- no-impact result distinguishes no sound, pre-ready, blanked, and
  below-threshold diagnostics;
- invalid phase boundaries fail loud as `ResourceFailure`.

### S3 - Android Audio Trigger Armed Callback

- Change `AndroidImpactAudioTrigger.start(...)` to accept an `onArmed` callback.
- Invoke `onArmed` only after:
  - `AudioRecord.startRecording()` returns successfully;
  - `AudioRecord.getTimestamp(... TIMEBASE_BOOTTIME)` returns a usable anchor.
- Do not invoke `onArmed` on permission denied, recorder creation failure,
  `startRecording()` failure, anchor failure, or read failure.
- Preserve single-owner mic behavior and raw-PCM-in-memory-only behavior.
- Expose enough state for the main thread to supply the HFR first-frame anchor
  and sample-index accept boundary without racing the audio worker.
- Expose the audio clock anchor to `MainActivity` so actual Ready-cue completion
  can be converted to a detector sample index.

### S4 - Audio-First MainActivity Orchestration

- Replace the pre-arm `playReadySignalThenRecordedHfrEstimate()` path.
- `handleShootCommand()` should:
  - stop speech recognition;
  - set capture state to an arming state, not `Ready to shoot`;
  - call the new audio-first sound-triggered HFR sequence.
- Start `AndroidImpactAudioTrigger` before `burstRecorder.start(...)`.
- On audio `onArmed`, start HFR recording.
- On HFR `onFirstFrameAnchor`, combine audio and video anchors, then emit Ready.
- After the actual Ready cue completion is known, convert cue completion to
  `acceptAfterSampleIndex` through the BOOTTIME audio anchor and enable marker
  acceptance for the audio worker.
- Ready cue completion must be the later of:
  - `TextToSpeech` `UtteranceProgressListener.onDone`/`onError` for the
    `Ready` utterance; and
  - the deterministic final beep end plus release padding.
- If TTS completion cannot be observed robustly, the post-armed cue must be
  deterministic beeps-only. Spoken status may occur before the armed detector is
  accepting markers, but no unknown-duration spoken cue may be inside the
  accepted/partially blanked window.
- If audio arming fails before HFR starts, finish a fail-loud no-read/failure and
  do not start video.
- If HFR start or first-frame anchor fails after audio arms, cancel audio, finish
  fail-loud, and do not emit Ready.
- On terminal success/no-read/failure, cancel or release the audio trigger and
  restart the live feed/listener according to the existing Run Mode flow.

### S5 - Duration And Window Constants

- Replace `SOUND_TRIGGER_MAX_ARM_MILLIS = 2500` with a user-actionable 5000 ms
  post-blanking window.
- Add explicit cue/blanking duration constants derived from:
  - actual Ready TTS completion when TTS is used;
  - three beeps;
  - beep spacing;
  - tone release padding;
  - safety margin.
- Do not derive `acceptAfterSampleIndex` from a fixed Ready TTS duration guess.
  The accepted sample index must be based on actual TTS completion or a
  deterministic beeps-only cue.
- Ensure recorder external-stop duration covers:
  - audio arming;
  - HFR startup and first-frame callback;
  - worst-case Ready cue and blanking;
  - 5000 ms user-actionable impact window;
  - 200 ms post-impact capture.

### S6 - Mapping And Logging

- Map accepted impact events with the existing `ImpactWindowMapper` only after
  both anchors exist.
- Log:
  - audio armed callback;
  - HFR first-frame anchor;
  - Ready cue emitted;
  - Ready cue blanking sample count;
  - marker acceptance enabled sample index and the cue-completion source
    (`tts_done`, `tts_error`, or `beeps_only`);
  - detected impact offset;
  - no-impact diagnostics: armed state, scanned samples, actionable samples,
    arm duration, pre-ready transient count, blanked transient count, and
    below-threshold count;
  - threshold diagnostics for no-impact: strongest post-ready ambient-relative
    delta/ratio and whether any silence/noise-floor guard was involved.
- Keep no-read/failure behavior fail-loud. Do not emit mph from pre-ready,
  blanked, bad-anchor, no-impact, or bad-window states.

### S7 - Source Guards And Integration Tests

Update source and pipeline tests:

- `CalibrationUiSourceTest` rejects:
  - `updateShellState(status = "Ready to shoot")` before arm;
  - `playReadySignalThenRecordedHfrEstimate()`;
  - video-first `onFirstFrameAnchor = { anchor -> startImpactAudioMarker(...) }`.
- `CalibrationUiSourceTest` asserts:
  - audio start before HFR start;
  - Ready cue after both audio armed and HFR first-frame anchor;
  - marker acceptance enabled after actual Ready cue completion maps to an
    audio-anchor sample index;
  - terminal paths cancel/release audio.
- `ImportContractsTest` asserts the live route still uses
  `startRecordedWindowEstimate(outcome)`, not whole-clip
  `startRecordedEstimate(outcome)`.
- `RecordedHfrWindowFrameSourceTest` / related source tests assert BOOTTIME
  anchor and UNPROCESSED/MIC fallback remain present.

### S8 - Docs

Update:

- `docs/HOW_THE_APPLICATION_WORKS.md`
- `docs/DATA_FLOW.md`
- `docs/HOW_TO_RUN.md`
- `docs/FUNCTIONAL_TEST_REGISTRY.md`

Docs must explain the actual user sequence:

- Setup once.
- Say `shoot`.
- App arms mic first, then HFR video.
- Wait for audible `Ready`.
- Make the pop/impact after the Ready cue finishes.
- The app ignores its own Ready cue and shows logs/proof when no read happens.
- Any loud ambient-relative spike after Ready counts, including a mouth-generated
  shouted/pop sound. The app does not classify the sound type.

### S9 - Verification

Before install:

- focused detector/source tests;
- full `:app:testDebugUnitTest --rerun-tasks`;
- `:app:assembleDebug --rerun-tasks`;
- `pnpm docs:check`;
- `pnpm security:check`;
- `git diff --check`.

On S10+ after install:

- clear logcat;
- say `shoot`;
- verify log order:
  - voice command;
  - audio armed;
  - HFR start;
  - HFR first-frame anchor;
  - Ready cue emitted;
  - marker acceptance enabled after actual cue completion is mapped to an audio
    sample index;
  - impact detected after mouth pop;
  - bounded recorded estimate window;
- verify the spoken `Ready`/beeps do not self-trigger;
- verify a mouth-generated shouted/pop sound after Ready triggers as a loud
  ambient-relative spike;
- verify no-impact logs show scanned/actionable samples and blanked diagnostic
  counts plus strongest post-ready delta/ratio.

## Gate 2 Review Questions

1. Is the queue specific enough to implement without adding unreviewed behavior?
2. Are the sample-index detector phase boundaries sufficient to avoid wall-clock
   blanking drift?
3. Are any additional tests required before implementation begins?
