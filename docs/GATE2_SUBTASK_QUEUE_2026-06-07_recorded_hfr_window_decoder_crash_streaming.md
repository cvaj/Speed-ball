# Gate 2 Subtask Queue: Recorded-HFR Window Decoder Crash Streaming

Date: 2026-06-07

Gate 1 plan:

- `docs/IMPL_PLAN_2026-06-07_recorded_hfr_window_decoder_crash_streaming.md`

Workstream:

- `recorded-hfr-window-decoder-crash-streaming`

## Non-Negotiable Constraints

- No production recorded-HFR window route may call `ImageReader`,
  `MediaCodec.getOutputImage()`, `Image.getPlanes()`, or `.planes`.
- Decoder output must be consumed through `MediaCodec.getOutputBuffer(index)`.
- Unsupported or ambiguous decoder output must return fail-loud No Read, never a
  plausible mph and never a fallback to `Image` plane APIs.
- Unsupported or opaque output on the target S10+ is not an acceptable final
  proof. It is a failed viability proof that either requires adding tested
  support for the measured S10+ format or reopening Gate 1 for a different
  non-`Image` route.
- The Android window source must be pull-streaming: at most one decoded full-size
  frame is live while processing a `nextFrame()` call.
- The Android source must not retain `MutableList<ImportVideoFrame>` or
  full-window ARGB.
- Window bounds, strict increasing PTS, decoded-window proof, timeout proof, and
  S10+ device proof are part of the implementation, not optional polish.

## Subtask 0: S10+ ByteBuffer Viability Spike

Run this before building the full replacement decoder path.

Purpose:

- Prove the target S10+ emits manually convertible raw YUV from
  `MediaCodec.getOutputBuffer(index)`.
- Avoid spending implementation time on a ByteBuffer route that can only return
  perpetual unsupported-format No Read on the target device.

Required behavior:

- Use a minimal one-frame/short-window decoder probe on the S10+ in byte-buffer
  mode.
- Configure with `codec.configure(format, null, null, 0)`.
- Optionally request a known format through the input/output format when Android
  honors it, but always record the actual `codec.outputFormat`.
- Read and log:
  - `MediaFormat.KEY_COLOR_FORMAT`;
  - `MediaFormat.KEY_STRIDE`;
  - `MediaFormat.KEY_SLICE_HEIGHT`;
  - source width/height;
  - output buffer capacity;
  - emitted PTS.
- Confirm the output is manually convertible raw YUV:
  - I420;
  - NV12;
  - a vendor semiplanar layout that can be proven by byte-order/padding tests.
- If the S10+ emits `COLOR_FormatYUV420Flexible` only through
  `getOutputImage().getPlanes()`, UBWC/opaque, or another layout that cannot be
  manually decoded from `getOutputBuffer`, stop this Gate 2 implementation lane.
  Record the evidence and reopen Gate 1 for a GL-readback alternative such as
  decode-to-`SurfaceTexture` plus `glReadPixels`, which still avoids
  `Image.getPlanes()`.

Acceptance:

- The measured S10+ color format must be brought into the supported conversion
  set with byte-order, stride, and slice-height tests before the implementation
  can claim S10+ viability.
- An unsupported-format No Read on the S10+ target device is a failed device
  proof, not a passing terminal No Read.
- The final device proof must show a real decode on S10+: either an estimate or
  a No Read for a non-format reason such as no ball, insufficient detections, bad
  PTS, or invalid calibration.

## Subtask 1: ByteBuffer YUV Conversion Contract

Create a small import-domain decoder conversion unit around byte-buffer output.

Required behavior:

- Input includes:
  - output `ByteBuffer`;
  - source width/height;
  - target width/height;
  - `MediaFormat.KEY_COLOR_FORMAT`;
  - `MediaFormat.KEY_STRIDE`;
  - `MediaFormat.KEY_SLICE_HEIGHT`;
  - presentation timestamp.
- Supported formats for the first implementation:
  - `MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar` / I420;
  - `MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar` / NV12;
  - the measured S10+ output format if the viability spike shows it is manually
    convertible raw YUV;
  - any Qualcomm/vendor semiplanar constants only after explicit test coverage
    proves byte order and padding.
- Use stride and slice height for every luma/chroma read.
- Validate capacity before reads; fail loud if indexes would exceed buffer
  bounds.
- Convert/downscale into one `ImportVideoFrame` at the requested working
  resolution.
