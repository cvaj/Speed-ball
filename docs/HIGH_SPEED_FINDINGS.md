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

## Phase 5 decode/frame-timestamp proof attempt (S10+)

After the Phase 5 code-level implementation review closed, we ran the real app
again on the connected S10+ (`SM-G975U`). This is measured evidence, but it is
not a successful pairing proof. It shows the current record-then-decode path
fails loud before frame/timestamp pairing.

Command shape:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.speedball.app
adb shell am start -n com.speedball.app/.MainActivity --ez autoStart120 true
sleep 12
adb logcat -d -s SPEEDBALL_CAPTURE
```

Observed result:

```text
BURST_SUCCESS callbacks=512 uniqueTs=320 expected=300 min=240 medianGapMs=8.33 band=7.08..9.58 medianPass=true proof=true file=speed_ball_1280x720_120_1780226593751.mp4 bytes=6477067
DECODE_FAILURE reason=SENSOR_TIMESTAMP_NEAR_DUPLICATE message=Adjacent SENSOR_TIMESTAMP values were closer than the provisional near-duplicate threshold: 1.0E-6 ms. file=speed_ball_1280x720_120_1780226593751.mp4
DECODE_DIAGNOSTICS decoded=266 uniqueTs=320 sensorMedianMs=8.33 sensorMaxMs=8.38 ptsMedianMs=8.33 ptsMaxMs=8.39 dropThresholdMs=12.50 exactCount=false nearDuplicateMs=0.000001 clock=CAPTURE_BASED_CANDIDATE samples=
DECODE_SENSOR_GAPS_NS chunk=1 count=319 values=[1, 8333333, 8333333, 8333333, 8377500, ...]
DECODE_PTS_GAPS_US chunk=1 count=265 values=[8333, 8333, 8334, 8377, 8334, ...]
DECODE_PTS_SENSOR_OFFSETS_US count=0 values=[]
```

Interpretation:

- Phase 4 capture proof still passes for this run: nonzero MP4, enough unique
  timestamps by the Phase 4 gate, and median sensor gap in the 120 fps band.
- Phase 5 rejects the run before pairing because the sensor timestamp stream
  contains repeated 1 ns adjacent gaps. These are near-duplicates, not valid
  120 fps frame intervals.
- The decoded MediaExtractor sample count (`266`) also does not exactly match
  the unique sensor timestamp count (`320`), so exact-count reconciliation would
  fail even without the near-duplicate gate.
- PTS gaps stayed in the 120 fps cadence band for this run, with no PTS
  dropped-frame-sized gap observed.
- PTS-to-sensor offsets were not emitted because exact counts did not match.

Phase 5 device behavior is therefore **fail-loud proven, but not successful
pairing proven**. Later work must either find a better timestamp source for the
recorder stream, prove a value-anchored reconciliation strategy, or move to the
preview/GPU path before reporting any measured result.

## Phase 6 value-anchor device proof (S10+)

After the Phase 6 value-anchor diagnostics and privacy logging landed, we reran
the real app on the connected S10+ (`SM-G975U`) for the required anchor verdict
evidence.

Command shape:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.speedball.app
adb shell am start -n com.speedball.app/.MainActivity --ez autoStart120 true
sleep 12
adb logcat -d -s SPEEDBALL_CAPTURE
```

Observed result:

