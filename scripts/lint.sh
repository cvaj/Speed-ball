#!/usr/bin/env bash
set -euo pipefail

if [[ -x ./gradlew ]]; then
  ./gradlew lintDebug
elif [[ -x prototype/hs-probe/gradlew && -n "${ANDROID_HOME:-}" ]]; then
  (cd prototype/hs-probe && ./gradlew :app:lintDebug --no-daemon)
else
  echo "[lint] No root Android project yet, or ANDROID_HOME is unset. Skipping Gradle lint for scaffold-only state."
fi
