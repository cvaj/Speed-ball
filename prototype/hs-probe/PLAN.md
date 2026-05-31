# Softball Exit-Velocity — native Android (Kotlin) build plan

Decided architecture (2026-05-30): **fully native Android, Kotlin + Jetpack Compose +
Camera2 high-speed + OpenCV Android SDK.** vision-camera/CameraX is out (no high-speed).
Math ports from the verified `softball-speed/src/core` TS core.

## Hard requirements (from the user)
- Live = arm -> swing -> short burst -> process in ~1-2s -> on-screen report. No upload, no manual slow-mo.
- **Selectable capture mode: fps {120, 240} x resolution {720p, 1080p}.** Only offer combos the
  device's HAL actually reports. Default to the S10+ sweet spot (720p).
- Tap-to-sample the ball's neon color in the live view; that HSV band is the tracker target.
- Outputs: exit velocity (mph), launch/flight angle, trajectory with **aerodynamic drag**
  (softball Cd/mass/diameter/air density; optional wind).
- Distance scale: manual known-distance caliper (carried over from TS core).
- Accuracy bar: within ~5 mph of actual. Both phones: S10+ and S22+.
- Recording-import mode (incl. stock-app slow-mo clips) = high-accuracy / verification path.

## Verified device facts (see FINDINGS.md)
- S10+ delivers TRUE ~242fps @ 1080p to a third-party Camera2 app (4.12ms gaps, all unique).
- S10+ H.264 encoder caps MP4 persistence ~120fps -> 240 path must read frames off the
  GPU/preview (SurfaceTexture) surface, NOT the encoder.
- 720p is plenty for centroid tracking and runs much cooler -> S10+ default 720p.

## Staged subtask queue
- [x] S0  De-risk: prove third-party high-speed capture on S10+ (hs-probe). DONE.
- [ ] S1  App skeleton: rename/restructure to the real app; Compose nav (Capture / Calibrate /
         Results / Import); mode selector UI (fps x res) populated from device HAL.
- [ ] S2  Core math in Kotlin: port velocity LS-fit + LOO, calibration (px/ft horizontal),
         launch angle, units (px->mph). Unit tests (golden values from the TS suite).
- [ ] S3  Trajectory physics (Kotlin): RK4 with quadratic drag; softball constants; wind opt.
         Golden-value tests vs hand-calc / no-drag closed form.
- [ ] S4  120fps capture path: Camera2 high-speed record burst (clean at 120) -> decode frames
         (MediaMetadataRetriever/MediaCodec) -> OpenCV HSV centroid per frame.
- [ ] S5  Tap-to-sample HSV calibration on a live preview frame; tolerance band.
- [ ] S6  Distance caliper calibration screen (port the 1-DOF caliper UX).
- [ ] S7  Pipeline: detections -> velocity/angle -> trajectory -> Results UI (mph, angle,
         R^2, trajectory plot, carry/apex/hang). Fail-loud on <3 detections / bad scale.
- [ ] S8  240fps GPU path: SurfaceTexture -> GLSL HSV threshold -> centroid readback at 240.
- [ ] S9  Recording-import mode: pick a video, run the same detector offline.
- [ ] S10 OpenCV Android SDK integration + thermal/duty-cycle guards; on-device testing both modes.

## Notes / decisions
- Build first to a WORKING 120fps tool (S4-S7), then add 240 GPU path (S8). 120 already
  meets the +-5mph bar and 4x beats 30fps.
- Reuse hs-probe's proven Camera2 high-speed session code as the capture foundation.
