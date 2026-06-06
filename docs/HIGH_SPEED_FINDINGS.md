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

## Phase 12 record-then-decode source decision (S10+)

After Gate 2 approval for the record-then-decode source decision, we added a
developer-only timestamp-source characteristic read and ran it on the connected
S10+ (`SM-G975U`, Android 12).

Command shape:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:installDebug
adb shell pm grant com.speedball.app android.permission.CAMERA
adb logcat -c
adb shell am start -n com.speedball.app/.MainActivity --ez autoLogTimestampSource true
adb logcat -d -s SPEEDBALL_CAPTURE
```

Observed result:

```text
CAMERA_TIMESTAMP_SOURCE camera=back cameraIdClass=numeric-1chars source=REALTIME value=1 sharedClockCandidate=true
```

After Claude's implementation review correctly flagged that the legacy
`DECODE_PTS_SENSOR_OFFSETS_US count=0` line was count-gated and index-zipped, we
added a count-independent value-match diagnostic and reran the S10+ 120 fps
record-then-decode proof.

Observed value-match result:

```text
BURST_SUCCESS callbacks=496 uniqueTs=310 expected=300 min=240 medianGapMs=8.33 band=7.08..9.58 medianPass=true proof=true file=speed_ball_1280x720_120_1780290458149.mp4 bytes=6417935
TIMESTAMP_ANCHOR_DIAGNOSTIC file=speed_ball_1280x720_120_1780290458149.mp4 verdict=REJECTED reason=SENSOR_NEAR_DUPLICATE decoded=257 rawPositiveSensorTs=496 exactDistinctSensorTs=310 hypotheticalPostCollapseSensorTs=248 postCollapse=postCollapseStillMismatched nearDuplicateGroups=62 evaluatedCandidates=0 survivingMappings=0 maxResidualUs=n/a medianResidualUs=n/a presentationHoles=0 sensorHoles=0 holeAgreement=NO_HOLES failures=none
DECODE_PTS_SENSOR_VALUE_MATCH verdict=AMBIGUOUS_MATCH decoded=257 uniqueTs=310 evaluatedOffsets=8487 toleranceUs=750 matched=248 unambiguous=186 ambiguous=62 longestCleanRun=3 maxResidualUs=6 medianResidualUs=3 offsetUs=1102031621721
```

Interpretation:

- The back camera reports `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`, so shared
  clock analysis is supported for surfaces that expose their frame timestamps.
- A count-independent value-match was measured against the full exact sensor
  timestamp set. It does not rescue the route: the best offset matched only
  `248` of `257` decoded samples, `62` matched frames were ambiguous because of
  near-duplicate sensor timestamps, and the longest contiguous unambiguous run
  was `3` frames.
- Encoder-frame loss is unrecoverable through the tested public MediaRecorder
  route because no per-frame accepted-vs-emitted identity is exposed and the
  measured `REALTIME` value-match did not recover one.
- Sensor-callback duplication does not rescue the route because the deterministic
  exact-distinct and post-collapse counts both mismatch decoded sample count.
- The S10+ `1280x720 @ 120 fps` record-then-decode route is therefore a scoped
  no-go for production measurement. It remains no-read and emits no token, mph,
  angle, or trajectory.

## Phase 7 preview timestamp proof (S10+)

After adding the decoder-free `SurfaceTexture` preview timestamp proof and pure
preview pairer, we ran the real app on the connected S10+ (`SM-G975U`) using the
developer auto-start hook. The device was launched over Wi-Fi ADB, and the debug
proof window was allowed to show over keyguard for this diagnostic run.

Command shape:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.speedball.app android.permission.CAMERA
adb logcat -c
adb shell am force-stop com.speedball.app
adb shell am start -n com.speedball.app/.MainActivity --ez autoStartPreview120 true
sleep 12
adb logcat -d -s SPEEDBALL_CAPTURE
```

Observed result:

```text
PREVIEW_PROOF_WINDOW debugShowWhenLocked=true
MODES 1280x720 @ 120 fps:recordSupported=true, 1920x1080 @ 120 fps:recordSupported=true, 1280x720 @ 240 fps:recordSupported=true, 1920x1080 @ 240 fps:recordSupported=true
PREVIEW_PATH_START mode=1280x720_@_120_fps
PREVIEW_HIGH_SPEED_REQUEST_LIST mode=1280x720_@_120_fps requests=4
PREVIEW_TIMESTAMP_SPIKE_FAILURE mode=1280x720_@_120_fps reason=PREVIEW_CADENCE_MISMATCH message=Median_SurfaceTexture_timestamp_gap_was_outside_the_requested_fps_band.
PREVIEW_FRAME_DIAGNOSTICS mode=1280x720_@_120_fps verdict=FAILURE reason=PREVIEW_CADENCE_MISMATCH rawPreviewTs=66 positivePreviewTs=66 uniquePreviewTs=66 rawSensorTs=66 positiveSensorTs=66 uniqueSensorTs=66 previewMedianMs=33.3775 previewMaxMs=33.377553 sensorMedianMs=33.3775 sensorMaxMs=33.377553 expectedMs=8.333333333333334 dropThresholdMs=12.5 exactMatches=66 sensorMembership=66 unmatchedLeadingPreview=0 unmatchedTrailingPreview=0 coalescing=false pairingVerdict=REJECTED
PREVIEW_TIMESTAMP_OFFSETS_NS mode=1280x720_@_120_fps chunk=1 count=64 values=[0, 0, 0, 0, ...]
```

Interpretation:

- The S10+ accepts the preview-only constrained-high-speed `SurfaceTexture`
  session; this is not a session-configuration rejection.
- Camera2 returns a high-speed request list of size `4`.
- For every consumed preview frame, `SurfaceTexture.timestamp` exactly matches a
  `SENSOR_TIMESTAMP` value, so the shared-clock hypothesis is proven for consumed
  frames on this device.
- The consumed preview stream is not 120 fps. Median preview and sensor gaps are
  `33.3775 ms`, with max gap `33.377553 ms`. That is roughly 30 fps,
  not the requested 120 fps.
- Phase 7 therefore correctly remains **fail-loud/no-read** with
  `PREVIEW_CADENCE_MISMATCH`; no measurement-ready preview path is claimed.

## What this proves / disproves

- ✅ Third-party Camera2 high-speed works on the S10+.
- ✅ The sensor delivers **true ~242 fps at 1080p** to our app.
- ❌ MediaRecorder/H.264 **cannot persist** more than ~120 fps on the Exynos 9820 (encoder
  ceiling) — so the 240 path needs GPU/preview frame access, not the encoder.
- ✅ 120 fps persists near 120 fps through MediaRecorder, so
  record-then-decode remains the first recording route to evaluate.
- ❌ The current Phase 5 exact-count record-then-decode path does **not** yet
  produce a verified decoded-frame-to-sensor-timestamp pairing on S10+; it fails
  loud on near-duplicate sensor gaps and decoded/sensor count mismatch.
- ❌ Phase 6 value-anchor S10+ device evidence is captured and rejects fail-loud:
  near-duplicate callbacks remain, post-collapse sensor count still mismatches
  decoded frames, and no measurement-ready pairing is produced.
- ❌ Phase 12 record-then-decode source decision is scoped no-go on S10+
  `1280x720 @ 120`: timestamp source is `REALTIME`, but the measured
  count-independent PTS-to-sensor value-match is ambiguous/incomplete
  (`matched=248/257`, `ambiguous=62`, `longestCleanRun=3`).
- ✅ Phase 7 proves `SurfaceTexture.timestamp == SENSOR_TIMESTAMP` for consumed
  preview frames on the S10+.
- ❌ Phase 7 preview-only `SurfaceTexture` delivery on the S10+ is not 120 fps in
  the measured app path; it fails loud with `PREVIEW_CADENCE_MISMATCH`.

## Open items carried into the plan

- Verify the same on the **S22+** (expected: true 240, likely persistable via its stronger encoder).
- Build the **GPU (SurfaceTexture + GLSL) frame path** for true 240 on the S10+.
- Continue the reviewed preview/direct source route or test another device. The
  S10+ record-then-decode route is scoped no-go for measurement on the measured
  `1280x720 @ 120` path.