- Do not use `Bitmap` for the production decode conversion unless tests prove it
  does not retain full-window state; direct integer downscale is preferred.

Tests:

- I420 tightly packed.
- I420 with `stride > width` and `sliceHeight > height`.
- NV12 tightly packed.
- NV12 with `stride > width` and `sliceHeight > height`.
- NV21 must either be explicitly supported with tests or fail loud as
  unsupported; do not silently interpret NV21 as NV12.
- Unsupported color format returns No Read with color format and dimensions.
- Missing or invalid stride/slice-height returns No Read.
- Buffer too small for reported padded layout returns No Read.
- Converted colors are asserted with deterministic synthetic pixels, not only
  "non-empty output".
- Non-1:1 downscale test: source dimensions differ from target dimensions, for
  example `1920x1080 -> 1280x720`, with a known color block in source space
  asserted at the correct output region. This must prove the source-to-target
  mapping, not only that output dimensions are correct.
- Measured S10+ output format test: once Subtask 0 records the actual format,
  add a synthetic ByteBuffer test for that exact layout or fail the queue back
  to Gate 1 if it is not manually convertible.

## Subtask 2: Pull-Streaming Android MediaCodec Source

Refactor `AndroidRecordedHfrWindowFrameSource` into a live pull source.

Required behavior:

- `create(...)` validates file/window/target/deadline inputs and returns
  `ImportValidationResult.Success(source)` only after extractor/codec are ready.
- Configure codec with byte-buffer output:
  - `codec.configure(format, null, null, 0)`.
- Do not create `ImageReader`; do not use a render surface.
- `nextFrame()`:
  - checks closed/terminal state;
  - feeds input samples while needed;
  - drains output with bounded dequeue calls;
  - skips sync-prefix outputs before `windowStartUs` while counting them;
  - stops after `windowEndUs`, EOS, `window.maxFrames`, timeout, or error;
  - validates strict increasing PTS for emitted frames;
  - converts exactly one in-window output buffer to `ImportVideoFrame`;
  - releases every output buffer exactly once.
- Provide a finalized proof accessor such as `decodedProof()` or mutable `proof`
  that remains valid after `close()`.
- Represent decoder terminal failures as source-level No Read or an internal
  terminal state that `runRecordedWindowEstimate` can surface as No Read.

Tests/source guards:

- Source guard forbids `ImageReader`, `getOutputImage`, `Image.getPlanes`, and
  `.planes` in `AndroidRecordedHfrWindowFrameSource`.
- Source guard requires `getOutputBuffer(` and `configure(format, null, null, 0)`.
- Source guard forbids `MutableList<ImportVideoFrame>` and `frames +=
  imageToFrame` in `AndroidRecordedHfrWindowFrameSource`.
- Window source proof unit tests keep existing JVM fake-source tests for
  `ContainerTimeWindow` behavior and add assertions for proof shape expected by
  the new pull source.

## Subtask 3: Lifecycle, Early Termination, And Timeout

Make decoder ownership safe under streaming estimator early returns.

Required behavior:

- `close()` is idempotent.
- `close()` releases:
  - extractor;
  - codec stop/release;
  - any outstanding output buffer;
  - handler/thread resources if introduced.
- `RecordedHfrStreamingEstimate.estimate(...)` must keep `source.close()` in a
  `finally`; source-guard this if necessary.
- Early No Read, resource limit, cancellation, invalid frame metadata, timeout,
  and exception paths must all release decoder resources.
- Decode timeout must be cumulative from source creation and enforced during
  pull-mode `nextFrame()`.
- Timeout returns fail-loud No Read and proof has `timedOut = true`.

Tests:

- Fake pull source records `close()` on success.
- Fake pull source records `close()` on early No Read from invalid frame metadata.
- Fake pull source records `close()` on cancellation.
- Fake pull source records `close()` on source exception.
- Android source lifecycle helper tests prove idempotent close can be called
  multiple times without double-release failures.
- Timeout path produces No Read/proof and releases resources.

## Subtask 4: MainActivity Integration And No-Read Surfacing

Wire the pull decoder result into `runRecordedWindowEstimate`.

Required behavior:

- `runRecordedWindowEstimate` handles:
  - source creation No Read;
  - source timeout No Read;
  - unsupported color format No Read;
  - decoded zero frames No Read;
  - decoded-window validation No Read;
  - estimator No Read.
