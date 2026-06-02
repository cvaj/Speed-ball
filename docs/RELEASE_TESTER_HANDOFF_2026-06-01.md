# Release Tester Handoff - 2026-06-01

## Status

This build is intended for controlled tester feedback after Phase 16 hardening
and device smoke pass. Tester-ready means the app is safe, fail-loud, honestly
labeled, and does not require the user to start or stop a recording for speed
checks.

Tester-ready does not mean estimate mph has been validated against known
ground-truth speed. The real ball-in-frame validation proof is still the named
reopenable limitation below unless Phase 16 device evidence closes it.

## Supported Tester Workflows

- Live visual estimate: after calibration, color, and ROI setup, the app captures
  a bounded frame window, stops automatically, processes the frames, and shows a
  labeled estimate or a typed no-read.
- Calibration setup: the preview shows movable A/B caliper endpoints, a yellow
  color sample point, and an ROI rectangle. Select A, B, Color, or ROI and use
  the coarse/fine movement buttons to align setup before running an estimate.
- Import estimate: after the user selects a video through Android's picker, the
  app validates, extracts, estimates or no-reads, saves a redacted summary, and
  prepares redacted export evidence automatically.
- Saved results and exports are summary-only evidence. They must not contain raw
  videos, raw pixels, private URIs, filesystem paths, ADB endpoints, pairing
  data, APKs, keystores, or credentials.

## Accuracy Limitation

Open limitation:

`S10_REAL_BALL_GROUND_TRUTH_ESTIMATE_VALIDATION_PENDING`

Meaning:

- S10+ live visual estimates are personal estimates, not certified measurements.
- Estimate mph is not ground-truth accuracy validated while this limitation is
  open.
- Safety-floor device proof can show no crashes, no false mph on no-read, no
  leaks, bounded automatic capture, and honest labels. It cannot prove the
  displayed mph is accurate.

Reopen/close condition:

- A real ball moves through the calibrated camera view.
- Color and ROI match the real ball.
- The setup has a known or externally measurable speed reference.
- The app produces either a labeled estimate with timing basis, confidence, and
  assumptions, or a typed no-read, and the evidence is reviewed.

## Unsupported Claims

- No S10+ strict production measurement claim is allowed from the closed
  record/decode route or any unproven source.
- Imported clips remain estimate-only unless a later reviewed provenance gate
  proves strict timing authority.
- No alternate-device support is claimed unless that device is connected and
  measured.

## Tester Evidence To Capture

- App install and launch result.
- Missing-setup no-read with no speed, angle, trajectory, carry, apex, or hang
  values.
- Calibration UI smoke: overlay visible, target selection works, and at least
  one target nudge updates the on-screen setup status.
- Bounded automatic live estimate run: estimate or typed no-read.
- One labeled-estimate rendering case when a safe fixture or controlled setup is
  available; otherwise record why accuracy validation remains open.
- One real Android picker-selected import file to exercise picker access,
  transient grant behavior, and redaction.
- Redacted export evidence and saved summary lines.
- Redaction scan results over logs/docs/exports.
