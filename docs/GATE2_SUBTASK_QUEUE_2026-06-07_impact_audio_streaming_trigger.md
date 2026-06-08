# Gate 2 Subtask Queue - Impact Audio Streaming Trigger

Date: 2026-06-07
Workstream: `impact-audio-streaming-trigger`
Gate 1 plan: `docs/IMPL_PLAN_2026-06-07_impact_audio_streaming_trigger.md`

## Gate 2 Invariants

- Do not change the audio-first contract:
  - impact audio arms before HFR recording starts;
  - HFR first-frame timestamp must exist before Ready;
  - Ready cue must complete before marker acceptance;
  - accepted baseline cannot include pre-video or cue samples.
- Do not classify sound type. The trigger is an ambient-relative transient
  detector only.
- Keep `ImpactAudioTrigger.detect(...)` as the batch compatibility API.
- Batch and streaming detection must produce identical verdicts, detected sample
  index, and no-impact diagnostics for characterized inputs.
- The Android audio worker must not copy/rescan the full accumulated buffer.
- HFR `ExternalStop` must still have a duration hard cap.
- No path may report mph from missing marker, bad anchors, hard cap, timeout, or
  insufficient/ambiguous frame evidence.

## Subtask 1 - Streaming Detector State

Files:

- `app/src/main/java/com/speedball/app/audio/ImpactAudioTrigger.kt`
- `app/src/test/kotlin/com/speedball/app/audio/ImpactAudioTriggerTest.kt`

Tasks:

- Add a JVM-testable streaming detector state, for example
  `ImpactAudioStreamingDetector`.
- Feed it `ShortArray` chunks with an explicit written-sample cursor.
- Maintain enough sample history for baseline and trigger windows only.
- Advance a monotonic scan cursor; never rescan already-finalized windows.
- Compute baseline/window RMS by recomputing the fixed-size baseline and trigger
  windows at each monotonic scan position, using the same arithmetic as the
  batch detector. Do not use running-sum-subtract RMS unless equivalence is
  deliberately changed to epsilon-based with drift controls.
- Preserve existing diagnostics:
  - scanned/actionable samples;
  - pre-ready transient count;
  - blanked transient count;
  - below-threshold transient count;
  - strongest post-ready delta/ratio;
  - configured minimum delta/baseline.
- Preserve phase validation and anchor failure behavior.
- Make `ImpactAudioTrigger.detect(...)` a compatibility wrapper over streaming
  state so existing callers/tests still exercise the same logic.

Required tests:

- Streaming and batch return identical `Detected` sample index and event scalars
  for a valid post-Ready pop around delta `107`, ratio around `3.0`. Identical
  means same verdict, same sample index, and the same scalar diagnostics produced
  by the same fixed-window arithmetic, not just same verdict class.
- Streaming and batch return identical no-trigger diagnostics for quiet
  near-silence breath/rustle.
- Streaming and batch match for pre-video transients, cue blanking, and baseline
  exclusion.
- Streaming and batch match for invalid phase boundaries and stale anchors.
- Chunk-boundary tests where baseline, trigger window, and peak sample cross
  chunk boundaries.

## Subtask 2 - Real-Time / No-Starvation Proof

Files:

- `app/src/test/kotlin/com/speedball/app/audio/ImpactAudioTriggerTest.kt`
- optional new focused test file under `app/src/test/kotlin/com/speedball/app/audio/`

Tasks:

- Add a deterministic JVM performance assertion or bounded-work metric for the
  streaming detector.
- The test must process at least the sound-trigger buffer shape:
  - 48 kHz audio;
  - baseline 960 samples;
  - trigger window 96 samples;
  - at least 330,240 samples, matching the failed S10+ run size.
- Assert processing stays comfortably under the audio duration or assert a
  bounded work counter that proves each finalized window is processed once.
- Keep this test deterministic enough for CI; avoid relying only on a fragile
  wall-clock threshold if a work-counter assertion is available.

## Subtask 3 - Android Audio Worker Streaming Integration

Files:

- `app/src/main/java/com/speedball/app/audio/AndroidImpactAudioTrigger.kt`
- `app/src/test/kotlin/com/speedball/app/importing/RecordedHfrWindowFrameSourceTest.kt`
- `app/src/test/kotlin/com/speedball/app/importing/ImportContractsTest.kt`

Tasks:

- Replace per-read `ImpactAudioTrigger.detect(samples.copyOf(written), ...)`
  with chunk-fed streaming state.
- Keep `AudioRecord.getTimestamp(AudioTimestamp.TIMEBASE_BOOTTIME)` as the
  timing authority.
- Keep `markVideoReady` and `enableAcceptance` as sample-index boundary updates.
- Stop reading promptly when:
  - detector returns `Detected`;
  - detector returns `ResourceFailure`;
  - stop sample `acceptAfterSampleIndex + actionableSampleCount` is reached;
  - cancellation occurs.
- Raw PCM must remain in memory only and be dropped on terminal/cancel.

Required tests/source guards:

- Reject `copyOf(written)` in `AndroidImpactAudioTrigger`.
- Require construction/use of the streaming detector state.
- Preserve `onArmed` after `startRecording()` and BOOTTIME anchor.
- Preserve audio-first route from `MainActivity`.

## Subtask 4 - Provisional Trigger Gate And Diagnostics

Files:

- `app/src/main/java/com/speedball/app/MainActivity.kt`
- `docs/HOW_TO_RUN.md`
- `docs/HOW_THE_APPLICATION_WORKS.md`
- `docs/DATA_FLOW.md`
- `docs/FUNCTIONAL_TEST_REGISTRY.md`

