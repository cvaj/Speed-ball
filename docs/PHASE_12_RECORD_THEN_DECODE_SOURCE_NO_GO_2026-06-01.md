# Phase 12 Record-Then-Decode Source Decision - S10+ Scoped No-Go

Date: 2026-06-01

Scope: Samsung Galaxy S10+ `SM-G975U`, Android 12, back camera, app
`1280x720 @ 120 fps` MediaRecorder record-then-decode route.

## Verdict

The S10+ record-then-decode route remains no-read and is a scoped source no-go
for production measurement on this device/configuration. The route can persist
a clean 120 fps MP4 cadence, but the public Android surfaces exercised so far
do not expose a measurement-safe identity binding from decoded samples back to
the corresponding `SENSOR_TIMESTAMP` values.

This does not claim S10+ hardware cannot capture high-speed frames. It also
does not claim other devices, imported media, stock camera slow-motion clips, or
future reviewed source routes are unsupported.

## Evidence

Phase 6 S10+ record-then-decode proof:

- decoded video samples: `268`;
- raw positive capture callback timestamps: `520`;
- exact distinct positive `SENSOR_TIMESTAMP` values: `325`;
- hypothetical post-collapse sensor timestamp count: `260`;
- near-duplicate groups with representative `1 ns` gaps;
- value-anchor verdict: `SENSOR_NEAR_DUPLICATE`;
- legacy `DECODE_PTS_SENSOR_OFFSETS_US count=0`, which means the
  count-gated/index-zipped offset diagnostic was skipped under count mismatch;
- no `DecodeOutcome.Success`, timing proof token, speed, angle, or trajectory.

Phase 12 timestamp-source characteristic read:

```text
06-01 00:47:21.557 30372 30372 I SPEEDBALL_CAPTURE: CAMERA_TIMESTAMP_SOURCE camera=back cameraIdClass=numeric-1chars source=REALTIME value=1 sharedClockCandidate=true
```

The `REALTIME` characteristic supports shared-clock analysis for surfaces whose
timestamps are exposed, but it does not identify which captured sensor frames
survived into the encoded MP4. It therefore does not repair the count and
identity mismatch.

Phase 12 count-independent PTS-to-sensor value-match rerun:

```text
BURST_SUCCESS callbacks=496 uniqueTs=310 expected=300 min=240 medianGapMs=8.33 band=7.08..9.58 medianPass=true proof=true file=speed_ball_1280x720_120_1780290458149.mp4 bytes=6417935
TIMESTAMP_ANCHOR_DIAGNOSTIC file=speed_ball_1280x720_120_1780290458149.mp4 verdict=REJECTED reason=SENSOR_NEAR_DUPLICATE decoded=257 rawPositiveSensorTs=496 exactDistinctSensorTs=310 hypotheticalPostCollapseSensorTs=248 postCollapse=postCollapseStillMismatched nearDuplicateGroups=62 evaluatedCandidates=0 survivingMappings=0 maxResidualUs=n/a medianResidualUs=n/a presentationHoles=0 sensorHoles=0 holeAgreement=NO_HOLES failures=none
DECODE_PTS_SENSOR_VALUE_MATCH verdict=AMBIGUOUS_MATCH decoded=257 uniqueTs=310 evaluatedOffsets=8487 toleranceUs=750 matched=248 unambiguous=186 ambiguous=62 longestCleanRun=3 maxResidualUs=6 medianResidualUs=3 offsetUs=1102031621721
```

This diagnostic is count-independent: it searches one constant offset from
decoded PTS values into the full exact sensor-timestamp set instead of requiring
equal counts or pairing by index. The best measured candidate still cannot
produce a measurement-safe binding: only `248` of `257` decoded samples bind,
`62` matched frames are ambiguous because near-duplicate sensor timestamps are
indistinguishable at microsecond PTS precision, and the longest contiguous
unambiguous run is `3` frames, below the reviewed `12`-frame clean-source
minimum.

The decoder under-extraction branch is ruled out by code: `BurstVideoDecoder`
iterates `MediaExtractor.sampleTime` until end-of-stream and hard-fails on its
work bound, and `DecodedVideoMetadata.frameCount` is the decoded PTS list size.
The `268` decoded count is therefore the container sample count observed by the
app, not a partial app-side read.

## Mechanism Assessment

The remaining failure is fatal by exhaustion over the public routes that could
bind decoded frames to sensor timestamps:

- Encoder-frame loss is unrecoverable because no public Android API used by
  the tested MediaRecorder route exposes which captured `SENSOR_TIMESTAMP`
  values survived encoding. The count-independent value-match rerun tested the
  plausible `REALTIME` clock binding route and still failed with ambiguous and
  incomplete matches.
- Sensor-callback duplication does not rescue the route because exact distinct
  timestamps and deterministic post-collapse counts both differ from decoded
  count in the Phase 6 proof (`325` / `260` vs `268`) and in the Phase 12
  value-match rerun (`310` / `248` vs `257`). Any alternate collapse would need
  an independently specified deterministic rule, not a threshold tuned to force
  decoded count.
- Container PTS cadence is clean enough to show a 120 fps encoded stream, but
  PTS alone is not the timing authority. A count-independent value-match can use
  PTS as a lookup key only if it recovers unambiguous real `SENSOR_TIMESTAMP`
  values; the measured rerun did not.
- App-owned `MediaCodec` input/output correlation is foreclosed for the tested
  constrained high-speed route because the camera writes into an opaque encoder
  input `Surface`; per-frame source identity is not exposed without a reviewed
  non-coalescing GL interpose route.
- `eglPresentationTimeANDROID` stamping through a GL interpose remains
  foreclosed by the Phase 12 live GL/ImageReader no-go unless a new reviewed
  120 fps non-coalescing GL route is proven.

## Consequence

For this S10+ record-then-decode route, production must continue to return
no-read. Phase 13 live measurement integration must not use this source, and
this decision must not emit or imply a measurement token, mph, launch angle, or
trajectory value.