```text
MODES 1280x720 @ 120 fps:recordSupported=true, 1920x1080 @ 120 fps:recordSupported=true, 1280x720 @ 240 fps:recordSupported=true, 1920x1080 @ 240 fps:recordSupported=true
BURST_SUCCESS callbacks=520 uniqueTs=325 expected=300 min=240 medianGapMs=8.33 band=7.08..9.58 medianPass=true proof=true file=speed_ball_1280x720_120_1780249488438.mp4 bytes=6790131
TIMESTAMP_ANCHOR_DIAGNOSTIC file=speed_ball_1280x720_120_1780249488438.mp4 verdict=REJECTED reason=SENSOR_NEAR_DUPLICATE decoded=268 rawPositiveSensorTs=520 exactDistinctSensorTs=325 hypotheticalPostCollapseSensorTs=260 postCollapse=postCollapseStillMismatched nearDuplicateGroups=65 evaluatedCandidates=0 survivingMappings=0 maxResidualUs=n/a medianResidualUs=n/a presentationHoles=0 sensorHoles=0 holeAgreement=NO_HOLES failures=none
TIMESTAMP_ANCHOR_POST_COLLAPSE file=speed_ball_1280x720_120_1780249488438.mp4 interpretation=Post-collapse_sensor_count_still_differs_from_decoded_sample_count;_record-then-decode_may_have_encoder/frame-loss_issues_beyond_near-duplicate_callbacks._Evidence_only.
TIMESTAMP_ANCHOR_NEAR_DUPLICATE_GAPS_NS file=speed_ball_1280x720_120_1780249488438.mp4 chunk=1 count=16 values=[1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
DECODE_FAILURE reason=SENSOR_TIMESTAMP_NEAR_DUPLICATE message=Adjacent SENSOR_TIMESTAMP values were closer than the provisional near-duplicate threshold: 1.0E-6 ms. file=speed_ball_1280x720_120_1780249488438.mp4
DECODE_DIAGNOSTICS decoded=268 uniqueTs=325 sensorMedianMs=8.33 sensorMaxMs=8.38 ptsMedianMs=8.33 ptsMaxMs=8.39 dropThresholdMs=12.50 exactCount=false nearDuplicateMs=0.000001 clock=CAPTURE_BASED_CANDIDATE samples=
DECODE_PTS_SENSOR_OFFSETS_US count=0 values=[]
```

The debug APK built and installed successfully, wireless ADB reported the S10+
attached, and the manually unlocked run reached capture/decode. The burst proof
passed at 120 fps, but anchor diagnostics rejected the evidence before candidate
evaluation because near-duplicate `SENSOR_TIMESTAMP` callbacks were present.
Post-collapse sensor count was still mismatched (`260` hypothetical sensor
samples vs `268` decoded frames), so collapsing near-duplicates would still not
produce a measurement-ready pairing.

Interpretation:

- Phase 6 S10+ anchor evidence is now **measured** and rejects fail-loud with
  `SENSOR_NEAR_DUPLICATE`.
- No Phase 6 diagnostic `Proven` or measurement-consumable proof is claimed.
- Record-then-decode remains no-read on the S10+ for measurement because decoded
  frames still cannot be paired to sensor timestamps safely.

## What this proves / disproves

- ✅ Third-party Camera2 high-speed works on the S10+.
- ✅ The sensor delivers **true ~242 fps at 1080p** to our app.
- ❌ MediaRecorder/H.264 **cannot persist** more than ~120 fps on the Exynos 9820 (encoder
  ceiling) — so the 240 path needs GPU/preview frame access, not the encoder.
- ✅ 120 fps records cleanly end-to-end → a simple record-then-decode pipeline is viable now.
- ❌ The current Phase 5 exact-count record-then-decode path does **not** yet
  produce a verified decoded-frame-to-sensor-timestamp pairing on S10+; it fails
  loud on near-duplicate sensor gaps and decoded/sensor count mismatch.
- ❌ Phase 6 value-anchor S10+ device evidence is captured and rejects fail-loud:
  near-duplicate callbacks remain, post-collapse sensor count still mismatches
  decoded frames, and no measurement-ready pairing is produced.

## Open items carried into the plan

- Verify the same on the **S22+** (expected: true 240, likely persistable via its stronger encoder).
- Build the **GPU (SurfaceTexture + GLSL) frame path** for true 240 on the S10+.
- Find a verified per-frame timestamp pairing strategy for record-then-decode,
  or keep this path no-read and use the preview/GPU path for measurement.
