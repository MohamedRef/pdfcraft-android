#!/usr/bin/env bash
# Retry only transient upstream dependency-download failures. Never hide test/build errors.
set -euo pipefail

if (( $# == 0 )); then
  echo "usage: $0 ./gradlew <args...>" >&2
  exit 2
fi

log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT

for attempt in 1 2 3; do
  : > "$log_file"
  set +e
  "$@" 2>&1 | tee "$log_file"
  status=${PIPESTATUS[0]}
  set -e
  if (( status == 0 )); then
    exit 0
  fi
  if (( attempt == 3 )) || ! grep -Eiq       '(Received status code (429|502|503|504)|Gateway Time-out|Read timed out|SocketTimeoutException|Connection reset|Temporary failure in name resolution|Downloading from .* failed: timeout)'       "$log_file"; then
    echo "::error::Gradle command failed (exit $status); not hiding build/test failures." >&2
    exit "$status"
  fi
  echo "::warning::Transient Gradle dependency download failure, retry ${attempt}/2 (checksum verification unchanged)." >&2
  sleep "$(( attempt * 15 ))"
done
