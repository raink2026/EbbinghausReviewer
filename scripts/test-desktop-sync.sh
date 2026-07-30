#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$root_dir"

command -v shellcheck >/dev/null 2>&1 || {
  printf 'Error: shellcheck is required for desktop sync checks\n' >&2
  exit 1
}
command -v bats >/dev/null 2>&1 || {
  printf 'Error: bats is required for desktop sync checks\n' >&2
  exit 1
}

shellcheck scripts/review-sync.sh scripts/test-desktop-sync.sh
bats tools/tests/bats/review-sync.bats