- Investigate whether the S10+ requires a companion encoder surface, vendor
  camera constraints, or a different session shape before a preview/GPU path can
  consume true high-speed frames.
- Phase 12 live-source no-go evidence remains separate from the
  record-then-decode source no-go and must stay scoped to measured routes.

## Phase 9 direct companion proof (S10+)

Phase 9 now has an app-owned debug entry point:

```bash
adb shell am start -n com.speedball.app/.MainActivity --ez autoStartDirectProof120 true
```

The implemented run shape is companion encoder plus direct
`SurfaceTexture`/GL readback in one constrained high-speed session, followed by
the preview-only control only after companion teardown and scratch deletion.
The companion MP4 is app-private cache data only; it is not decoded, imported,
path-logged, or used for proof.

Observed S10+ result:

```text
DIRECT_PROOF_START mode=1280x720_@_120_fps shape=COMPANION_ENCODER
DIRECT_CAPTURE_SCRATCH_PREPARED mode=1280x720_@_120_fps
DIRECT_CAPTURE_GL_SETUP_READY mode=1280x720_@_120_fps
DIRECT_CAPTURE_HIGH_SPEED_REQUEST_LIST mode=1280x720_@_120_fps requests=4
DIRECT_CAPTURE_FRAME_READBACK count=1 timestamp=1079441694556966
DIRECT_CAPTURE_RELEASE_DONE failure=null scratch=DELETED
DIRECT_PROOF_COMPANION mode=1280x720_@_120_fps shape=COMPANION_ENCODER requestListSize=4 directCount=1 sensorCount=8 pixelCount=1 verdict=CAPTURED
DIRECT_PROOF_PREVIEW mode=1280x720_@_120_fps shape=PREVIEW_ONLY_CONTROL verdict=PREVIEW_CADENCE_MISMATCH
DIRECT_PROOF_RESULT mode=1280x720_@_120_fps verdict=INSUFFICIENT_DIRECT_FRAMES
```

App-private cache check after the run showed only the empty
`cache/speed-ball-companion` directory and no scratch MP4 file.

Interpretation:

- The S10+ accepts the companion encoder plus direct `SurfaceTexture` session
  and Camera2 returns a high-speed request list of size `4`.
- The app proved one same-update direct GL pixel readback and captured matching
  aggregate pixel proof metadata without reading the companion MP4.
- The companion scratch file was deleted.
- This is still not a measurement-ready source: the current direct proof window
  captured only one direct timestamp/pixel proof, while eight sensor timestamps
  arrived. The proof runner therefore rejects before token eligibility with
  `INSUFFICIENT_DIRECT_FRAMES`.
- The preview-only control still reproduces the known
  `PREVIEW_CADENCE_MISMATCH` result.

The proof token path remains bound to the vetted direct measurement input, and
no token is minted from this evidence. The app remains fail-loud no-read on the
S10+ until the direct path can capture at least 12 consumed same-update frames
and prove cadence and membership against `SENSOR_TIMESTAMP`.

## Phase 11 direct readback target/diagnostics proof (S10+)

Phase 11 raised the direct readback target above the token minimum and added
root-cause diagnostics. The companion MP4 remains app-private cache data only;
it is not decoded, imported, path-logged, or used for proof.

Command shape:

```bash
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.speedball.app android.permission.CAMERA
adb logcat -c
adb shell am force-stop com.speedball.app
adb shell am start -n com.speedball.app/.MainActivity --ez autoStartDirectProof120 true
sleep 14
adb logcat -d -s SPEEDBALL_CAPTURE
adb shell run-as com.speedball.app find cache -maxdepth 3 -type f -o -type d
```

Observed S10+ result:

```text
DIRECT_PROOF_START mode=1280x720_@_120_fps shape=COMPANION_ENCODER
DIRECT_CAPTURE_SCRATCH_PREPARED mode=1280x720_@_120_fps
DIRECT_CAPTURE_GL_SETUP_READY mode=1280x720_@_120_fps
DIRECT_CAPTURE_HIGH_SPEED_REQUEST_LIST mode=1280x720_@_120_fps requests=4
DIRECT_CAPTURE_FRAME_READBACK count=1 timestamp=1085549956763347 readbackMs=4.089 frameCallbacks=1 captureCallbacks=8
DIRECT_CAPTURE_RELEASE_STEP name=stopRepeating elapsedMs=2.433 failed=false
DIRECT_CAPTURE_RELEASE_STEP name=closeSession elapsedMs=0.105 failed=false
DIRECT_CAPTURE_RELEASE_STEP name=closeCamera elapsedMs=3862.540 failed=false
DIRECT_CAPTURE_RELEASE_STEP name=releaseGl elapsedMs=2.219 failed=false
DIRECT_CAPTURE_RELEASE_STEP name=releaseCompanion elapsedMs=1328.836 failed=false
DIRECT_CAPTURE_RELEASE_STEP name=quitThread elapsedMs=0.042 failed=false
DIRECT_CAPTURE_RELEASE_DONE failure=null scratch=DELETED steps=6
DIRECT_PROOF_COMPANION mode=1280x720_@_120_fps shape=COMPANION_ENCODER requestListSize=4 directCount=6 sensorCount=48 pixelCount=6 frameCallbacks=6 captureCallbacks=48 appended=6 readbacks=6 readbackMedianMs=0.940 readbackMaxMs=4.089 releaseSteps=6 verdict=CAPTURED
DIRECT_PROOF_PREVIEW mode=1280x720_@_120_fps shape=PREVIEW_ONLY_CONTROL verdict=PREVIEW_CADENCE_MISMATCH
DIRECT_PROOF_RESULT mode=1280x720_@_120_fps verdict=INSUFFICIENT_DIRECT_FRAMES directMedianMs=33.378 directMaxMs=33.378 sensorMedianMs=8.333 sensorMaxMs=8.378 finalGate=INSUFFICIENT_DIRECT_FRAMES frameCallbacks=6 captureCallbacks=48 appended=6 readbacks=6 readbackMedianMs=0.940 readbackMaxMs=4.089 releaseSteps=6
```

App-private cache check after the run showed only:

```text
cache
cache/speed-ball-companion
```

Interpretation:

- The one-frame stopgap is gone; the S10+ consumed six same-update direct
  readbacks before duration expiry, but token eligibility still requires at
  least 12 whole-stream-clean frames.
- The direct `SurfaceTexture` stream is still not consuming at 120 fps in this
  session shape. Direct median/max gap was `33.378 ms`, while sensor callbacks
  remained at 120 fps (`8.333 ms` median, `8.378 ms` max).
- Readback work itself was not the dominant timing cost in this run:
  median readback was `0.940 ms`, max `4.089 ms`.
- Teardown diagnostics identify slow release steps: camera close took about
  `3862.540 ms`, and companion cleanup took about `1328.836 ms`.
- Scratch cleanup succeeded and no scratch MP4 remained in app-private cache.
- The result remains fail-loud no-read with `INSUFFICIENT_DIRECT_FRAMES`; no
  token, mph, angle, trajectory, or production measurement result is claimed.

## Phase 12 source-route unblock implementation status

Phase 12 now has code-level variant scaffolding plus S10+ device proof for the
implemented variant matrix. The following S10+ run was captured after wiring
variant selection and ImageReader probes:

```bash
adb shell am start -n com.speedball.app/.MainActivity \
  --ez autoStartDirectProof120 true \
  --es directProofVariant <variant-id>
```

- Implemented variants: baseline companion+GL, direct-first companion+GL,
  PBO-backed companion+GL, direct-only GL, constrained YUV ImageReader,
  constrained PRIVATE/video-encode ImageReader, and standard ImageReader.
- The GL Camera2 path now uses the selected ordered surface roles when creating
  the constrained high-speed session and request targets.
