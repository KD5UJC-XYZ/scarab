#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
mode="${1:?explicit preview, validate or write mode required}"
args=(--source "$SOURCE" --channel "$CHANNEL" --date "$RELEASE_DATE" --ordinal "$ORDINAL"
      --python "$RUNNER_TEMP/release-python/bin/python" --gh "$RUNNER_TEMP/release-tools/gh"
      --output "$RUNNER_TEMP/release-proposal")
if [ -n "$EXPECTED_HEAD" ]; then args+=(--expected-head "$EXPECTED_HEAD"); fi
if [ "$MAINTENANCE" = true ]; then args+=(--maintenance); fi
if [ -n "$NEXT_VERSION" ]; then args+=(--next-version "$NEXT_VERSION" --override-reason "$OVERRIDE_REASON"); fi
case "$mode" in
  preview) ;;
  validate) args+=(--validate) ;;
  write) test -n "${RELEASE_PR_TOKEN:?GitHub App token required}"; args+=(--write) ;;
  *) echo 'unknown preparation mode' >&2; exit 1 ;;
esac
node scripts/release-please/run.cjs "${args[@]}"
