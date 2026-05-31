# Speed-ball — Architecture & Implementation Plan

**Status:** preliminary, for adversarial review (Claude ↔ Codex) before implementation.
This is the detailed architecture and phased plan — not every micro-detail is solved; the
intent is that a reviewer can challenge the structure, the math, the ordering, the proof
obligations, and the risk handling before any code is written.

Companion docs: `DEVICE_CAPABILITIES.md` (verified phone abilities), `HIGH_SPEED_FINDINGS.md`
(de-risk evidence).

---

## 1. Goal & scope

Measure a hit softball's **exit velocity (mph), launch/flight angle, and drag-corrected
trajectory** from a phone on a tripod, with two modes:

1. **Live (primary):** arm → someone swings → short high-speed burst (2–3 s) → process
   on-device → **on-screen report within ~1–2 s.** No upload, no manual slow-mo, no desktop.
2. **Import (secondary, high-accuracy):** pick a recorded clip (including stock-camera
   240/960 fps slow-mo) → run the same detector offline for verification/sampling.

Detection = **neon-color centroid tracking**: tap the ball once to sample its color; track
that blob across frames; fit velocity; compute angle and trajectory.

### Non-goals (v1)
- Spin/Magnus effects in the trajectory (drag only).
- Multi-ball / multi-object tracking.
- iOS (architecture keeps the math portable; iOS would be a separate AVFoundation app later).
- Sub-1-mph accuracy. Target: **within ~5 mph of actual.**

---

## 2. Constraints & assumptions (challenge these in review)

