#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Validates, then launches one run of the pool workload; snouty pushes the images and the config to the tenant
# registry.
#   [CONFIG=config-canary] [LAUNCHER=basic_test] launch.sh FAULTS DURATION   for example: launch.sh on 15m
# FAULTS is on or off. CONFIG names the config directory (default config); the canary's run is named scarab-canary.
# The run records the commit of the Scarab image that build.sh labelled.
# The default launcher is the tenant's own, amaru-cardano, as cardano-foundation/moog launches it: it applies node
# faults (kill, stop, pause), network faults and clock skips, and takes a comma-separated list of compose services to
# leave out of each class (`custom.*_exclusion`, moog spec 107); the driver is left out of all of them. On this tenant
# basic_test applied network faults only, whatever --fault-tolerance said, and faulted the driver too (README, "did
# not apply"). LAUNCHER=basic_test keeps it for comparison, FAULTS on as high_availability and off as none.
# The tenant predates --max-compute-hours (release 66), so the duration bounds the run. A 15m run cost 18 to 20
# compute hours, about 7 of them setup.
set -euo pipefail

faults=$1
duration=$2
case $faults in
  on | off) ;;
  *) echo "launch: FAULTS is on or off, not $faults" >&2; exit 2 ;;
esac
launcher=${LAUNCHER:-amaru-cardano}
if [ "$launcher" = basic_test ]; then
  level=none
  [ "$faults" = off ] || level=high_availability
  fault_args=(--fault-tolerance "$level" --fault-exclusions '^driver$')
else
  enabled=false
  [ "$faults" = off ] || enabled=true
  fault_args=(--param custom.faults_enabled="$enabled" --param custom.instrumented=true)
  for class in network_fault container_faults_kill container_faults_pause container_faults_stop; do
    fault_args+=(--param "custom.${class}_exclusion=driver")
  done
fi
here=$(cd "$(dirname "$0")/.." && pwd)
commit=$(docker image inspect scarab-at-scarab:trial --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
[ -n "$commit" ] || { echo "launch: scarab-at-scarab:trial has no commit label; build it with build.sh" >&2; exit 1; }
# Validation waits for setup complete: up to 600 s for the pools to agree, then up to 1200 s for the healthy-setup gate,
# and for the canary up to 600 s of cost-model enactment between the two.
validate_timeout=35m
case ${CONFIG:-config} in
  config-canary) name=scarab-canary validate_timeout=45m
    description="writeBits canary (GHSA-vfw2-hhrc-87v5), k = 5, on Scarab $commit with two cardano-node pools" ;;
  config-canary-scarab-vm) name=scarab-canary-scarab-vm validate_timeout=45m
    description="writeBits canary (GHSA-vfw2-hhrc-87v5), k = 5, Scarab $commit on its own Plutus evaluator" ;;
  config-reference) name=scarab-pool-reference
    description="control: cardano-node 11.1.3 in s1's seat with two cardano-node 11.1.3 pools, create-env defaults" ;;
  config-reference-late-start) name=scarab-pool-reference-late-start
    description="control: cardano-node 11.1.3 in s1's seat, its container started once the two pools listen" ;;
  config-late-start) name=scarab-pool-late-start
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, s1's container started once they listen" ;;
  config-k200) name=scarab-pool-k200
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, k = 200, 40000-slot epochs" ;;
  *) name=scarab-pool
    description="Scarab $commit pool with two cardano-node 11.1.3 pools, create-env defaults" ;;
esac

"$here/scripts/validate.sh" "$validate_timeout"
SNOUTY_CONTAINER_ENGINE=docker exec snouty launch \
  --launcher "$launcher" \
  --config "$here/${CONFIG:-config}" \
  --test-name "$name" \
  --description "$description" \
  --duration "$duration" \
  "${fault_args[@]}" \
  --param attrs.scarab="$commit" \
  --param attrs.reference=cardano-node-11.1.3 \
  --param attrs.plugin_version=0.1.0
