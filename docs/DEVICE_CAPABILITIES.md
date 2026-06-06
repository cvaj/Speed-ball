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
| Phase 7 preview-only app 720p @ 120 | 66 consumed preview / 66 sensor callbacks | **33.38 ms** | ~30 fps ❌ |

The sensor + pipeline genuinely deliver **true ~242 fps at full 1080p** to our app. (Raw
capture-callback counts were inflated on the 120 run by per-batch double-firing; the median
sensor-timestamp gap is the robust truth and was used here.)

The Phase 7 app preview-only `SurfaceTexture` proof is a separate session shape:
the S10+ accepted the constrained-high-speed session and Camera2 generated a
request list of size `4`, but the consumed preview stream ran at about 30 fps.
`SurfaceTexture.timestamp` exactly matched `SENSOR_TIMESTAMP` for consumed
frames, so the timestamp clock is usable only if a later session shape can deliver
true high-speed preview frames.

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

- **120 fps persists near 120 fps** → record-then-decode is the first recording
  route, but Phase 5/6/12 app evidence makes the measured S10+
  `1280x720 @ 120` record-then-decode route a scoped no-go for production
  measurement because decoded samples cannot be bound to surviving
  `SENSOR_TIMESTAMP` values.
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
| S10+ | **720p @ 120** | Capture proof is clean and coolest, but measurement remains no-read until a measurement-ready source route is proven. Record-then-decode Phase 5/6/12 is scoped no-go on measured `1280x720 @ 120` evidence: timestamp source is `REALTIME`, but count-independent PTS-to-sensor value-match is ambiguous/incomplete (`matched=248/257`, `ambiguous=62`, `longestCleanRun=3`). Preview/direct SurfaceTexture proof currently fails loud at about 30 fps consumed cadence. 1080p remains selectable. |
| S22+ | 720p @ 240 (pending verify) | Stronger encoder likely persists 240 directly. |

---

## 6. Practical capture constraints (Camera2 high-speed)

- A constrained-high-speed session is treated by the current architecture as allowing
  **at most 2 output surfaces**, which must be a **preview surface and/or a
  video-encoder surface**. Phase 12 records separate constrained `ImageReader`
  evidence for CPU-readable YUV and `PRIVATE`/video-encode usage surfaces before
  any source-route no-go; raw per-frame CPU access is not available directly in
  the accepted high-speed source shapes.
- **3A (auto-exposure/focus) is limited/locked** in high-speed mode → we set a **short, fixed
  shutter** to freeze the ball (also reduces motion blur) and fixed focus on the swing plane.
- Per-frame `SENSOR_TIMESTAMP` is available from the capture callback and is the source of
  truth for inter-frame Δt (proven accurate in the prototype).

## 7. Phase 12 No-Go Evidence Requirement

### Record-then-decode route

