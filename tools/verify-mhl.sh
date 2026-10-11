#!/bin/bash
# Runs the reference ASC MHL tool (pip package "ascmhl", installed into a throw-away venv) over the histories FeldKit writes.
#   tools/verify-mhl.sh                 -> verifies app/build/fixtures/mhl-out/* (made by MhlTest)
#   tools/verify-mhl.sh <folder> ...    -> verifies the given folders (e.g. one pulled from the phone)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="${MHL_VENV:-${TMPDIR:-/tmp}/feldkit-mhl-venv}"
[ -x "$VENV/bin/ascmhl" ] || { python3 -m venv "$VENV" && "$VENV/bin/pip" install -q ascmhl; }
if [ $# -eq 0 ]; then set -- "$ROOT"/app/build/fixtures/mhl-out/job-*; fi
fail=0
for d in "$@"; do
  echo "== $d"
  # the reference tool verifies the history while it adds the next generation: do that on a copy so the folder stays untouched
  tmp=$(mktemp -d); cp -R "$d" "$tmp/job"
  # hash of the first generation decides what we ask the tool to use for the new one
  fmt=$(grep -o '<\(xxh64\|xxh3\|xxh128\|md5\|sha1\|c4\) ' "$d"/ascmhl/*.mhl | head -1 | tr -d '< ')
  if "$VENV/bin/ascmhl" create "$tmp/job" -h "${fmt:-xxh64}" 2>&1 | tail -3; then echo "   ok (${fmt:-xxh64})"; else echo "   FAILED"; fail=1; fi
  rm -rf "$tmp"
done
exit $fail
