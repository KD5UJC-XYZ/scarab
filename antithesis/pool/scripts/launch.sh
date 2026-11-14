#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validates, then launches one basic_test run of the pool workload; snouty pushes the images and the config to the
# tenant registry.
#   [CONFIG=config-canary] launch.sh FAULT_TOLERANCE DURATION   for example: launch.sh resilient 15m
# CONFIG names the config directory (default config); the canary's run is named scarab-canary. The run records the
# commit of the Scarab image that build.sh labelled.
# The tenant predates --max-compute-hours (release 66), so the duration bounds the run. A 15m resilient run cost 18 to
# 20 compute hours, about 7 of them setup.
set -euo pipefail

faults=$1
duration=$2
here=$(cd "$(dirname "$0")/.." && pwd)
commit=$(docker image inspect scarab-at-scarab:trial --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
[ -n "$commit" ] || { echo "launch: scarab-at-scarab:trial has no commit label; build it with build.sh" >&2; exit 1; }
validate_timeout=10m
case ${CONFIG:-config} in
  config-canary) name=scarab-canary validate_timeout=35m
    description="writeBits canary (GHSA-vfw2-hhrc-87v5) on Scarab $commit with two cardano-node 11.1.3 pools" ;;
  config-canary-k5) name=scarab-canary-k5
    description="writeBits canary (GHSA-vfw2-hhrc-87v5), k = 5, on Scarab $commit with two cardano-node pools" ;;
  config-reference) name=scarab-pool-reference
    description="control: cardano-node 11.1.3 in s1's seat with two cardano-node 11.1.3 pools, create-env defaults" ;;
  config-reference-start-together) name=scarab-pool-reference-start-together
    description="control: cardano-node 11.1.3 in s1's seat, its container started with the two cardano-node pools" ;;
  config-start-together) name=scarab-pool-start-together
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, s1's container started with them" ;;
  config-k200) name=scarab-pool-k200
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, k = 200, 40000-slot epochs" ;;
  *) name=scarab-pool
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, create-env defaults" ;;
esac

"$here/scripts/validate.sh" "$validate_timeout"
SNOUTY_CONTAINER_ENGINE=docker exec snouty launch \
  --launcher basic_test \
  --config "$here/${CONFIG:-config}" \
  --test-name "$name" \
  --description "$description" \
  --duration "$duration" \
  --fault-tolerance "$faults" \
  --fault-exclusions '^driver$' \
  --param attrs.scarab="$commit" \
  --param attrs.reference=cardano-node-11.1.3 \
  --param attrs.plugin_version=0.1.0
