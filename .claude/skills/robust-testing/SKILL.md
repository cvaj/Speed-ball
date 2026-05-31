---
name: robust-testing
description: >
  Adversarial test design for Speed-ball: golden values, pipeline tests,
  no-mock application logic, error paths, Android/device proof obligations.
---

# Robust Testing Skill

Use for every test plan, test review, and implementation self-review.

Priorities:

1. Functional golden values.
2. Mathematical invariants.
3. Real pipeline/data-flow tests.
4. Error/fail-loud paths.
5. Edge cases and boundaries.

Do not mock math, calibration, detection, trajectory, or timestamp reconciliation. Stub Android framework boundaries only when JVM tests cannot use the real service.
