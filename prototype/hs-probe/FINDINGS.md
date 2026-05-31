# High-speed capture de-risk — findings (Galaxy S10+ / SM-G975U)

Prototype: `hs-probe` — a minimal standalone Kotlin app that opens the back camera via
**Camera2 `CONSTRAINED_HIGH_SPEED`** (NOT CameraX/vision-camera) and measures true frame
delivery via `SENSOR_TIMESTAMP`. Verified clips with OpenCV (cv2) frame counts.

## Verified results (S10+, back camera, id 0)

HAL high-speed configs exposed to a **third-party** app:
- sizes: `1280x720`, `1920x1080`, `960x540`
- `1920x1080 -> [30,120], [120,120], [30,240], [240,240]`
- `1280x720  -> [30,120], [120,120], [30,240], [240,240]`

True camera delivery (median inter-frame gap from sensor timestamps — robust against
capture-callback double-firing):

| Request        | Unique frames | Median gap | True rate |
|----------------|---------------|------------|-----------|
| 1080p @ 240    | 640 (all unique) | 4.12 ms | **~242 fps** |
| 720p  @ 240    | 640 (all unique) | 4.12 ms | **~242 fps** |
| 1080p @ 120    | 400              | 8.33 ms | ~120 fps |

MP4 persistence via real-time **MediaRecorder (H.264)**:

| Config            | Distinct frames saved | Effective saved fps |
|-------------------|-----------------------|---------------------|
| 1080p @ 240 RT    | 188                   | ~118 fps |
| 720p  @ 240 RT    | 331 / 2.75s           | ~120 fps |
| 1080p @ 120 RT    | 328 / 2.74s           | ~120 fps (clean) |
| 1080p @ 240 slowmo (captureRate 240, playback 30) | 328 | ~124 fps (NO gain) |

## Conclusions

1. **Third-party Camera2 high-speed works on the S10+.** The earlier "Samsung won't expose
   high fps" belief is only true for the proprietary **960fps super-slow-mo**; standard
   **120/240** via `CONSTRAINED_HIGH_SPEED` is exposed and usable.
2. **The S10+ sensor genuinely delivers true ~242fps at full 1080p** to our app (4.12ms gaps,
   every frame a unique timestamp).
3. **vision-camera / CameraX cannot reach this** — CameraX has no high-speed capture. The
   live high-speed mode MUST use native Camera2 (Kotlin). The earlier "OpenCV-in-worklet via
   vision-camera" plan is therefore off the table for high-speed; it would cap at ~30fps.
4. **The Exynos 9820 H.264 hardware encoder caps real-time persistence at ~120fps** (true at
   both 720p and 1080p; slow-mo MediaRecorder did not help — frames arrive in real time and
   the encoder drops them). So to USE all 240 frames for detection we must pull frames off the
   **GPU/preview (SurfaceTexture) path** (OpenGL/Skia HSV threshold) rather than the H.264
   encoder. Saving a 240fps MP4 on the S10+ is not viable via MediaRecorder.
5. **120fps is fully usable end-to-end today** on the S10+ (clean capture; 4x better than
   30fps; cooler/less thermal risk). Recommended default on the S10+.
6. **240fps is sensor-confirmed**; full-rate detection on S10+ needs the GPU path. The **S22+**
   (Snapdragon 8 Gen 1 / Exynos 2200) has a much stronger encoder and should sustain true
   240fps even through the encoder — to be verified when that device is connected.

## Implication for the app architecture

- Live high-speed capture = **native Camera2 (Kotlin)**, selectable 120/240 mode + resolution.
- Detection on the recorded/streamed frames = OpenCV (Android SDK in the native module) or a
  GPU shader for the 240fps path; tap-to-sample HSV color calibration.
- "Live" = arm → swing → short burst → process in ~1-2s → report (no upload, no manual slow-mo).
- Recording-import mode (incl. stock-app slow-mo clips) = high-accuracy / verification path.
- Distance scale = manual known-distance caliper (carried over from the TS core).

## Repro

```bash
cd ~/projects/hs-probe
JAVA_HOME=~/.local/opt/jdk-17-temurin ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.hsprobe android.permission.CAMERA
adb shell am start -n com.hsprobe/.MainActivity --ei fps 240 --ei w 1920 --ei h 1080 --el durMs 3000
adb logcat -d -s HSPROBE     # look for CAMERA_DELIVERED ... medianGapMs
```
