#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds the trial's three images from local base images. Only the driver's build has network access, for apt.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
SCARAB=ghcr.io/kd5ujc-xyz/scarab@sha256:1811062cbb86d33b38cdf5132b0a24c3de1624f42625980bc0fca785390461e5
REFERENCE=cardano-reference-11.1.3:local
JRE=eclipse-temurin@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81

docker image inspect "$REFERENCE" > /dev/null || {
  echo "build: $REFERENCE is missing; build it with scripts/prepare_reference_image.py" >&2; exit 1; }
docker image inspect "$SCARAB" > /dev/null && docker image inspect "$JRE" > /dev/null || {
  echo "build: pull $SCARAB and $JRE first" >&2; exit 1; }

build() { docker build --pull=false --network="$3" --platform linux/amd64 -t "$1" "$here/$2"; }
build scarab-at-cardano:trial cardano none
build scarab-at-scarab:trial scarab none
build scarab-at-driver:trial driver default
