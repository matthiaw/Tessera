#!/usr/bin/env bash
# Copyright 2026 Tessera Contributors
# Licensed under the Apache License, Version 2.0
#
# Compares .planning/benchmarks/latest-<DATASET>.json against the previous
# green baseline (.planning/benchmarks/baseline-<DATASET>.json) and exits 1
# if any benchmark's p95 (SampleTime) regresses by more than the threshold.
#
# Usage: scripts/check_regression.sh <dataset>   e.g. 100000
#
# Env:
#   REGRESSION_THRESHOLD_PCT  integer percent, default 25 (D-04 / Phase 0 Q2)
#
# If the baseline file is missing, the script seeds it from the latest result
# and exits 0 so the first nightly run establishes the reference point.
#
# Requires: jq on PATH.

set -euo pipefail

DATASET="${1:-100000}"
THRESHOLD_PCT="${REGRESSION_THRESHOLD_PCT:-25}"
DIR=".planning/benchmarks"
LATEST="$DIR/latest-$DATASET.json"
BASELINE="$DIR/baseline-$DATASET.json"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq not on PATH — install jq (brew install jq / apt install jq)" >&2
  exit 2
fi

if [[ ! -f "$LATEST" ]]; then
  echo "ERROR: no latest result at $LATEST — run JMH first" >&2
  exit 2
fi

if [[ ! -f "$BASELINE" ]]; then
  echo "INFO: no baseline at $BASELINE — seeding baseline from latest"
  cp "$LATEST" "$BASELINE"
  exit 0
fi

# Extract (benchmark-name \t p95) for SampleTime mode entries.
extract() {
  jq -r '.[] | select(.mode == "sample") | "\(.benchmark)\t\(.primaryMetric.scorePercentiles."95.0")"' "$1"
}

failed=0
while IFS=$'\t' read -r bench latest_p95; do
  [[ -z "$bench" ]] && continue
  base_p95=$(extract "$BASELINE" | awk -v b="$bench" '$1==b {print $2}')
  if [[ -z "$base_p95" ]]; then
    echo "INFO: $bench is new — skipping regression check"
    continue
  fi
  ratio=$(awk -v l="$latest_p95" -v b="$base_p95" 'BEGIN { printf "%.2f", (l - b) / b * 100 }')
  printf "%-70s baseline=%-12s latest=%-12s delta=%+s%%\n" "$bench" "$base_p95" "$latest_p95" "$ratio"
  over=$(awk -v r="$ratio" -v t="$THRESHOLD_PCT" 'BEGIN { print (r > t) ? "1" : "0" }')
  if [[ "$over" == "1" ]]; then
    echo "FAIL: $bench p95 regressed by $ratio% (threshold ${THRESHOLD_PCT}%)" >&2
    failed=1
  fi
done < <(extract "$LATEST")

exit "$failed"
