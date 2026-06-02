# Phase 12 Live GL/ImageReader Source No-Go Draft

Status: proposed for adversarial review; not final until Claude/Codex review
converges.

## Scope

This draft no-go is scoped only to the live Camera2 constrained-high-speed
GL/ImageReader source class on the S10+ (`SM-G975U`, Android 12), using the
documented 1280x720 at 120 fps mode.

The scoped source class includes:

- single `SurfaceTexture` GL readback with companion encoder;
- direct-first single `SurfaceTexture` GL readback with companion encoder;
- GLES3 PBO-backed GL readback with companion encoder;
- direct-only single `SurfaceTexture` GL readback;
- constrained high-speed YUV `ImageReader`;
- constrained high-speed `PRIVATE`/video-encode usage `ImageReader`;
- standard Camera2 `ImageReader` as a consumer-model control.

## Proposed Decision

The live GL/ImageReader source class on the S10+ constrained high-speed session
does not currently provide a 120 fps measurement-ready source for v1.

This is a source-route no-go for that scoped class only. It is not a product
completion claim, not a device-level unsupported claim, and not a statement that
all S10+ capture strategies are exhausted.

## Non-Claims

This draft does not claim:

- S10+ is unsupported for v1;
- Phase 12 produced a token-eligible source;
- user-visible mph, angle, trajectory, carry, apex, hang time, or confidence is
  available;
- record-then-decode is impossible;
- import/offline workflows are impossible;
- a `PRIVATE`/usage-flagged ImageReader can provide a proof source without
  delivered image callbacks and a reviewed `HardwareBuffer` pixel-proof path;
- another Android device will behave the same way.

The record-then-decode route remains open per the Phase 12 F3 guardrail. The
current Phase 5/6 record-then-decode evidence remains no-read for measurement
until decoded frames can be safely paired to `SENSOR_TIMESTAMP`, but that route
is not closed by this live GL/ImageReader no-go.

## Evidence Matrix

Measured S10+ variant matrix:

| Variant | Source class | Session/API result | Producer cadence | Ratio | Final gate | Interpretation |
|---|---|---|---|---|---|---|
| `companion-gl` | Inline GL + companion encoder | Constrained high-speed accepted; request list `4` | `8.333 ms` median, in band | `48 : 6 : 6` | `INSUFFICIENT_DIRECT_FRAMES` | Producer is true 120 fps, but the direct consumer receives about 30 fps and only 6 frames. |
| `direct-gl-first` | Inline GL + companion encoder, direct surface first | Constrained high-speed accepted; request list `4` | `8.333 ms` median, in band | `192 : 24 : 24` | `DIRECT_CADENCE_MISMATCH` | Reversing surface order reaches 24 frames but still consumes at about 30 fps. |
| `pbo-gl-readback` | GLES3 PBO-backed GL + companion encoder, direct surface first | Constrained high-speed accepted; request list `4` | `8.333 ms` median, in band | `200 : 25 : 24` | `DIRECT_CADENCE_MISMATCH` | Decoupling CPU readback does not fix the consumed cadence; the direct consumer still consumes at about 30 fps. |
| `direct-gl-only` | Inline GL only | Constrained high-speed accepted; request list `4` | `33.378 ms` median, below band | `24 : 24 : 24` | `DIRECT_CADENCE_MISMATCH` | Removing the companion encoder changes producer/session cadence to about 30 fps. |
| `constrained-image-reader` | YUV `ImageReader` same-image proof | Constrained high-speed rejected during session configuration | Not available | `0 : 0 : 0` | `SESSION_CONFIGURATION_FAILED` | The CPU-readable YUV buffering consumer is rejected by the constrained high-speed API/HAL. |
| `constrained-private-image-reader` | `PRIVATE` `ImageReader` with `USAGE_VIDEO_ENCODE` | Constrained high-speed accepted; request list `4` | `16.667 ms` median, below requested band | `12 : 0 : 0` | `MISSING_DIRECT_TIMESTAMPS` | The preview/encoder-like buffered consumer is accepted, but no `ImageReader` callbacks or proof frames are delivered. |
| `standard-image-reader` | Standard Camera2 YUV `ImageReader` same-image proof | Standard Camera2 session accepted; request list `1` | `33.283 ms` median, below requested band | `24 : 24 : 24` | `DIRECT_CADENCE_MISMATCH` | The consumer-buffering path works, but producer cadence is below 120 fps, so this cannot prove the constrained high-speed source route. |

## Preconditions Check

- Consumer-buffering evidence exists: constrained YUV `ImageReader` was
  attempted and rejected by session configuration; constrained PRIVATE/video-
  encode `ImageReader` was accepted but delivered zero image callbacks and ran
  below the requested producer band; standard YUV `ImageReader` was accepted as
  a control but ran below the requested producer band.
- Decoupled GL evidence exists: `pbo-gl-readback` is a real GLES3 PBO route and
  was device-exercised. It kept producer cadence in band but still consumed at
  about 30 fps.
- Standard-session consumer ratios are not overused: the standard `ImageReader`
  row is documented as below-band producer evidence and does not carry the
  constrained-route no-go by itself.
- Decoder evidence remains separate: Phase 5/6 record-then-decode remains
  no-read for measurement, but the route remains open and is not closed by this
  draft.
- Production output remains fail-loud no-read: no variant mints token
  eligibility or produces measurement values.

## Rationale

The companion-backed GL rows prove the Camera2 producer can run at 120 fps while
the direct `SurfaceTexture` consumer receives only about 30 fps. Reversing
surface order and decoupling CPU readback with PBOs do not change that consumed
cadence. Direct-only GL and standard `ImageReader` both fall to about 30 fps at
the producer/session level, so they do not provide a 120 fps source. Constrained
YUV `ImageReader` is rejected by the HAL/API. Constrained PRIVATE/video-encode
`ImageReader`, the public API buffered route most similar to preview/encoder
surfaces, is accepted but produces no image callbacks or direct proof frames and
runs below the requested producer band.

Within the tested public Camera2/GL/ImageReader live source class, the remaining
failure appears structural: the constrained high-speed routes that keep producer
cadence at 120 fps still do not expose 120 fps consumed frames to app code, and
the tested `ImageReader` routes either reject, produce no image callbacks, or
run below the requested producer cadence on this device.

## Consequences If Approved

If this draft is approved, Phase 12 records a scoped source-route no-go for the
live GL/ImageReader source class on S10+ constrained high speed.

The next supported-device strategy must not build Phase 13 live measurement
integration on this source class. The next route remains a separate reviewed
work item, likely record-then-decode or another explicitly proposed source
strategy, with its own pairing proof and fail-loud gates.

## Review Questions

1. Is the scope narrow enough to avoid a false device-level no-go?
2. Does the measured matrix satisfy the Phase 12 no-go preconditions for the
   live GL/ImageReader source class?
3. Is there another public-API non-coalescing constrained-session consumer that
   must be tested before this scoped no-go can be approved?
4. Are the record-then-decode and product-completion boundaries explicit enough?
