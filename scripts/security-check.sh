#!/usr/bin/env bash
set -euo pipefail

status=0

repo_files() {
  git ls-files -z --cached --others --exclude-standard
}

ensure_git_scan_context() {
  if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "[security-check] scanner error: not inside a git working tree" >&2
    return 1
  fi
}

is_path_excluded_from_secret_text_scan() {
  local file="$1"
  case "$file" in
    scripts/stale-terms.patterns|scripts/secret-text.patterns)
      return 0
      ;;
  esac
  return 1
}

secret_text_scan_files() {
  local file
  while IFS= read -r -d '' file; do
    if ! is_path_excluded_from_secret_text_scan "$file"; then
      printf '%s\0' "$file"
    fi
  done < <(repo_files)
}

run_secret_text_scan() {
  local pattern_file="$1"
  shift
  local -a files=("$@")
  local filtered_pattern_file

  if [[ ! -s "$pattern_file" ]]; then
    echo "[security-check] scanner error: missing or empty pattern file: $pattern_file" >&2
    return 2
  fi
  filtered_pattern_file="$(mktemp)"
  awk '!/^[[:space:]]*(#|$)/ { print }' "$pattern_file" > "$filtered_pattern_file"
  if [[ ! -s "$filtered_pattern_file" ]]; then
    echo "[security-check] scanner error: no active secret-text patterns after filtering comments: $pattern_file" >&2
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
      echo "[security-check] scanner error while scanning secret text:" >&2
      printf '%s\n' "$output" >&2
      return 2
      ;;
  esac
}

if ! ensure_git_scan_context; then
  exit 1
fi

filename_hits=()
while IFS= read -r -d '' file; do
  case "$file" in
    .env|.env.*|*.pem|*.key|credentials.*|*/.env|*/.env.*|*/credentials.*|local.properties|*/local.properties|*.keystore|*.apk|*.aab)
      filename_hits+=("$file")
      ;;
  esac
done < <(repo_files)

if [[ "${#filename_hits[@]}" -gt 0 ]]; then
  printf '%s\n' "${filename_hits[@]}" >&2
  echo "[security-check] refusing to proceed with secret/local artifact candidates above" >&2
  status=1
fi

mapfile -d '' secret_scan_files < <(secret_text_scan_files)
if [[ "${#secret_scan_files[@]}" -eq 0 ]]; then
  echo "[security-check] scanner error: no tracked or nonignored files selected for scanning" >&2
  exit 1
fi

set +e
run_secret_text_scan "scripts/secret-text.patterns" "${secret_scan_files[@]}"
scan_status=$?
set -e
case "$scan_status" in
  0)
    echo "[security-check] review possible hardcoded secret text above" >&2
    status=1
    ;;
  1)
    ;;
  *)
    status=1
    ;;
esac

if [[ "$status" -eq 0 ]]; then
  echo "[security-check] OK"
fi

exit "$status"
