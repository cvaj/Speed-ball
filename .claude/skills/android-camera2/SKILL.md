---
name: android-camera2
description: >
  Android Kotlin, Camera2 constrained high-speed sessions, MediaRecorder,
  SurfaceTexture/GPU frame paths, SENSOR_TIMESTAMP, permissions, lifecycle,
  and device validation for Speed-ball.
---

# Android Camera2 Skill

Use for capture, permissions, frame timing, and device-specific work.

Rules:

- Use Camera2 constrained high-speed for live high-fps capture.
- Do not replace live capture with CameraX.
- Use `SENSOR_TIMESTAMP` as timing authority.
- Reconcile decoded frames with timestamps before measurement.
- Release camera, recorder, codec, surfaces, GL resources, and handlers on all lifecycle/error paths.
- Device claims require measured evidence in `docs/DEVICE_CAPABILITIES.md` or `docs/HIGH_SPEED_FINDINGS.md`.

Required tests/proof:

- JVM/unit tests for pure orchestration where possible.
- On-device logs for HAL mode enumeration and real capture timing.
- Failure proof for unsupported modes and permission denial.