- **C1.** Live high-speed requires **native Camera2** (CameraX/vision-camera can't). Verified.
- **C2.** S10+ delivers **true ~242 fps @ 1080p**, but its H.264 encoder caps MP4 persistence
  at ~120 fps → **120 = record-then-decode; 240 = GPU/preview frame path.** Verified.
- **C3.** High-speed sessions allow only **preview + encoder surfaces** (no `ImageReader`/CPU).
- **C4.** 3A is locked in high-speed → we **fix a short shutter + fixed focus** on the swing
  plane (also reduces motion blur).
- **A1.** Camera is placed **side-on, ~perpendicular to the swing/ball plane** (~12 ft to the
  side, framed on contact). Velocity is measured **in the image plane**; off-axis framing
  introduces foreshortening error. This is a setup requirement surfaced in the UI.
- **A2.** One neon ball is the dominant high-saturation blob in the ROI during flight.
- **A3.** Adequate lighting (outdoor/bright) so a short shutter still yields a detectable blob.
- **A4.** Per-frame `SENSOR_TIMESTAMP` order matches decoded/processed frame order (validated
  by frame-count reconciliation; see C2 — divergence is the 240/encoder signal).

---

## 3. High-level architecture

```
                ┌───────────────────────────────────────────────┐
                │                    :app (Android)              │
                │                                                │
  Camera2 HS ──▶│  capture/   ── frames + sensorTimestamps ──┐   │
  (120: record  │   - HighSpeedSession (Camera2)             │   │
   240: GLES)   │   - RecordPath (MediaRecorder→decode)      ▼   │
                │   - GpuPath (SurfaceTexture→GLSL mask)   detection/        │
                │                                          - HsvSampler      │
  Import video ─▶│  import/  ── frames + PTS ──────────────▶ - BlobTracker   │
                │                                          (centroid+radius) │
                │                                              │             │
                │   calibration/ (distance caliper, color)     ▼             │
                │                                          List<Detection>   │
                │                                              │             │
                │                                              ▼             │
                │   ui/ (Compose: Capture, Calibrate,   ┌── :core (pure JVM) │
                │        Results, Import, ModeSelector)  │   velocity fit    │
                │                                        │   calibration     │
                │   ◀──────── VelocityResult ───────────┤   units           │
                │   ◀──────── Trajectory ───────────────┤   trajectory(drag)│
                └────────────────────────────────────────┴───────────────────┘
```

- **`:core`** — pure-Kotlin/JVM module, **no Android deps**, fully unit-tested (golden values
  ported from the verified TS core). Holds all math: velocity fit, calibration, units,
  trajectory physics, and the shared data types.
- **`:app`** — Android: Camera2 capture, OpenCV detection, the GL 240 path, Compose UI, and
  the orchestration that turns frames → detections → `VelocityResult` → `Trajectory`.

---

## 4. Module breakdown

### 4.1 `:core` (pure Kotlin, JVM-tested)
- `Detection(t: Double /*s*/, x: Double, y: Double /*raw px, y-down*/)`
- `velocity/VelocityFit.kt` — OLS line fit per axis + leave-one-out outlier rejection.
- `calibration/Scale.kt` — `pixelsPerFoot(...)` from the distance caliper.
- `units/Units.kt` — px/s → ft/s → mph; ft/in/cm → ft.
- `physics/Trajectory.kt` — RK4 projectile-with-drag.
- `model/` — `VelocityResult`, `Trajectory`, `LaunchSetup`, `BallSpec`, `AirSpec`.

### 4.2 `:app/capture`
- `HighSpeedCamera.kt` — enumerate HAL high-speed configs; open back camera; build a
  `CameraConstrainedHighSpeedCaptureSession`; expose available `{fps × size}` combos.
- `RecordPath.kt` — 120 fps: preview + MediaRecorder; capture-callback collects
  `SENSOR_TIMESTAMP[]`; on stop, decode the clip (MediaCodec→ImageReader or
  `MediaMetadataRetriever.getFramesAtIndex`) and pair frame i ↔ timestamp i.
- `GpuPath.kt` — 240 fps (S10+): preview = `SurfaceTexture`; per frame, render to a GL
  texture and run a GLSL HSV-`inRange` shader at reduced res; reduce to a centroid
  (glReadPixels of a small mask, e.g. 160×90, → CPU centroid). Δt from `SENSOR_TIMESTAMP`.

### 4.3 `:app/detection`
- `HsvSampler.kt` — sample median HSV over a small disk at the tap point → tolerance band.
- `BlobTracker.kt` — per frame: RGB→HSV, `inRange`, morphology open+close, largest contour,
  centroid (image moments) + radius; gating (area bounds, max inter-frame jump); returns a
  `Detection?`. (OpenCV Android SDK.)
- `FlightWindow.kt` — pick the contiguous clean in-flight detections (post-contact) for the fit.

### 4.4 `:app/calibration`
- `DistanceCaliper.kt` (+ Compose UI) — mark two points a known real distance apart in the
  swing plane → `pixelsPerFoot`. Ports the 1-DOF caliper UX from the RN demo.
- `ColorCalibration.kt` — drives `HsvSampler` from a tap on a frozen preview frame.

### 4.5 `:app/ui` (Jetpack Compose)
- `ModeSelectorScreen` — fps {120,240} × res {720p,1080p}, populated from device HAL; defaults
  per `DEVICE_CAPABILITIES.md`.
- `CaptureScreen` — live preview, color-sample tap, arm/record, progress.
- `CalibrateScreen` — distance caliper.
- `ResultsScreen` — mph (large), launch angle, R²/RMS, trajectory plot (Compose Canvas),
  apex/carry/hang; fail-loud "No read" state.
- `ImportScreen` — pick a video, run the offline pipeline.

---

## 5. Measurement math (concrete — ported from the verified TS core)

Inputs: detections `{(t_i, x_i, y_i)}`, `t_i` from sensor timestamps (seconds), pixels y-down.

1. **OLS line fit** per axis: `x(t)=x0+vx·t`, `y(t)=y0+vy·t`.
2. **Leave-one-out rejection:** compute full-fit RMS residual; for each point, refit without
   it; if the largest RMS improvement exceeds a threshold and `n−1 ≥ 3`, drop that point;
   repeat up to k times. Report `used`, `R²`, `rmsResidualPx`.
3. **Speed:** `speedPx = √(vx²+vy²)` [px/s]; `ftps = speedPx / pixelsPerFoot`;
   `mph = ftps × 0.6818182`.
4. **Launch angle (elevation):** `θ = atan2(−vy, |vx|)` in degrees (image y-down → up-positive).
   Valid only when the camera is ~perpendicular to the swing plane (A1).
5. **Fail-loud:** throw / "No read" if `<3` detections, non-finite fit, or `pixelsPerFoot ≤ 0`.

**Golden reference (regression anchor):** the bundled `hitting2` 3-point set must reproduce the
TS core's result (~6.9 mph, ~17° elevation, R² ≈ 0.995, 377.5 px/ft). This is a cross-impl
correctness check between the TS core and the Kotlin port.

---

## 6. Physics: trajectory with aerodynamic drag (concrete)

2D point-mass in the swing plane, **quadratic drag**, RK4 integration.

**Constants (defaults; tunable in UI):**
- `g = 9.81 m/s²`
- 12-inch softball: diameter `d = 0.0955 m` (circumference 0.30 m), mass `m = 0.1899 kg`
  (≈6.7 oz), area `A = π(d/2)² ≈ 7.16e-3 m²`
- air density `ρ = 1.225 kg/m³` (sea level, 15 °C; adjustable for temp/altitude)
- drag coefficient `Cd = 0.40` (softball; Re/spin dependence ignored in v1 — flag for review)
- drag constant `k = 0.5·ρ·Cd·A / m` [1/m]

**Initial conditions** from the measurement: `v0 = mph/2.237` m/s, `vx0 = v0·cosθ`,
`vy0 = v0·sinθ`.

**Equations** (wind `w = (wx, wy)`, default 0; `vrel = (vx−wx, vy−wy)`):
```
dx/dt = vx
dy/dt = vy
dvx/dt = −k·|vrel|·(vx−wx)
dvy/dt = −g − k·|vrel|·(vy−wy)
```
Integrate RK4 with `dt = 1 ms` from launch height until `y` returns to launch height (or 0),
capped at a max time. **Outputs:** trajectory polyline, apex height, carry (horizontal
distance), hang time.

**Test anchors:**
- `Cd = 0` (no drag) → matches closed form `range = v0²·sin(2θ)/g`, `apex = (v0·sinθ)²/2g`
  (within RK4 tolerance).
- `Cd > 0` → range strictly less than no-drag; monotonic in Cd.
- Hand-calc one full case (e.g. 70 mph @ 25°) and assert apex/carry/hang to tolerance.

---

## 7. Capture pipeline design

### 7.1 120 fps — record-then-decode (works on S10+ today)
1. High-speed session: `[SurfaceView preview, MediaRecorder]`, fixed short shutter.
2. Record burst (durMs ~2500–3000). Capture callback collects `SENSOR_TIMESTAMP[]`.
3. Stop; decode frames in order; pair decoded frame i ↔ timestamp i (FIFO; reconcile counts).
4. `BlobTracker` per frame → detections; `FlightWindow` → fit → result. ~1–3 s for ~360–720 frames.

### 7.2 240 fps — GPU/preview path (S10+; needed because encoder caps 120)
1. High-speed session with `SurfaceTexture` preview.
2. Each frame → GL texture; GLSL fragment shader does HSV `inRange` → small mask
   (e.g. downscaled 160×90).
3. Reduce mask → centroid (glReadPixels small buffer → CPU centroid, or GPU reduction).
4. Δt from `SENSOR_TIMESTAMP`. Detections streamed; fit at end of burst.

### 7.3 S22+ 240 (pending verify)
If its encoder persists 240 to MP4 (likely), reuse 7.1 for 240 too; otherwise use 7.2.

### 7.4 Timestamp pairing (R2)
Capture callback yields one `SENSOR_TIMESTAMP` per frame in order. For 120 (clean) the decoded
frame count == timestamp count → pair by index. For the encoder-dropped 240 case the counts
diverge — exactly the signal that the GPU path is required.

---

## 8. Detection design

- **Color sample:** tap the ball on a frozen preview frame → median HSV over an ~8 px disk →
  band `H±ΔH`, `S∈[Smin,255]`, `V∈[Vmin,255]` (neon ⇒ high S,V).
- **Per frame:** RGB→HSV, `inRange` → mask, morphology open(3×3)+close, largest connected
  component, centroid via moments (`m10/m00, m01/m00`), radius from area. Gate on area bounds
  and max plausible inter-frame jump.
- **In-flight selection:** detect the displacement spike at contact; take the contiguous clean
  detections after it. Fail-loud if `<3`.

**R7 — foliage robustness (real risk; the demo background is a forest):** a neon yellow-green
ball is high-saturation/high-value vs duller foliage; defend with tight S/V floors, an ROI
band along the expected ball path, largest-blob + motion gating, and a user-adjustable
tolerance. Document a setup tip (frame the ball path against sky where possible).

---

## 9. UI / UX flow

1. **Mode** — pick fps×res (HAL-filtered), see a heat hint for 240 on S10+.
2. **Calibrate distance** — caliper on a still frame; save `pixelsPerFoot`.
3. **Sample color** — tap the ball in preview; preview the mask overlay; adjust tolerance.
4. **Capture** — tripod, arm, swing; short burst auto/triggered; processing spinner.
5. **Results** — mph, launch angle, R²/RMS, trajectory plot, apex/carry/hang; "No read" on
   fail-loud. Re-track / recalibrate / home.
6. **Import** — same pipeline on a chosen video (incl. stock slow-mo) for verification.

---

## 10. Tech stack & dependencies

- Kotlin **2.2.0**, AGP **8.13.1**, Gradle **9.3.1** (all proven by the prototype build),
  `compileSdk 35`, `minSdk 26`, `targetSdk 35`, JDK 17.
- **Camera2** (`android.hardware.camera2`) — high-speed capture. **No CameraX.**
- **OpenCV** `org.opencv:opencv:4.13.0` (verified on Maven Central — simple Gradle dep, no
  manual SDK import).
- **Jetpack Compose** (compose-bom) — UI. Compose Canvas for the trajectory plot.
- **Coroutines** for async capture/processing.
- `:core` is JVM-only with **JUnit** (fast, no emulator) for golden-value tests.

---

## 11. Testing strategy (correctness-first, golden values)

1. **`:core` golden values** — port the 24 TS tests verbatim (same inputs/expected): velocity
   fit, LOO, calibration, units. The `hitting2` 3-point → ~6.9 mph anchor is a cross-impl check.
2. **Trajectory** — no-drag closed-form match (Cd=0); drag monotonicity; one hand-calc case.
3. **Detection** — run `BlobTracker` on the bundled `hitting2` frames; assert centroids within
   ±2 px of known positions; mask robustness against the forest background.
4. **Pipeline** — frames → detections → mph within tolerance on a known clip.
5. **On-device** — real swings; **120 vs 240 consistency**; compare to the RN demo's 6.9 mph
   reference; thermal check on sustained 240 (S10+).

No mocking of math/detection. Real frames, real OpenCV, real in-memory data.

---

## 12. Risk register

| # | Risk | Mitigation |
|---|---|---|
| R1 | 240 frame access on S10+ (encoder caps 120) | GPU/SurfaceTexture+GLSL path; 120 default; 240 verified-capable |
| R2 | Per-frame timestamp ↔ decoded-frame pairing | Capture `SENSOR_TIMESTAMP[]`; reconcile counts; FIFO order |
| R3 | High-speed sessions: no CPU `ImageReader` | Route via preview (GPU) or encoder only — baked into design |
| R4 | Motion blur (slow shutter) | Fixed short shutter (3A locked anyway); document lighting |
| R5 | Off-axis camera → foreshortening | Side-on setup requirement surfaced in UI; image-plane caveat |
| R6 | Thermal at sustained 240 (S10+) | 720p default, short bursts, duty-cycle guard |
| R7 | Color tracking vs green foliage | Tight S/V band, ROI, largest-blob + motion gating, adjustable tol |
| R8 | OpenCV native integration | `org.opencv:opencv:4.13.0` Maven Central; verified available |
| R9 | S22+ unverified | Run `hs-probe` on S22+ before relying on its real-time 240 |

---

## 13. Phased implementation plan (subtask queue for review)

Each subtask lists **deps**, whether it's **parallel-safe**, and its **proof obligation**.
Build to a working **120 fps tool first**, then add the 240 GPU path.

- **S0 — De-risk high-speed capture.** ✅ DONE (`prototype/hs-probe`, `HIGH_SPEED_FINDINGS.md`).
- **S1 — Repo + module skeleton.** `:core` (JVM) + `:app` (Android, Compose), Gradle wired,
  builds empty. App identity: **label "Speed-ball", applicationId `com.speedball.app`**.
  _Deps: S0. Parallel: no (foundation). Proof: `./gradlew assembleDebug` + `:core:test` run green empty._
- **S2 — `:core` measurement math.** Port velocity fit + LOO + calibration + units; golden
  tests. _Deps: S1. Parallel: with S4a. Proof: 24 ported golden tests pass; hitting2 → ~6.9 mph._
- **S3 — `:core` trajectory physics.** RK4 + drag; golden tests. _Deps: S1. Parallel: with S2.
  Proof: Cd=0 matches closed form; one hand-calc case within tolerance._
- **S4a — Camera2 high-speed capture (120).** Enumerate combos; record burst; collect
  `SENSOR_TIMESTAMP[]`. _Deps: S1. Parallel: with S2/S3. Proof: on-device burst saved + timestamps logged (mirrors prototype)._
- **S4b — Decode + frame/timestamp pairing.** _Deps: S4a. Proof: decoded frame count == timestamp count at 120; sample frames extracted._
- **S5 — OpenCV detection (HSV sample + BlobTracker).** _Deps: S1, S4b. Proof: centroids on hitting2 frames within ±2 px; mask survives forest bg._
- **S6 — Distance caliper calibration (UI + core wire).** _Deps: S1, S2. Parallel: with S5. Proof: known-gap clip → correct px/ft; matches RN demo readout._
- **S7 — Pipeline + Results UI (120 end-to-end).** detections → fit → trajectory → report;
  fail-loud. _Deps: S2,S3,S4b,S5,S6. Proof: real 120 swing → plausible mph/angle/trajectory; 6.9 mph clip reproduced._
- **S8 — 240 GPU path (SurfaceTexture→GLSL→centroid).** _Deps: S5,S7. Proof: 240 burst on S10+ yields ~2× the detections of 120 over the same window; consistent mph._
- **S9 — Import mode (offline video, incl. slow-mo).** _Deps: S5,S7. Proof: stock slow-mo clip → mph consistent with live._
- **S10 — Hardening:** thermal/duty-cycle guards, mode defaults, S22+ verification, UX polish,
  on-device test matrix. _Deps: S7+. Proof: 120/240 × 720/1080 matrix runs; thermal acceptable._

---

## 14. Deliberately deferred (not solved here — fine for v1 plan)

- Exact GLSL centroid-reduction kernel (S8 detail).
- Auto-trigger "swing detected" heuristic vs manual record button (start manual; add auto later).
- Cd as a function of Reynolds number / spin; Magnus lift.
- iOS / cross-platform.
- Persisting/cataloging past measurements (history DB).

---

## 15. Review questions for Codex/Claude

1. Is **record-then-decode at 120 first, GPU path for 240 second** the right sequencing, or
   should the GPU path be built up front to avoid two capture codepaths?
2. Is the **drag model (constant Cd=0.40, no Magnus)** acceptable for ±5 mph / trajectory, or
   do we need Re-dependent Cd in v1?
3. Is **image-plane velocity with a side-on camera** (A1) acceptable, or do we need a second
   reference to correct for off-axis/foreshortening?
4. Is the **timestamp-pairing reconciliation (A4/R2)** robust enough, or do we need explicit
   per-frame timestamp embedding?
5. Foliage robustness (R7): tight HSV + ROI + motion gating — sufficient, or do we need a
   learned/background-subtraction step?