Tasks:

- Keep centralized production gates:
  - `SOUND_TRIGGER_THRESHOLD_MULTIPLIER = 2.5`;
  - `SOUND_TRIGGER_MINIMUM_PEAK_DELTA = 100`;
  - `SOUND_TRIGGER_MINIMUM_BASELINE_RMS = 25.0`.
- Document these as provisional field-tuned gates based on the S10+ run that
  logged delta `107.20` and ratio `3.04`.
- Ensure no-impact logs still include strongest delta/ratio and configured gates.
- Ensure docs say field proof must validate both multi-pop reliability and no
  ambient false-trigger.

Required tests:

- Source guard or unit test verifies the configured gates are centralized and
  set to the provisional values.
- Detector tests prove a delta around `107` and ratio around `3.0` triggers.
- Detector tests prove near-silent breath/rustle still fails.
- Detector tests include a moderate non-silent ambient baseline with a sub-pop
  transient that must not trigger, so false-trigger coverage is not limited to
  near-silent rooms.

## Subtask 5 - ExternalStop Duration Hard Cap

Files:

- `app/src/main/java/com/speedball/app/capture/HighSpeedBurstRecorder.kt`
- `app/src/test/kotlin/com/speedball/app/importing/ImportContractsTest.kt`
- optional focused recorder/source contract test

Tasks:

- Change `BurstStopMode.ExternalStop` semantics:
  - external event may stop earlier;
  - `durationMillis` remains a hard failsafe.
- Extract a pure JVM-testable helper for the failsafe decision, for example
  `resolveBurstDurationFailsafeMillis(stopMode, durationMillis): Long?`.
- `HighSpeedBurstRecorder` must call that helper instead of embedding an
  untestable `stopMode` branch.
- The hard cap must not be shorter than the sound-trigger budget:
  - HFR start budget;
  - Ready cue budget;
  - 5-second user actionable window;
  - 200 ms post-impact capture margin.
- Ensure completion remains idempotent if external stop and hard cap race.

Required tests/source guards:

- Guard that `HighSpeedBurstRecorder` does not skip all duration stop logic for
  `ExternalStop`.
- Unit-test the pure failsafe helper:
  - returns the configured cap for `ExternalStop`;
  - returns the configured cap for ordinary timed modes;
  - returns `null` only for any explicitly uncapped mode, if such a mode exists;
  - rejects or clamps invalid durations consistently with current burst options.
- Contract/source guard that sound-triggered HFR still uses `ExternalStop`, but
  passes a duration that covers the full ready-after-arm budget.
- Existing HFR tests continue to pass.

## Subtask 6 - Fail-Loud Outcome And Window Boundaries

Files:

- `app/src/main/java/com/speedball/app/MainActivity.kt`
- recorded-HFR window tests under `app/src/test/kotlin/com/speedball/app/importing/`
- UI/source guard tests under `app/src/test/kotlin/com/speedball/app/ui/`

Tasks:

- Preserve no-read behavior for:
  - no impact;
  - bad audio/video anchor;
  - bad Ready cue anchor;
  - hard cap without accepted marker;
  - insufficient decoded window frames;
  - ambiguous timing/cadence/source validity.
- Ensure no such path displays mph.
- Ensure successful detected impact still maps to a bounded recorded-HFR window
  and decodes no more than that bounded window.

Required tests:

- Update existing source guards to reject full-clip decode for the live route.
- Verify no-impact/hard-cap result remains no-read with scalar diagnostics.
- Verify `RECORDED_ESTIMATE_WINDOW` is still logged only after detected impact.

## Subtask 7 - Docs And Security

Files:

- `docs/HOW_TO_RUN.md`
- `docs/HOW_THE_APPLICATION_WORKS.md`
- `docs/DATA_FLOW.md`
- `docs/FUNCTIONAL_TEST_REGISTRY.md`
- `docs/SECURITY_CHECKLIST.md`

Tasks:

- Document streaming audio trigger flow.
- Document provisional gates and field-validation requirements.
- Document expected successful log sequence and failure diagnostics.
- Document that raw PCM remains in memory only.
- Document `.misc.txt` remains ignored and excluded from commit.

## Subtask 8 - Verification And Device Proof

Required local checks:

- Focused audio detector tests.
- Focused Android/source guard tests.
- Focused recorded-HFR window tests.
- Full `:app:testDebugUnitTest --rerun-tasks --no-daemon`.
- `:app:assembleDebug --rerun-tasks --no-daemon`.
- `pnpm docs:check`.
- `pnpm security:check`.
- `git diff --check`.

Required S10+ proof:

- Install debug build.
- Clear logcat.
- Launch app.
- Run multiple `shoot` attempts:
  - voice recognition starts the route;
  - audio armed before HFR;
  - first HFR anchor before Ready;
  - acceptance enabled after Ready cue completion;
  - mouth/impact pop triggers `RECORDED_HFR_IMPACT_DETECTED`;
  - processing returns promptly, not 40 seconds later;
  - bounded window decode starts;
  - no mph on any no-read.
- Run a realistic outdoor/noisy ambient/no-pop 5-second window with moderate
  ambient sources such as wind or voices:
  - no false trigger;
  - no-impact returns promptly;
  - diagnostics include strongest delta/ratio.
- Capture logs and at least one screenshot/proof artifact under `.interagent/tmp/`.
