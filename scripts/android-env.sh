#!/usr/bin/env bash
set -euo pipefail

resolve_android_home() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    return 0
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
    return 0
  fi

  local default_sdk="$HOME/Android/Sdk"
  if [[ -d "$default_sdk" ]]; then
    export ANDROID_HOME="$default_sdk"
    return 0
  fi

  echo "[android-env] Android SDK not found" >&2
  echo "[android-env] checked ANDROID_HOME, ANDROID_SDK_ROOT, and $default_sdk" >&2
  return 2
}
