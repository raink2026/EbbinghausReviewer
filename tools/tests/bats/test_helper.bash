#!/usr/bin/env bash

repo_root() {
  cd "$BATS_TEST_DIRNAME/../../.." && pwd -P
}

python_bin() {
  if command -v python3 >/dev/null 2>&1; then
    printf '%s\n' python3
  else
    printf '%s\n' python
  fi
}

run_python_test() {
  local test_name=$1
  local root
  root=$(repo_root)
  run "$(python_bin)" -c '
import sys
import unittest

sys.path.insert(0, sys.argv[1])
suite = unittest.defaultTestLoader.loadTestsFromName(sys.argv[2])
result = unittest.TextTestRunner(verbosity=2).run(suite)
raise SystemExit(0 if result.wasSuccessful() else 1)
' "$root" "$test_name"
  if (( status != 0 )); then
    printf '%s\n' "$output" >&3
    return 1
  fi
}
