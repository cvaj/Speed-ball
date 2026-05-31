# Device Capabilities — Samsung Galaxy S10+ and S22+

Everything we have learned (and measured) about the target phones' camera abilities, and
what it means for a high-speed ball-tracking app. The S10+ numbers are **measured on-device**
via the `hs-probe` prototype; the S22+ numbers are **inferred** and flagged as TO-VERIFY.

---

## 1. The two API tiers (this is the crux)

There are two completely different "high-speed" tiers on Samsung phones, and they are
routinely conflated:

| Tier | Frame rates | Exposed to third-party apps? |
|---|---|---|
| **Standard `CONSTRAINED_HIGH_SPEED_VIDEO`** (Android Camera2) | 120, 240 fps | **YES** — any native app that calls Camera2 directly |
| **Samsung "Super Slow-mo"** (960 fps) | 960 fps | **NO** — proprietary HAL extension, stock camera app only |

"Camera2" is the standard Android OS camera **API** (code any app calls), *not* an app and
*not* the Samsung camera app. **CameraX** (and therefore `react-native-vision-camera`) is a
higher-level wrapper on Camera2 that **does not support high-speed sessions at all** — which
is why a vision-camera approach caps at ~30 fps regardless of device.

**Conclusion:** 120/240 fps is reachable by our app via native Camera2. 960 fps is not
(except by importing a clip the stock app recorded — see the recording-import mode).

---

## 2. Galaxy S10+ (SM-G975U, Exynos 9820) — MEASURED

Probed with `prototype/hs-probe` (Camera2 `CONSTRAINED_HIGH_SPEED`, back camera id 0).

### High-speed configurations the HAL exposes to a third-party app
- High-speed sizes: **1280×720, 1920×1080, 960×540**
- `1920×1080 → [30,120], [120,120], [30,240], [240,240]`
- `1280×720  → [30,120], [120,120], [30,240], [240,240]`
- `960×540   → [30,120], [120,120]`

### True frame delivery (from `SENSOR_TIMESTAMP`, median inter-frame gap)
| Request | Unique frames | Median gap | True rate |
|---|---|---|---|
| 1080p @ 240 | 640 (all unique) | **4.12 ms** | **~242 fps** ✅ |
| 720p @ 240 | 640 (all unique) | **4.12 ms** | **~242 fps** ✅ |
| 1080p @ 120 | 400 | **8.33 ms** | ~120 fps ✅ |
| Phase 4 app 720p @ 120 | 325 | **8.33 ms** | ~120 fps ✅ |

The sensor + pipeline genuinely deliver **true ~242 fps at full 1080p** to our app. (Raw
capture-callback counts were inflated on the 120 run by per-batch double-firing; the median
sensor-timestamp gap is the robust truth and was used here.)

### MP4 persistence via real-time MediaRecorder (H.264) — THE bottleneck
| Config | Distinct frames saved | Effective saved fps |
|---|---|---|
| 1080p @ 240 (real-time) | 188 | ~118 fps |
| 720p @ 240 (real-time) | 331 / 2.75 s | ~120 fps |
| 1080p @ 120 (real-time) | 328 / 2.74 s | ~120 fps (clean) |
| 1080p @ 240 (slow-mo tag) | 328 | ~124 fps (no gain) |
| Phase 4 app 720p @ 120 | nonzero MP4, 6,460,459 bytes | timestamp proof passed |

**The Exynos 9820 H.264 hardware encoder caps real-time persistence at ~120 fps** (true at
both 720p and 1080p; slow-mo tagging did not help — frames arrive in real time and the
encoder drops them). Therefore:

- **120 fps records cleanly end-to-end** → simple record-then-decode pipeline works.
- **240 fps frames exist but cannot be saved via MediaRecorder** → the 240 path must read
  frames off the **GPU/preview (SurfaceTexture)** surface, not the encoder.

### Thermal note
Exynos 9820 runs warm. 240 fps sustained is the main heat risk. Mitigations: **720p default**
(2.25× less pixel throughput than 1080p across sensor/ISP/memory/detect), short bursts (2–3 s
per swing, low duty cycle), and a duty-cycle guard.

---

## 3. Galaxy S22+ (SM-S906, Snapdragon 8 Gen 1 / Exynos 2200) — TO VERIFY

Not yet probed (device not connected). Expectations, to confirm by running `hs-probe`:

- **Almost certainly exposes 1080p @ 120/240** via the same Camera2 high-speed API.
- **Much stronger H.264/HEVC encoder** (SD 8 Gen 1 does 4K@120, 1080p@240+) → likely
  **persists true 240 fps to MP4 in real time**, so the simpler record-then-decode pipeline
  may cover 240 there without the GPU path.
- More thermal/processing headroom → 240 sustained is lower-risk than on the S10+.

**Action:** run `hs-probe` on the S22+ and record results here before relying on real-time 240
recording for that device.

---

## 4. Quality: 720p vs 1080p for centroid tracking

For tracking the centroid of a colored blob, resolution barely matters:

- The centroid averages over all blob pixels; precision improves only as ~1/√(blob pixels).
  A ~30 px ball at 1080p vs ~20 px at 720p both sit far above the ~5 px noise floor → the
  difference is ~±0.2 px of per-frame jitter.
- Velocity = displacement (tens–hundreds of px) ÷ time, so a sub-pixel centroid error is
  **<1% of velocity.** Resolution is not a dominant error term.
- Dominant error sources, in order: **(1) distance calibration, (2) motion blur
  (shutter×speed — identical at 720p/1080p), (3) frame timing.**
- **1080p only helps when the ball is small in frame** (camera far / very wide shot), keeping
  the blob above the pixel floor. Hence both resolutions are user-selectable.

**Recommendation:** S10+ default **720p** (cooler, ample accuracy); offer 1080p for small-ball
framing.

---

## 5. Selectable capture modes (hard requirement)

The app exposes **fps {120, 240} × resolution {720p, 1080p}**, populated from the device's
actual HAL configs (only show combos the device reports). Suggested defaults:

| Device | Default | Notes |
|---|---|---|
| S10+ | **720p @ 120** | Clean end-to-end today, coolest, ±5 mph easily met. 720p @ 240 for more samples (GPU path, mild extra heat). 1080p available. |
| S22+ | 720p @ 240 (pending verify) | Stronger encoder likely persists 240 directly. |

---

## 6. Practical capture constraints (Camera2 high-speed)

- A constrained-high-speed session allows **at most 2 output surfaces**, which must be a
  **preview surface and/or a video-encoder surface** — **no `ImageReader` (CPU) output.** So
  raw per-frame CPU access is not available directly; frames come via preview (GPU) or the
  encoder.
- **3A (auto-exposure/focus) is limited/locked** in high-speed mode → we set a **short, fixed
  shutter** to freeze the ball (also reduces motion blur) and fixed focus on the swing plane.
- Per-frame `SENSOR_TIMESTAMP` is available from the capture callback and is the source of
  truth for inter-frame Δt (proven accurate in the prototype).

---

## 7. Reproduce on any device

```bash
cd prototype/hs-probe
JAVA_HOME=~/.local/opt/jdk-17-temurin ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.hsprobe android.permission.CAMERA
adb shell am start -n com.hsprobe/.MainActivity --ei fps 240 --ei w 1920 --ei h 1080 --el durMs 3000
adb logcat -d -s HSPROBE   # CAMERA_DELIVERED ... medianGapMs is the true frame rate
```
