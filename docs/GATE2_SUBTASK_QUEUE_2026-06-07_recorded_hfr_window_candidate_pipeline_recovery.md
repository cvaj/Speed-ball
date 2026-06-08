# Gate 2 Subtask Queue - Recorded-HFR Window Candidate Pipeline Recovery

Date: 2026-06-07
Workstream: `recorded-hfr-window-candidate-pipeline-recovery`

## Goal

Fix the real S10+ field failure where recorded-HFR capture and sound-window
selection worked, frames `157-164` visibly contained the yellow ball, but the
app hung after `RECORDED_ESTIMATE_STAGE streaming window=true` and never
returned a terminal result.

Gate 1 converged on the root cause:

- no end-to-end timeout/cancellation around the estimate stage;
- unbounded blob-count/RANSAC candidate work after detection;
- exposure blur no-read evaluated after the expensive estimate instead of
  before it;
- ROI must not be a per-shot requirement for recorded-HFR run mode.

No production code may be implemented until this Gate 2 queue converges.

## Subtask 1 - End-To-End Window Processing Deadline

Implement a recorded-HFR window processing budget that spans:

- MediaCodec source open;
- frame decode/conversion;
- source-validity accumulation;
- proof thumbnail generation;
- blob detection;
- candidate reduction/RANSAC;
- proof/report assembly.

Requirements:

- Use one absolute deadline for `runRecordedWindowEstimate`.
- Default hard budget: `RECORDED_HFR_WINDOW_DECODE_TIMEOUT_MILLIS = 10_000L`
  unless implementation review finds an existing smaller product constant that
  is safer.
- Pass a real cancellation/deadline signal into
  `RecordedHfrStreamingEstimate.estimate(...)`; do not rely on the default
  `{ false }`.
- Check cancellation before every decoded frame, after frame conversion, before
  blob detection, before candidate reduction, and inside any candidate/RANSAC
  loop that can iterate over many blobs.
- On cancellation/deadline, return fail-loud
  `VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED` /
  `ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED`; do not display mph.

Tests:

- Fake source that stalls or cancellation flips mid-stream returns no-read and
  closes the source.
- Candidate reducer cancellation before/during RANSAC returns resource no-read.
- Terminal proof result is built from partial frames when any thumbnails exist.

## Subtask 2 - Guaranteed Terminal Log And UI Result

Make the no-silent-hang invariant explicit.

Requirements:

- Every `runRecordedWindowEstimate` path after source creation must log
  `RECORDED_ESTIMATE_WINDOW decoded=... syncPrefix=... decodeMs=... timedOut=...`
  or an equivalent terminal window-processing log before returning.
- Timeout/resource-limit paths must return an `ImportRunOutcome.NoRead` with:
  - source kind `RECORDED_ESTIMATE`;
  - frame count if known;
  - proof thumbnails if available;
  - source-validity verdict if evaluated;
  - top reason shown in report.
- The UI must leave the user with a visible result/proof/no-read state and then
  allow another `shoot` without repeating setup.

Tests:

- Regression test that a resource-limited recorded-HFR window produces terminal
  proof/no-read metadata.
- UI/report state test includes decoded/window/candidate/reason lines for
  recorded-HFR no-read.

## Subtask 3 - Early Exposure Blur Gate Without Losing Proof

Move the existing exposure blur policy before expensive detection/RANSAC.

Requirements:

- If `BurstDiagnostics.actualExposureMedianNanos` exceeds
  `RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS`, do not run full candidate
  detection/RANSAC.
- Preserve proof availability:
  - either run a bounded proof-only window thumbnail pass with no blob
    detection/RANSAC; or
  - return no-read immediately while clearly reporting that the retained debug
    MP4 is available for proof extraction.
- Prefer proof-only thumbnails if they can be produced within the same hard
  window budget.
- No mph may be emitted from an over-exposure/blur-gated run.

Tests:

- Over-threshold exposure returns before invoking full
  `RecordedHfrStreamingEstimate.estimate(...)`.
- Over-threshold exposure returns no mph and includes proof availability or
  proof thumbnails.
- Existing exposure diagnostics tests continue to pass.

## Subtask 4 - Bound Blob Candidate Generation

Bound candidate generation by blob count, not only frame count.

Requirements:

- Add reviewed constants for recorded-HFR mode only, initially:
  - max blobs per frame: `32`;
  - max total candidate blobs in a window: `90`;
  - max retained candidate frames remains bounded by the window frame count.
- Scope these caps through configuration, not global shared behavior. The
  shared direct/import reducer paths must keep their current behavior unless
  they explicitly opt into a budget.
- If a frame or window exceeds these caps, return a resource no-read with proof
  thumbnails already collected.
- Surface counts in diagnostics/report:
  - scanned frames;
  - candidate frames;
  - candidate blobs;
  - cap that failed.
- Keep fail-loud behavior: do not silently drop excess blobs and continue to mph
  unless the drop strategy is explicitly proven not to bias the result. Gate 2
  chooses fail-loud over silent trimming.

Tests:

- Large yellow/noisy frame that creates too many blobs returns
  `RESOURCE_LIMIT_EXCEEDED`.
- A close oversized yellow component returns a bounded no-read or valid
  candidate result promptly; never hangs.
- Candidate count caps are tested separately from frame caps.
- Sibling direct/import reducer behavior is unchanged when no recorded-HFR
  budget is configured.

## Subtask 5 - Bound RANSAC / Candidate Reduction

The RANSAC-style reducer must be work-bounded independently of detection.

Requirements:

- Add a recorded-HFR reducer budget, initially:
  - max total RANSAC candidates: `90`;
  - max pair hypotheses evaluated: `4096`;
  - cancellation/deadline check at least every `128` pair hypotheses.
