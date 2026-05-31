# How The Application Works

Speed-ball measures a hit softball by tracking a neon-colored ball in high-speed phone-camera frames.

## User Flow

1. The user selects a supported camera mode, such as 720p at 120 fps.
2. The user calibrates distance by marking a known distance in the ball's plane.
3. The user taps the neon ball to sample its color.
4. The app records a short high-speed burst.
5. The app detects the ball center in each frame.
6. The app fits a straight-line velocity over valid detections.
7. The app converts pixels per second to mph using the distance calibration.
8. The app reports speed, launch angle, confidence, and a drag-based trajectory.

## Measurement Rules

Frame coordinates are raw image pixels. Image `y` points downward, so upward velocity is negative `vy`.

Speed is:

```text
speed_px_per_s = sqrt(vx^2 + vy^2)
feet_per_second = speed_px_per_s / pixels_per_foot
mph = feet_per_second * 0.6818182
```

Launch angle is:

```text
angle_degrees = atan2(-vy, abs(vx))
```

The camera must be roughly side-on to the swing plane. Off-axis setup creates foreshortening error and must be surfaced to the user.

## Fail-Loud Behavior

The app must show "No read" rather than a wrong speed when:

- fewer than three valid detections exist;
- timestamps are missing, duplicate, non-monotonic, or unpaired;
- calibration is missing or invalid;
- the fit is non-finite or residuals are too large;
- the detector cannot distinguish the ball from background blobs.

## Capture Modes

- 120 fps on S10+ uses record-then-decode because it records cleanly.
- 240 fps on S10+ requires GPU/preview detection because MediaRecorder drops frames.
- Device capabilities must come from Camera2 HAL enumeration, not hardcoded assumptions.
