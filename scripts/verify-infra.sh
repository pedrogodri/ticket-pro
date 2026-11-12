#!/usr/bin/env bash
# Asserts that the AWS resources the architecture depends on exist in MiniStack.
# Exit 0 only if every check passes. Usage: scripts/verify-infra.sh
set -uo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/aws.sh
load_env

failures=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }

# --- identity ---------------------------------------------------------------
if account=$(awsl sts get-caller-identity 2>/dev/null | jq -er '.Account'); then
  pass "sts identity (account $account)"
else
  fail "sts get-caller-identity (ministack unreachable?)"
fi

[ "$failures" -eq 0 ] || { echo "$failures check(s) failed"; exit 1; }
echo "all checks passed"