- The result UI must show terminal No Read/proof instead of crashing.
- Logs must disclose:
  - requested window start/end;
  - decoded emitted count;
  - source width/height;
  - output color format;
  - stride/slice-height;
  - timeout flag;
  - no-read reason when conversion is unsupported.
- No path reports mph from unsupported decoder output, timeout, invalid padding,
  insufficient decoded frames, or invalid PTS.

Tests:

- Source guard verifies `runRecordedWindowEstimate` still uses
  `AndroidRecordedHfrWindowFrameSource.create`.
- No Read propagation tests for unsupported color format and timeout if those can
  be isolated without Android framework.
- Existing UI source tests updated only if display text changes.

## Subtask 5: Memory-Bound And Retention Proofs

Prove the trigger actually bounds memory.

Required behavior:

- Android decoder source retains no full-window frame list.
- Estimator retains only:
  - bounded candidate records without full ARGB;
  - bounded proof thumbnails;
  - current one-frame `ImportVideoFrame` while processing.
- Candidate retention cap remains enforced.
- Source thumbnails remain bounded by `maxProofFrames`.

Tests/source guards:

- Source guard: no `List<ImportVideoFrame>` constructor parameter in
  `AndroidRecordedHfrWindowFrameSource`.
- Source guard: no `mutableListOf<ImportVideoFrame>` in
  `AndroidRecordedHfrWindowFrameSource`.
- Existing retained-candidate tests continue to prove candidate records exclude
  full-frame ARGB.
- Add/keep a stress-shaped unit test with many non-candidate frames and few
  candidates proving retained candidate count and proof thumbnail count stay
  bounded.

## Subtask 6: Docs And Security

Update documentation with the actual behavior.

Required docs:

- `docs/HOW_THE_APPLICATION_WORKS.md`
- `docs/DATA_FLOW.md`
- `docs/FUNCTIONAL_TEST_REGISTRY.md`
- `docs/SECURITY_CHECKLIST.md`
- `docs/HOW_TO_RUN.md` if test steps/troubleshooting change

Required content:

- Recorded-HFR impact window decode is ByteBuffer-only.
- `ImageReader`/`getOutputImage`/plane APIs are intentionally forbidden in the
  production route because the S10+ crash is uncatchable.
- Only the requested impact window is decoded.
- Frames are processed one at a time.
- Unsupported decoder output fails loud as No Read.
- Device proof is required before claiming S10+ field closure.

Security/privacy:

- No raw media export added.
- Decoded frames remain in memory only.
- Logs must include diagnostics but not file paths beyond already-redacted
  display names.

## Subtask 7: Verification And Device Proof

Local checks before implementation review:

- Focused tests:
  - `RecordedHfrWindowFrameSourceTest`
  - `RecordedHfrStreamingEstimateTest`
  - `ImportContractsTest`
  - new ByteBuffer YUV conversion tests
  - lifecycle/timeout tests
  - `CalibrationUiSourceTest` if UI changed
- Full unit suite:
  - `ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon`
- Build:
  - `ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:assembleDebug --rerun-tasks --no-daemon`
- Docs/security:
  - `pnpm docs:check`
  - `pnpm security:check`
  - `git diff --check`

S10+ proof after implementation review or clearly disclosed as open if device is
not available:

- run the ByteBuffer viability spike if not already recorded in implementation
  evidence;
- install debug APK;
- clear logcat;
- launch app;
- enter Run Mode with valid setup;
- say `shoot`;
- wait for Ready;
- make a pop/impact;
- verify no native crash:
  - no `ImageReader$SurfaceImage.getPlanes`;
  - no `MediaCodec.getOutputImage`;
  - no `Image.getPlanes`;
  - no `NewDirectByteBuffer` abort;
  - no `OutOfMemoryError`;
- verify actual output format, stride, and slice-height are logged;
- verify terminal estimate or No Read with decoded-window proof;
- reject the proof if the terminal No Read is `unsupported color format`,
  opaque/UBWC output, flexible-only output, or any other target-device format
  unsupported by the implementation.

## Implementation Order

1. Run S10+ ByteBuffer viability spike and record measured output format,
   stride, slice-height, and convertibility.
2. Land ByteBuffer conversion contract and tests, including the measured S10+
   format and non-1:1 downscale.
3. Refactor Android source to pull-streaming ByteBuffer output.
4. Wire lifecycle, timeout, and No Read surfacing.
5. Add source guards and memory-bound tests.
6. Update docs/security.
7. Run local checks.
8. Send implementation review package, then run/install S10+ proof when allowed.