- The PBO-backed GL route is a real separate consumer model (`pbo-gl-readback`):
  it creates a GLES3 context, issues `glReadPixels` into a pixel-pack buffer,
  and maps the previous callback's PBO on the next callback. It is not an alias
  for the inline `SurfaceTexture`/`glReadPixels` path.
- The debug entry point accepts `--es directProofVariant <id>` with ids from
  `plannedDirectProofVariants()`, so adb can exercise each route independently.
- Diagnostics now include variant id, consumer model, surface order, buffer
  size, request-list size, producer/capture callback cadence, and whether
  producer cadence is in the requested fps band.
- The ImageReader pixel-proof contract is implemented as a same-acquired-image
  snapshot path feeding the shared aggregate signature builder, with fail-loud
  timestamp/conversion handling and close-after-signature behavior.
- The Camera2 ImageReader session path is wired for constrained high-speed YUV,
  constrained high-speed PRIVATE/video-encode usage, and standard Camera2 probes;
  all routes were device-exercised in the matrix below.

Measured S10+ variant matrix:

| Variant | Session result | Producer cadence | Ratio | Final gate |
|---|---|---|---|---|
| `companion-gl` | constrained high-speed accepted, request list `4` | `8.333 ms` median, in band | `48 : 6 : 6` | `INSUFFICIENT_DIRECT_FRAMES` |
| `direct-gl-first` | constrained high-speed accepted, request list `4` | `8.333 ms` median, in band | `192 : 24 : 24` | `DIRECT_CADENCE_MISMATCH` |
| `pbo-gl-readback` | constrained high-speed accepted, request list `4` | `8.333 ms` median, in band | `200 : 25 : 24` | `DIRECT_CADENCE_MISMATCH` |
| `direct-gl-only` | constrained high-speed accepted, request list `4` | `33.378 ms` median, below band | `24 : 24 : 24` | `DIRECT_CADENCE_MISMATCH` |
| `constrained-image-reader` | constrained high-speed session rejected | Not available | `0 : 0 : 0` | `SESSION_CONFIGURATION_FAILED` |
| `direct-estimate-preview-plus-gl-readback` | constrained high-speed session rejected | Not available | `0 : 0 : 0` | `SESSION_CONFIGURATION_FAILED` |
| `constrained-private-image-reader` | constrained high-speed accepted, request list `4` | `16.667 ms` median, below band | `12 : 0 : 0` | `MISSING_DIRECT_TIMESTAMPS` |
| `standard-image-reader` | standard Camera2 session accepted, request list `1` | `33.283 ms` median, below band | `24 : 24 : 24` | `DIRECT_CADENCE_MISMATCH` |

Interpretation:

- The companion-backed GL variants prove the producer can run at 120 fps, but
  the direct consumer still receives about 30 fps.
- The PBO-backed GL variant is accepted by the same constrained 120 fps session
  and keeps producer cadence in band, but it still consumes about 30 fps
  (`DIRECT_CADENCE_MISMATCH`), so PBO readback did not unblock the source on
  this S10+ run.
- Direct-only GL and standard ImageReader both produce about 30 fps, so they
  cannot prove a 120 fps source route.
- The constrained high-speed YUV ImageReader target was device-exercised and
  rejected during session configuration.
- The direct-estimate preview-plus-GL/readback two-`SurfaceTexture` target shape
  was device-exercised on S10+ and rejected during constrained high-speed session
  configuration because the output surfaces must have different types. Redacted
  evidence is in `.interagent/tmp/two-surface-direct-estimate-rejection-2026-06-05.txt`.
- The constrained high-speed PRIVATE/video-encode ImageReader target was
  device-exercised and accepted, but delivered zero `ImageReader` callbacks and
  zero direct proof frames while producer callbacks were below the requested
  120 fps band.
- The standard ImageReader consumer-buffering route is device-exercised and
  functional, but because producer cadence is below the requested band, its
  consumer ratio cannot satisfy source-route no-go evidence by itself.
- The remaining decision is review-bound: either identify another equivalent
  non-coalescing constrained-session consumer to test, or conclude by reviewed
  evidence that the tested GL/ImageReader live-source routes do not provide a
  120 fps measurement-ready source on this S10+.
