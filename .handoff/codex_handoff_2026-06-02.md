# Codex Handoff - 2026-06-02 02:39 ET

## Repo And Protocol State

- Repo: `/home/vangmountain/projects/speed-ball`
- Active task/workstream: `post-phase-16-live-camera-calibration-ui`
- Owner: Codex implementation lane.
- No Claude review request was sent for the latest fixes. User explicitly said review is probably needed later, but not now.
- Do not commit unless the user explicitly authorizes it.
- Current broker next step says the latest debug APK is installed on the S10+ and manual proof is needed.

## Installed Build State

- Debug APK installed successfully on the S10+ over wireless ADB.
- Logcat was cleared before relaunching the latest build.
- Startup screenshot after install: `.interagent/tmp/shoot-direct-route-installed.png`
- Screenshot shows live preview, A/B caliper lines, IMU axis, and Tools handle.

## Critical Latest Fix

User correctly pointed out that after saying `shoot`, the app said `Ready` but then went into the wrong path for S10+.

Evidence before fix:

- Logcat showed `VOICE_SHOOT_COMMAND recognized`.
- Then logcat showed `VOICE_RECORD_SUCCESS ... speed_ball_...mp4`.
- Then logcat showed `RECORDED_ESTIMATE_STAGE ...`.
- This proved `shoot` was incorrectly routing through `startTimedRecordingEstimate()` / MediaRecorder MP4 / decode estimate.
- The S10+ should not use that route for the field `shoot` path because decoder-based measurement/estimate behavior is not the path the user wants.

Implemented fix:

- `MainActivity.handleShootCommand()` now calls `playReadySignalThenVisualEstimate()`.
- After `Ready` speech and three beeps, `shoot` now calls `startDirectVisualEstimate()` / `Est 120`.
- It no longer calls `startTimedRecordingEstimate()` after `Ready`.
- Added `readySignalPending` so partial/final voice callbacks cannot queue multiple captures during the ready/beep interval.
- Added `CaptureStatusOverlay` in `SpeedBallApp.kt` for active capture states, so camera ownership transitions do not look like a dead black result screen.
- Full black result overlay remains gated to current successful result statuses only.

Expected proof after this build:

- Say `shoot`.
- Expected logs: `VOICE_SHOOT_COMMAND recognized`, then `VISUAL_ESTIMATE_*`.
- Forbidden logs after `shoot`: `VOICE_RECORD_SUCCESS`, `RECORDED_ESTIMATE_STAGE`.
- Full black result screen should appear only after a successful estimate result.
- No-read should leave normal status/no-read UI, not a full black result overlay.

## Latest No-Read Evidence Before Fix

Two recent recorded/decode-path attempts were observed before fixing the route:

- One no-read: `IMPORT_ESTIMATE_NO_READ reason=INVALID_METADATA message=Recorded estimate needs distance setup, color sample, and ROI before processing.`
- Another no-read: `IMPORT_ESTIMATE_RESULT result=estimate-no-read_reason=INSUFFICIENT_DETECTIONS ... At least four usable detections are required for an estimate.`

These were recorded path no-reads, not valid results.

## Related UI/Behavior Already Implemented

- Landscape camera-first live UI.
- Live feed aspect/stretch corrected after prior user report.
- A/B caliper lines drag continuously and smoothly with active-target-only behavior.
- Fine/coarse drag supported.
- Tools and Setup are bottom drawer style and translucent.
- Setup status row shows applied status and target.
- Ball color is selected through Color/Sample; ROI is region of interest.
- IMU level reference updates during setup and freezes at capture start.
- `Record 120` remains a separate explicit MediaRecorder MP4 diagnostic path.
- `Est 120` is direct 120 visual estimate path.
- `shoot` should now use `Est 120`, not `Record 120`.

## Files Touched In Latest Fix

- `app/src/main/java/com/speedball/app/MainActivity.kt`
  - Replaced ready-to-record voice route with ready-to-direct-visual-estimate route.
  - Added `readySignalPending`.
- `app/src/main/java/com/speedball/app/ui/SpeedBallApp.kt`
  - Added active `CaptureStatusOverlay`.
  - Result overlay already gated to current successful estimate-complete statuses.
- `app/src/test/kotlin/com/speedball/app/ui/CalibrationUiSourceTest.kt`
  - Added source guards for direct visual route and capture status overlay.
- `docs/DATA_FLOW.md`
  - Corrected `shoot` contract to direct visual estimate path.
- `docs/HOW_THE_APPLICATION_WORKS.md`
  - Corrected field-test `shoot` path language.
- `docs/FUNCTIONAL_TEST_REGISTRY.md`
  - Updated registry wording for direct visual route.
- `.interagent/progress/post-phase-16-live-camera-calibration-ui.md`
  - Added latest correction section and evidence.

## Checks Passed After Latest Fix

Commands run:

```sh
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:testDebugUnitTest --tests 'com.speedball.app.ui.CalibrationUiSourceTest' :app:assembleDebug
bash scripts/docs-check.sh
bash scripts/security-check.sh
git diff --check
```

All passed.

## Suggested Next Step

Manual test on the installed S10+ build:

1. Open app.
2. Ensure setup is ready: distance, color, ROI, level.
3. Tap Voice.
4. Say `shoot`.
5. Confirm app says `Ready` and beeps.
6. Confirm capture status appears during active capture.
7. Pull logs:

```sh
adb logcat -d | rg -n "VOICE_SHOOT_COMMAND|VOICE_RECORD_SUCCESS|RECORDED_ESTIMATE_STAGE|VISUAL_ESTIMATE|IMPORT_ESTIMATE|estimate-no-read" | tail -n 80
```

Pass condition:

- `shoot` produces `VISUAL_ESTIMATE_*`.
- `shoot` does not produce `VOICE_RECORD_SUCCESS` or `RECORDED_ESTIMATE_STAGE`.

## Review Recommendation

Claude Code should probably review the latest implementation later, specifically:

- Voice `shoot` routing to direct visual estimate.
- `readySignalPending` duplicate-capture guard.
- Active capture status overlay.
- Result overlay success-status gating.
- Docs/test registry consistency.

Do not send that review now unless the user explicitly asks.

## Still Open Physical Gates

- Real-ball ground-truth proof.
- IMU level sign validation.
- Human voice field test.
- Latest manual proof that `shoot` now uses direct visual estimate and no longer enters recorded/decode path.
