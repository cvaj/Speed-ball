#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

if [[ -x ./gradlew ]]; then
  # shellcheck source=scripts/android-env.sh
  source scripts/android-env.sh
  if ! resolve_android_home; then
    exit 2
  fi

  set +e
  output="$(./gradlew :app:lintDebug --no-daemon 2>&1)"
  status=$?
  set -e

  if [[ "$status" -eq 0 ]]; then
    printf '%s\n' "$output"
    exit 0
  fi

  if printf '%s\n' "$output" | grep -Eqi 'SDK location not found|Android SDK|No installed build tools|Failed to find Build Tools|Installed Build Tools revision|Could not determine the dependencies of task.*lint|requires the Android SDK'; then
    printf '%s\n' "$output" >&2
    echo "[lint] Android lint tooling or SDK unavailable; this is an environment-limited lint result, not a clean pass" >&2
    exit 2
  fi

  printf '%s\n' "$output" >&2
  exit 1
elif [[ -x prototype/hs-probe/gradlew && -n "${ANDROID_HOME:-}" ]]; then
  (cd prototype/hs-probe && ./gradlew :app:lintDebug --no-daemon)
else
  echo "[lint] No root Android project yet, or ANDROID_HOME is unset. Skipping Gradle lint for scaffold-only state."
fi
