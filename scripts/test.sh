#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

if [[ -x ./gradlew ]]; then
  # shellcheck source=scripts/android-env.sh
  source scripts/android-env.sh
  resolve_android_home
  ./gradlew test --no-daemon
elif [[ -x prototype/hs-probe/gradlew && -n "${ANDROID_HOME:-}" ]]; then
  (cd prototype/hs-probe && ./gradlew test --no-daemon)
else
  echo "[test] No root Android project yet, or ANDROID_HOME is unset. Skipping Gradle tests for scaffold-only state."
fi