- The `90` candidate ceiling is coherent with the `4096` pair cap:
  `90 * 89 / 2 = 4005`, so every under-cap candidate set can be evaluated
  without tripping the pair cap first. The pair cap remains the inner guard for
  future budget changes and sampled/alternate reducer paths.
- Plumb this through an explicit config/budget object, likely on
  `TrackExtractionConfig` or a nested reducer-budget field. `null`/absent budget
  preserves existing direct/import behavior.
- If caps/deadline are exceeded, return fail-loud
  `VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED`.
- Preserve the existing physics/straight-flight rejection behavior for normal
  candidate counts.
- Do not report mph when the reducer cap is exceeded.

Tests:

- Synthetic candidate set exceeding pair budget returns resource no-read.
- Synthetic valid trajectory under caps still succeeds.
- Ambiguous/noisy set under caps still returns no-read, not a false speed.
- Cap-coherence test documents that `90` candidates stay within the `4096`
  pair-hypothesis budget.
- Existing direct/import reduction test verifies unchanged behavior with no
  budget configured.

## Subtask 6 - Recorded-HFR ROI Optional, Full-Frame Safe

Change recorded-HFR run-mode behavior so ROI is no longer a per-shot blocker.

Requirements:

- Setup preview may still show and allow an ROI.
- Existing saved/setup ROI may be used as an optional spatial bound when
  present and valid.
- Recorded-HFR run mode must be able to process without user ROI by using a
  full-frame region over the 640x360 working frame.
- Full-frame mode must still obey the blob/RANSAC/deadline caps.
- Distance/color setup remain required unless the documented ball-diameter
  fallback is active.

Tests:

- Recorded-HFR config builds with color + distance but no ROI, using full-frame
  detector bounds.
- Invalid ROI falls back/fails loud according to the chosen behavior; it must
  not create a silent no-read if full-frame mode is available.
- Existing setup UI tests are updated so ROI is optional for recorded-HFR run
  mode but not accidentally optional for unrelated legacy paths unless intended.

## Subtask 7 - Proof And Report Details

Add user-visible evidence for no-read/success.

Requirements:

- Report/log lines include:
  - decoded/emitted frames;
  - selected window start/end;
  - scanned frame count;
  - candidate frame count;
  - candidate blob count;
  - selected/RANSAC inlier count if available;
  - source-validity verdict;
  - exposure summary;
  - rejection/cap reason.
- Proof thumbnails must show decoded frames even when no candidate track is
  accepted, whenever frames were decoded.
- The result must be dismissible without forcing repeated setup; run mode should
  be ready for the next `shoot` after terminal completion.

Tests:

- No-read proof contains source thumbnails for visible frames with zero accepted
  candidates.
- Report line tests cover candidate/cap/exposure/window details.
- State-machine/UI test verifies terminal result does not erase setup readiness.

## Subtask 8 - Documentation And Security

Update docs in the same implementation.

Required docs:

- `docs/HOW_THE_APPLICATION_WORKS.md`
- `docs/DATA_FLOW.md`
- `docs/FUNCTIONAL_TEST_REGISTRY.md`
- `docs/HOW_TO_RUN.md`
- `docs/SECURITY_CHECKLIST.md`

Required content:

- Recorded-HFR uses sound-triggered time windows first.
- ROI is optional for recorded-HFR run mode.
- Full-frame candidate generation is bounded and can fail loud.
- Large blur/over-exposure returns no-read rather than fake mph.
- Proof thumbnails/retained debug MP4s are diagnostic evidence, not committed
  artifacts.

Security:

- Confirm retained MP4 behavior remains debug-only, app-private, bounded, and
  excluded from git.
- Confirm no secrets/local device addresses are added to docs.

## Subtask 9 - Verification And Device Proof

Local checks:

```bash
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --no-daemon
ANDROID_HOME=/home/vangmountain/Android/Sdk ./gradlew :app:assembleDebug --rerun-tasks --no-daemon
pnpm docs:check
pnpm security:check
git diff --check
```

Device proof on S10+:

- Install debug APK.
- Setup once: permission, distance/calibration, color. ROI optional.
- Run mode: say `shoot`, wait for Ready, make loud pop/impact, move ball through
  any frame area.
- Verify terminal result/no-read appears within the hard budget.
- Verify logs include terminal `RECORDED_ESTIMATE_WINDOW ...`.
- Verify proof thumbnails show decoded frames.
- Verify next `shoot` can run without repeating setup.

## Gate 2 Review Questions

1. Are the proposed recorded-HFR candidate and RANSAC caps (`32` per frame,
   `90` total, `4096` pair hypotheses) appropriate as initial fail-loud bounds?
2. Should over-threshold exposure return immediately with retained-MP4 proof
   availability, or should it run a bounded proof-only thumbnail pass?
3. Is optional ROI/full-frame fallback scoped correctly to recorded-HFR run mode?
4. Are any sibling paths at risk of changing measurement behavior unintentionally?
5. Are additional tests required before implementation?

## Gate 2 Round 1 Amendments

Claude returned `NEEDS_DISCUSSION` with two findings:

- F1: caps would land in a shared reducer and could regress direct/import paths;
- F2: `256` total candidates and `4096` pair hypotheses were incoherent because
  `4096` pair hypotheses binds at about `91` candidates.

Amendments:

- Caps are now explicitly recorded-HFR-scoped through configuration. Shared
  direct/import paths must remain unchanged when no budget is configured.
- Total recorded-HFR candidates are now `90`, coherent with the `4096` pair cap
  because `90 * 89 / 2 = 4005`.
- Tests now explicitly require unchanged sibling reducer behavior and
  cap-coherence coverage.
- Over-threshold exposure still prefers a deadline-bounded proof-only thumbnail
  pass, with no detection/RANSAC.
