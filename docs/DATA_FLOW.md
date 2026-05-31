# Data Flow

## Phase 1 Shell

```text
MainActivity
  -> SpeedBallApp Compose shell
  -> Compose-free SpeedBallShellState
  -> pending/unavailable workflow rows
  -> no-read results state
```

The root project now has two modules:

- `:core` is pure Kotlin/JVM and currently exposes only a module identity marker.
- `:app` depends on `:core` and renders the placeholder Android shell.

No camera frames, imported media, calibration data, detections, timestamps,
velocity fits, or trajectory values flow through the app in Phase 1.

## Live 120 fps Path

```text
Camera2 constrained high-speed session
  -> preview surface + MediaRecorder surface
  -> capture callback SENSOR_TIMESTAMP list
  -> saved burst
  -> decode frames in order
  -> reconcile decoded frames with timestamps
  -> HSV/OpenCV centroid detection
  -> flight window selection
  -> core velocity fit
  -> core trajectory physics
  -> Compose results UI
```

## Live 240 fps Path

```text
Camera2 constrained high-speed session
  -> SurfaceTexture / GPU preview path
  -> GLSL HSV threshold mask
  -> centroid reduction
  -> SENSOR_TIMESTAMP timing
  -> core velocity fit
  -> results UI
```

This path is required on S10+ because MediaRecorder cannot persist true 240 fps.

## Import Path

```text
user-selected video URI
  -> validated metadata and frame extraction
  -> offline HSV/OpenCV detection
  -> timestamp/PTS validation
  -> core velocity and trajectory
  -> results UI
```

## Calibration

Color calibration produces HSV bounds. Distance calibration produces `pixelsPerFoot`. Both are required before reporting mph.
