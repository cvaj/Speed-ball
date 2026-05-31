# Functional Test Registry

Every Speed-ball capability must have functional proof. New capabilities must add or update a row before implementation is considered complete.

| Capability | Required Proof | Current Status |
|---|---|---|
| Root Gradle app skeleton | Wrapper checksum, dependency resolution, `:core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, root scripts, and `:core` boundary check | Phase 1 implemented locally; all listed proofs passed before implementation review |
| Placeholder no-read shell | JVM test asserts all workflow sections exist and no fake speed, frame rate, camera mode, or result value is exposed | Phase 1 implemented in `:app:testDebugUnitTest` |
| Camera2 high-speed HAL enumeration | Device/prototype log proving supported fps/res combos; app mapper tests for fixed vs blended ranges | Phase 4 app implementation pending review; S10+ proven in `docs/HIGH_SPEED_FINDINGS.md`; S22+ pending |
| 120 fps burst capture | On-device burst with unique timestamp count, median gap in band, nonzero MP4, and clean release; decode reconciliation comes in Phase 5 | Phase 4 code-level implementation approved; device burst proof BLOCKED/pending locked-device unlock |
| 240 fps GPU capture | On-device true 240 detections from preview/GPU path | Planned |
| HSV color calibration | Tap/sample -> HSV band -> mask preview/detection | Planned |
| Ball centroid detection | Real frame fixtures produce centroids within tolerance | Planned |
| Flight window selection | No read on insufficient/ambiguous detections; valid window on known clip | Planned |
| Velocity fit | Golden values for slope, speed, angle, residuals, outlier rejection | Phase 2 implemented in `:core:test`; canonical `hitting2` parity pending until fixture data exists |
| Distance calibration | Known pixel gap and real distance produce expected pixels/foot | Phase 2 implemented in `:core:test` |
| Unit conversion | px/s -> ft/s -> mph golden values | Phase 2 implemented in `:core:test` |
| Core measurement outcome | Detections + calibration -> typed success/failure; no partial mph/angle on failure | Phase 2 implemented in `:core:test` with synthetic end-to-end golden and invalid-options failure coverage |
| Drag trajectory | No-drag closed form, drag monotonicity, hand-calculated case | Phase 3 implemented in `:core:test` with drag-zeroed RK4 goldens, interpolation, angle-edge, zero-speed, and error-path coverage |
| Results UI | Valid read and no-read states display correct user-facing messages | Planned |
| Import mode | Imported clip runs same detector/math pipeline | Planned |
