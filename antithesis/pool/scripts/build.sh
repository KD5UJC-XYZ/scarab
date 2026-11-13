#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds the trial's images from local base images and a Scarab JAR. Only the driver's build has network access, for
# apt.
#   SCARAB_JAR=<JAR from node/assembly> [SCARAB_COMMIT=<what it was built from>] build.sh
# SCARAB_COMMIT defaults to the commit in the JAR's sbt-dynver name; launch.sh reads it back from the image's label.
set -euo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
REFERENCE=cardano-reference-11.1.3:local
JRE=eclipse-temurin@sha256:acd83cdf6c9b5bfcd7c4e9d3e1969bacaacdcfa034c074500ab28610fb840f81

jar=${SCARAB_JAR:?build: set SCARAB_JAR to a JAR assembled by node/assembly (see antithesis/README.md)}
[ -f "$jar" ] || { echo "build: $jar is not a file" >&2; exit 1; }
commit=${SCARAB_COMMIT:-}
if [ -z "$commit" ] && [[ $(basename "$jar") =~ \.g([0-9a-f]{7,40}) ]]; then
  commit=${BASH_REMATCH[1]}
fi
[ -n "$commit" ] || { echo "build: set SCARAB_COMMIT: $jar names no commit" >&2; exit 1; }

docker image inspect "$REFERENCE" > /dev/null || {
  echo "build: $REFERENCE is missing; build it with scripts/prepare_reference_image.py" >&2; exit 1; }
docker image inspect "$JRE" > /dev/null || { echo "build: pull $JRE first" >&2; exit 1; }

build() { docker build --pull=false --network="$3" --platform linux/amd64 -t "$1" "$here/$2"; }
build scarab-at-cardano:trial cardano none

# The JAR goes into a build context of its own, so that no JAR is ever left in the checkout.
context=$(mktemp -d)
trap 'rm -rf "$context"' EXIT
cp "$here/scarab/Dockerfile" "$here/scarab/scarab-node.sh" "$context/"
cp "$jar" "$context/scarab.jar"
docker build --pull=false --network=none --platform linux/amd64 --build-arg SCARAB_COMMIT="$commit" \
  -t scarab-at-scarab:trial "$context"

build scarab-at-driver:trial driver default
build scarab-at-canary:trial canary none
echo "build: Scarab $commit"
