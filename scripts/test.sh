#!/usr/bin/env bash
set -euo pipefail

if [[ -x ./gradlew ]]; then
  ./gradlew test
elif [[ -x prototype/hs-probe/gradlew && -n "${ANDROID_HOME:-}" ]]; then
  (cd prototype/hs-probe && ./gradlew test --no-daemon)
else
  echo "[test] No root Android project yet, or ANDROID_HOME is unset. Skipping Gradle tests for scaffold-only state."
fi
