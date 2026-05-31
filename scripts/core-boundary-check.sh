#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

if [[ ! -x ./gradlew ]]; then
  echo "[core-boundary] root Gradle wrapper is missing or not executable" >&2
  exit 1
fi

dependency_output="$(./gradlew :core:dependencies --configuration runtimeClasspath --no-daemon 2>&1)"
printf '%s\n' "$dependency_output"

if printf '%s\n' "$dependency_output" | grep -Eiq '(^|[[:space:]])(androidx\.|com\.android|android\.)'; then
  echo "[core-boundary] :core runtimeClasspath contains Android dependencies" >&2
  exit 1
fi

if grep -Eiq 'com\.android|androidx\.|org\.jetbrains\.compose|plugin\.compose|libs\.androidx|compose' core/build.gradle.kts; then
  echo "[core-boundary] core/build.gradle.kts references Android or Compose dependencies/plugins" >&2
  exit 1
fi

echo "[core-boundary] :core runtimeClasspath and build script are Android/Compose-free"