The S10+ `1280x720 @ 120` MediaRecorder record-then-decode route has a scoped
source no-go for production measurement. Phase 6 measured decoded `268`, exact
distinct sensor timestamps `325`, and hypothetical post-collapse sensor
timestamps `260`. Phase 12 measured
`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (`value=1`) and then ran a
count-independent PTS-to-sensor value-match against a fresh device burst. The
best measured offset was still not measurement-safe:
`verdict=AMBIGUOUS_MATCH`, `matched=248/257`, `ambiguous=62`, and
`longestCleanRun=3`. The app must keep this route no-read for production
measurement.

### Live GL/ImageReader source routes

A Phase 12 S10+ source-route no-go is valid only with a measured variant matrix
in this document and `docs/HIGH_SPEED_FINDINGS.md`.

Required rows:

| Variant | Consumer model | Session/API result | Producer cadence | captureCallbacks : frameAvailableCallbacks : consumedFrames | Direct cadence | Sensor cadence | Final gate | Interpretation |
|---|---|---|---|---|---|---|---|---|
| `companion-gl` | Single `SurfaceTexture` GL readback + companion encoder | Constrained high-speed accepted; request list `4` | `8.333 ms` median, in band | `48 : 6 : 6` | `33.378 ms` median | `8.333 ms` median | `INSUFFICIENT_DIRECT_FRAMES` | Producer is true 120 fps, but direct consumer still receives about 30 fps and only 6 frames. |
| `direct-gl-first` | Single `SurfaceTexture` GL readback + companion encoder, direct surface first | Constrained high-speed accepted; request list `4` | `8.333 ms` median, in band | `192 : 24 : 24` | `33.378 ms` median | `8.333 ms` median | `DIRECT_CADENCE_MISMATCH` | Reversing surface order reaches 24 frames but still about 30 fps consumed cadence. |
| `pbo-gl-readback` | GLES3 PBO-backed GL readback + companion encoder, direct surface first | Constrained high-speed accepted; request list `4` | `8.333 ms` median, in band | `200 : 25 : 24` | `33.378 ms` median | `8.333 ms` median | `DIRECT_CADENCE_MISMATCH` | PBO readback is accepted and producer cadence remains true 120 fps, but the direct consumer still consumes about 30 fps. |
| `direct-gl-only` | Single `SurfaceTexture` GL readback | Constrained high-speed accepted; request list `4` | `33.378 ms` median, below requested band | `24 : 24 : 24` | `33.378 ms` median | `33.378 ms` median | `DIRECT_CADENCE_MISMATCH` | Direct-only GL changes producer/session cadence to about 30 fps, so it is not a 120 fps source. |
| `constrained-image-reader` | `ImageReader` YUV same-image proof | Constrained high-speed rejected during session configuration | Not available | `0 : 0 : 0` | Not available | Not available | `SESSION_CONFIGURATION_FAILED` | HAL/API rejection is recorded; no ImageReader frames delivered in constrained high-speed. |
| `direct-estimate-preview-plus-gl-readback` | Preview `SurfaceTexture` plus GL readback `SurfaceTexture` | Constrained high-speed rejected during session configuration | Not available | `0 : 0 : 0` | Not available | Not available | `SESSION_CONFIGURATION_FAILED` | S10+ rejects the direct-estimate two-surface shape because the output surfaces must have different types; redacted evidence: `.interagent/tmp/two-surface-direct-estimate-rejection-2026-06-05.txt`. |
| `constrained-private-image-reader` | `ImageReader` `PRIVATE` with `USAGE_VIDEO_ENCODE` | Constrained high-speed accepted; request list `4` | `16.667 ms` median, below requested band | `12 : 0 : 0` | Not available | `16.667 ms` median | `MISSING_DIRECT_TIMESTAMPS` | The preview/encoder-like buffered surface is accepted, but no `ImageReader` callbacks or proof frames are delivered, so it is not a measurement-ready 120 fps source. |
| `standard-image-reader` | Standard Camera2 `ImageReader` YUV same-image proof | Standard Camera2 session accepted; request list `1` | `33.283 ms` median, below requested band | `24 : 24 : 24` | `33.283 ms` median | `33.283 ms` median | `DIRECT_CADENCE_MISMATCH` | Consumer-buffering path works, but producer cadence is below requested 120 fps, so its ratio cannot be used as constrained-route no-go evidence. |

The matrix must include consumer-buffering evidence. If `ImageReader` is
rejected by constrained high-speed session rules, accepted but callback-empty, or
accepted below producer band, record that result rather than assuming a generic
`ImageReader` outcome. If a standard Camera2 `ImageReader` or decoupled
GL/readback probe is used as the consumer-model check, record that route
separately from the constrained high-speed proof route. Standard-session
consumer probes cannot support a source-route no-go unless their producer
cadence is in the requested fps band.

Current Phase 12 code status: the app now has explicit source-route variant
identities, GL surface-order controls, a distinct `pbo-gl-readback` consumer
model, variant diagnostics, producer-cadence gating, and an ImageReader
same-snapshot pixel-proof contract. The measured S10+ rows above are device
evidence for those routes: constrained YUV ImageReader is rejected by session
configuration, constrained PRIVATE/video-encode ImageReader is accepted but
delivers no image callbacks and below-band producer cadence, standard ImageReader
is accepted but runs below the requested 120 fps producer band, and PBO-backed GL
remains accepted but still consumes about 30 fps.

This matrix is not a terminal source-route no-go. The remaining evidence-led
route is a reviewed equivalent non-coalescing constrained-session consumer, or
a reviewer-approved conclusion that the tested GL/ImageReader routes exhaust
the supported live-source options. The code must not label any current inline
`SurfaceTexture`/`glReadPixels` path as PBO or decoupled GL evidence.

---

## 8. Reproduce on any device

```bash
cd prototype/hs-probe
JAVA_HOME=~/.local/opt/jdk-17-temurin ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.hsprobe android.permission.CAMERA
adb shell am start -n com.hsprobe/.MainActivity --ei fps 240 --ei w 1920 --ei h 1080 --el durMs 3000
adb logcat -d -s HSPROBE   # CAMERA_DELIVERED ... medianGapMs is the true frame rate
```
