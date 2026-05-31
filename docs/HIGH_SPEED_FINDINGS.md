# High-Speed Capture — De-Risk Findings & Methodology

This records *how* we proved high-speed capture is possible and *what we measured*, so the
claims in `DEVICE_CAPABILITIES.md` are auditable. All numbers are from the S10+ (SM-G975U).

## Why we de-risked first

The whole app hinges on one question: **can a non-Samsung app actually get high-speed frames
on these phones?** Conventional wisdom (and an earlier assistant answer) said "Samsung doesn't
expose high fps to third-party apps." That's only half true, and getting it wrong would sink
the project — so we built the smallest thing that proves or kills it.

## The prototype (`prototype/hs-probe`)

A ~200-line standalone Kotlin app (no React Native, no CameraX) that:
1. Opens the back camera via Camera2 and logs the HAL's `availableHighSpeedVideoConfigurations`.
2. Creates a `CameraConstrainedHighSpeedCaptureSession` at a requested size/fps.
3. Records a short burst via MediaRecorder, and **independently counts true frame delivery
   via `SENSOR_TIMESTAMP`** in the capture callback.
4. Is driven from adb with intent extras: `--ei fps {120|240} --ei w {1280|1920} --ei h {720|1080} --ei slowmo {0|1} --el durMs N`.

Verification of saved clips used OpenCV (`cv2.VideoCapture` frame counts), not just container
metadata.

## Key methodological catch

Raw `onCaptureCompleted` callback counts **over-reported** on the 120 fps run (per-batch
double-firing): 640 callbacks but only 400 unique sensor timestamps. We therefore report the
**median gap between unique `SENSOR_TIMESTAMP`s** as the true frame rate:
- 4.12 ms gap = ~242 fps
- 8.33 ms gap = ~120 fps

This is the difference between a believable measurement and a fabricated one — the median-gap
check is the load-bearing evidence.

## Raw results (S10+)

```
1080p@240 : callbacks=640 uniqueTs=640 spanSec=2.642 medianGapMs=4.12  -> ~242 fps (all unique)
720p@240  : callbacks=640 uniqueTs=640 spanSec=2.642 medianGapMs=4.12  -> ~242 fps (all unique)
1080p@120 : callbacks=640 uniqueTs=400 spanSec=2.662 medianGapMs=8.33  -> ~120 fps

MediaRecorder MP4 distinct frames (cv2):
  1080p@240 realtime -> 188 frames  (~118 fps; encoder-dropped)
  720p@240  realtime -> 331 frames  (~120 fps; encoder-dropped)
  1080p@120 realtime -> 328 frames  (~120 fps; clean)
  1080p@240 slow-mo  -> 328 frames  (~124 fps; no gain — 108 MB file is bitrate×duration, not more frames)
```

## Phase 4 app proof (S10+)

After the Camera2 capture foundation landed in `:app`, we reran the proof through
the real app on the connected S10+ (`SM-G975U`, Android 12), not the standalone
prototype.

HAL enumeration from the app:

```text
1280x720 @ 120 fps: recordSupported=true
1920x1080 @ 120 fps: recordSupported=true
1280x720 @ 240 fps: recordSupported=true
1920x1080 @ 240 fps: recordSupported=true
```

Successful 720p@120 burst proof:

```text
callbacks=520
uniqueTs=325
expected=300
min=240
medianGapMs=8.33
band=7.08..9.58
medianPass=true
proof=true
file=speed_ball_1280x720_120_1780220549068.mp4
bytes=6460459
```

The MP4 existed under the app-specific external files Movies directory with
`6460459` bytes.

Lifecycle-stop proof:

```text
callbacks=72
uniqueTs=45
expected=300
min=240
medianGapMs=8.33
band=7.08..9.58
medianPass=true
proof=false
file=speed_ball_1280x720_120_1780220593313.mp4
bytes=612495
```

This run sent HOME during the burst. The app stopped early, closed a nonzero MP4,
and correctly refused to claim 120 fps proof because the unique timestamp floor
was not met.

## What this proves / disproves

- ✅ Third-party Camera2 high-speed works on the S10+.
- ✅ The sensor delivers **true ~242 fps at 1080p** to our app.
- ❌ MediaRecorder/H.264 **cannot persist** more than ~120 fps on the Exynos 9820 (encoder
  ceiling) — so the 240 path needs GPU/preview frame access, not the encoder.
- ✅ 120 fps records cleanly end-to-end → a simple record-then-decode pipeline is viable now.

## Open items carried into the plan

- Verify the same on the **S22+** (expected: true 240, likely persistable via its stronger encoder).
- Build the **GPU (SurfaceTexture + GLSL) frame path** for true 240 on the S10+.
- Confirm **per-frame timestamp pairing** when decoding (sensor timestamps ↔ decoded frame order).
