---
name: measurement-domain
description: >
  Speed-ball measurement math, centroid tracking, calibration, velocity fit,
  launch angle, unit conversion, and drag trajectory rules.
---

# Measurement Domain Skill

Use for velocity, calibration, detection, and trajectory changes.

Rules:

- Never report mph from fewer than three valid detections.
- Calibration scale must be positive and finite.
- Timestamp deltas must be positive and proven.
- Speed is computed from raw image pixels and converted using `pixelsPerFoot`.
- Launch angle uses image y-down convention: up is `-vy`.
- Drag trajectory tests must include no-drag closed-form and drag monotonicity.

Required tests:

- Golden-value tests for velocity and units.
- Error-path tests for invalid calibration/timestamps/detections.
- Real fixture/pipeline tests where data flows through detection or fitting.
