#!/usr/bin/env bash
set -euo pipefail

required_docs=(
  "README.md"
  "CLAUDE.md"
  "AGENTS.md"
  "docs/CODEBASE_SOURCE_OF_TRUTH.md"
  "docs/HOW_THE_APPLICATION_WORKS.md"
  "docs/FUNCTIONAL_TEST_REGISTRY.md"
  "docs/REVIEW_CHECKLIST.md"
  "docs/SECURITY_CHECKLIST.md"
  "docs/ARCHITECTURE_AND_IMPLEMENTATION_PLAN.md"
  "docs/DEVICE_CAPABILITIES.md"
  "docs/HIGH_SPEED_FINDINGS.md"
)

status=0

for file in "${required_docs[@]}"; do
  if [[ ! -s "$file" ]]; then
    echo "[docs-check] missing or empty required doc: $file" >&2
    status=1
  fi
done

repo_files() {
  git ls-files -z --cached --others --exclude-standard
}

ensure_git_scan_context() {
  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[docs-check] scanner error: not inside a git working tree" >&2
    return 1
  fi
}

is_path_excluded_from_content_scan() {
  local file="$1"
  case "$file" in
    scripts/stale-terms.patterns|scripts/secret-text.patterns)
      return 0
      ;;
  esac
  return 1
}

content_scan_files() {
  local file
  while IFS= read -r -d '' file; do
    if ! is_path_excluded_from_content_scan "$file"; then
      printf '%s\0' "$file"
    fi
  done < <(repo_files)
}

run_pattern_file_scan() {
  local label="$1"
  local pattern_file="$2"
  shift 2
  local -a files=("$@")
  local filtered_pattern_file

  if [[ ! -s "$pattern_file" ]]; then
    echo "[docs-check] scanner error: missing or empty pattern file for $label: $pattern_file" >&2
    return 2
  fi
  filtered_pattern_file="$(mktemp)"
  awk '!/^[[:space:]]*(#|$)/ { print }' "$pattern_file" > "$filtered_pattern_file"
  if [[ ! -s "$filtered_pattern_file" ]]; then
    echo "[docs-check] scanner error: no active patterns for $label after filtering comments: $pattern_file" >&2
    rm -f "$filtered_pattern_file"
    return 2
  fi
  if [[ "${#files[@]}" -eq 0 ]]; then
    rm -f "$filtered_pattern_file"
    return 1
  fi

  local output
  set +e
  output="$(grep -I -nE -f "$filtered_pattern_file" -- "${files[@]}" 2>&1)"
  local grep_status=$?
  rm -f "$filtered_pattern_file"

  case "$grep_status" in
    0)
      printf '%s\n' "$output"
      return 0
      ;;
    1)
      return 1
      ;;
    *)
      echo "[docs-check] scanner error while running $label scan:" >&2
      printf '%s\n' "$output" >&2
      return 2
      ;;
  esac
}

run_inline_scan() {
  local label="$1"
  local pattern="$2"
  shift 2
  local -a files=("$@")

  if [[ "${#files[@]}" -eq 0 ]]; then
    return 1
  fi

  local output
  set +e
  output="$(grep -I -nE -- "$pattern" "${files[@]}" 2>&1)"
  local grep_status=$?

  case "$grep_status" in
    0)
      printf '%s\n' "$output"
      return 0
      ;;
    1)
      return 1
      ;;
    *)
      echo "[docs-check] scanner error while running $label scan:" >&2
      printf '%s\n' "$output" >&2
      return 2
      ;;
  esac
}

if ! ensure_git_scan_context; then
  exit 1
fi

mapfile -d '' scan_files < <(content_scan_files)
if [[ "${#scan_files[@]}" -eq 0 ]]; then
  echo "[docs-check] scanner error: no tracked or nonignored files selected for scanning" >&2
  exit 1
fi

set +e
run_pattern_file_scan "stale-term" "scripts/stale-terms.patterns" "${scan_files[@]}"
scan_status=$?
set -e
case "$scan_status" in
  0)
    echo "[docs-check] found stale legacy terminology in Speed-ball protocol/docs/source files" >&2
    status=1
    ;;
  1)
    ;;
  *)
    status=1
    ;;
esac

set +e
run_inline_scan "conflict-marker" '^(<<<<<<<|=======|>>>>>>>)' "${scan_files[@]}"
scan_status=$?
set -e
case "$scan_status" in
  0)
    echo "[docs-check] conflict markers found" >&2
    status=1
    ;;
  1)
    ;;
  *)
    status=1
    ;;
esac

if [[ "$status" -ne 0 ]]; then
  exit "$status"
fi

echo "[docs-check] OK"
