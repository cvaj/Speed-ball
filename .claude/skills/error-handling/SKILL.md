---
name: error-handling
description: >
  Fail-loud measurement errors, Android resource cleanup, user-facing no-read
  reasons, logging, and exception handling.
---

# Error Handling Skill

Use when changing any path that can fail.

Rules:

- Wrong measurement is worse than visible "No read".
- Do not swallow exceptions that affect correctness.
- User-facing errors should be actionable and not leak sensitive local paths.
- Cleanup must happen for camera sessions, codecs, GL resources, OpenCV Mats, and file handles.
