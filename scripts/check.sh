#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

bash scripts/docs-check.sh
bash scripts/security-check.sh
bash scripts/core-boundary-check.sh
bash scripts/lint.sh
bash scripts/test.sh
